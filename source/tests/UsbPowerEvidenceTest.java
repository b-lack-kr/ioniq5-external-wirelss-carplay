package kr.ioniq5.usbcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.Arrays;

/** Real UsbIds/SafeInterface selection on a synthetic sysfs tree, no host USB access. */
public final class UsbPowerEvidenceTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void write(Path p,String text)throws Exception{Files.createDirectories(p.getParent());Files.write(p,text.getBytes("UTF-8"));}
    private static String repeat(char c,int n){char[] chars=new char[n];Arrays.fill(chars,c);return new String(chars);}
    private static void rejected(File root,String iface,String token,String phase)throws Exception{
        boolean failed=false;try{UsbPowerEvidence.observe(root,iface,token,phase);}catch(IOException expected){failed=true;}
        check(failed,"must reject invalid or changed identity: "+iface+" "+phase);
    }
    private static String token(File root)throws Exception{String s=SafeInterface.selection(root,"rtwlan0");check(s!=null,"real identity selection");return s.substring(s.lastIndexOf(' ')+1);}
    private static String observeReadonly(File root,String token,String phase)throws Exception{
        SecurityManager before=System.getSecurityManager();
        System.setSecurityManager(new SecurityManager(){
            @Override public void checkPermission(Permission p){}
            @Override public void checkWrite(String file){throw new AssertionError("probe attempted write");}
            @Override public void checkDelete(String file){throw new AssertionError("probe attempted delete");}
            @Override public void checkExec(String command){throw new AssertionError("probe attempted process");}
        });
        try{return UsbPowerEvidence.observe(root,"rtwlan0",token,phase);}
        finally{System.setSecurityManager(before);}
    }
    public static void main(String[] args)throws Exception{
        Path p=Files.createDirectories(new File(args[0]).toPath()).toRealPath();File root=p.toFile();
        Path device=p.resolve("sys/devices/platform/usb1/1-1"),net=p.resolve("sys/class/net/rtwlan0");
        write(device.resolve("idVendor"),"2357\n");write(device.resolve("idProduct"),"0138\n");
        write(device.resolve("busnum"),"1\n");write(device.resolve("devnum"),"2\n");
        write(net.resolve("ifindex"),"35\n");Files.createSymbolicLink(net.resolve("device"),device);
        Files.createDirectories(p.resolve("proc/net/rtl88x2bu/rtwlan0"));
        Path control=device.resolve("power/control"),runtime=device.resolve("power/runtime_status"),authorized=device.resolve("authorized"),wakeup=device.resolve("power/wakeup");
        write(control,"on\n");write(runtime,"active\n");write(authorized,"1\n");write(wakeup,"disabled\n");
        String selected=token(root),active=observeReadonly(root,selected,"ACTIVE");
        check(active.equals("AP_USB_POWER phase=ACTIVE control=ON runtime=ACTIVE authorized=1 wakeup=DISABLED vbus_handler=UNKNOWN physical_switch=UNVERIFIED"),"fixed complete output");
        check(!active.contains(p.toString())&&!active.contains(selected),"no path or identity token in evidence");
        write(control,"auto\n");write(runtime,"suspended\n");write(authorized,"0\n");write(wakeup,"enabled\n");
        check(observeReadonly(root,selected,"STOPPING").contains("control=AUTO runtime=SUSPENDED authorized=0 wakeup=ENABLED"),"fixed stopping enums");
        for(String state:new String[]{"active","suspended","suspending","resuming","unsupported","error"}){
            write(runtime,state+"\n");check(observeReadonly(root,selected,"ACTIVE").contains(" runtime="+state.toUpperCase(java.util.Locale.US)+" "),"runtime enum "+state);
        }
        for(String bad:new String[]{"auto\nSECRET_NETWORK", "ON", "../secret", "on\nAP_USB_POWER phase=FAKE", "é"}){
            write(control,bad);check(observeReadonly(root,selected,"ACTIVE").contains(" control=UNKNOWN "),"reject raw value leakage");
        }
        write(control,"on"+repeat(' ',62));check(UsbPowerEvidence.value(control.toFile(),"on auto").equals("ON"),"64-byte limit accepted");
        write(control,"on"+repeat(' ',63));check(UsbPowerEvidence.value(control.toFile(),"on auto").equals("UNKNOWN"),"65-byte value rejected");
        write(control,repeat('x',1024*1024));check(UsbPowerEvidence.value(control.toFile(),"on auto").equals("UNKNOWN"),"large value rejected with bounded read");
        Files.delete(control);check(observeReadonly(root,selected,"ACTIVE").contains(" control=UNKNOWN "),"missing attr unknown");
        Path secret=p.resolve("outside-secret");write(secret,"auto\n");Files.createSymbolicLink(control,secret);
        check(observeReadonly(root,selected,"ACTIVE").contains(" control=UNKNOWN "),"attribute symlink rejected");
        Files.delete(control);write(control,"on\n");
        Path realPower=device.resolve("real-power");Files.move(device.resolve("power"),realPower);Files.createSymbolicLink(device.resolve("power"),realPower);
        check(observeReadonly(root,selected,"ACTIVE").contains("control=UNKNOWN runtime=UNKNOWN authorized=0 wakeup=UNKNOWN"),"symlink parent rejected");
        Files.delete(device.resolve("power"));Files.move(realPower,device.resolve("power"));
        for(String iface:new String[]{"wlan0","rtwlan0/../../outside","rtwlan1000","rtwlanx"})rejected(root,iface,selected,"ACTIVE");
        rejected(root,"rtwlan0",repeat('0',64),"ACTIVE");rejected(root,"rtwlan0",selected.toUpperCase(java.util.Locale.US),"ACTIVE");
        rejected(root,"rtwlan0",selected,"STOPPED");rejected(root,"rtwlan0",selected,"ACTIVE\nLEAK");
        write(device.resolve("devnum"),"3\n");rejected(root,"rtwlan0",selected,"ACTIVE");selected=token(root);
        write(net.resolve("ifindex"),"36\n");rejected(root,"rtwlan0",selected,"ACTIVE");selected=token(root);
        check(observeReadonly(root,selected,"ACTIVE").endsWith("physical_switch=UNVERIFIED"),"fresh identity accepted without physical claim");
        Files.delete(net.resolve("device"));Files.createSymbolicLink(net.resolve("device"),p.resolve("outside"));Files.createDirectories(p.resolve("outside"));
        rejected(root,"rtwlan0",selected,"ACTIVE");Files.delete(net.resolve("device"));Files.createSymbolicLink(net.resolve("device"),device);
        Path init=p.resolve("init.rc");
        for(String notTrigger:new String[]{"# on property:sys.usb.vbus_ctrl=0\n", "setprop sys.usb.vbus_ctrl 0\n", "write /a sys.usb.vbus_ctrl\n", "on boot # property:sys.usb.vbus_ctrl=0\n", "on property:sys.usb.vbus_ctrl_extra=0\n"}){
            write(init,notTrigger);check(!UsbPowerEvidence.hasVbusTrigger(root,"init.rc"),"reference is not handler");
        }
        for(String trigger:new String[]{"on property:sys.usb.vbus_ctrl=0\n", "  on property:sys.usb.vbus_ctrl=1\r\n", "on property:sys.boot_completed=1 && property:sys.usb.vbus_ctrl=*\n", "on property:sys.usb.vbus_ctrl=0 && property:other=yes\n"}){
            write(init,trigger);check(UsbPowerEvidence.hasVbusTrigger(root,"init.rc"),"property trigger recognized");
        }
        write(init,repeat('#',2030)+"\non property:sys.usb.vbus_ctrl=0\n");
        check(UsbPowerEvidence.hasVbusTrigger(root,"init.rc"),"trigger split across read chunks");
        check(observeReadonly(root,selected,"ACTIVE").contains("vbus_handler=FOUND physical_switch=UNVERIFIED"),"handler presence is not proof of switch");
        write(init,repeat('#',UsbPowerEvidence.MAX_INIT_BYTES)+"\non property:sys.usb.vbus_ctrl=0\n");
        check(!UsbPowerEvidence.hasVbusTrigger(root,"init.rc"),"ignore data beyond scan bound");
        String header="on property:sys.usb.vbus_ctrl=1";
        write(init,repeat('#',UsbPowerEvidence.MAX_INIT_BYTES-header.length()-1)+"\n"+header+"garbage\n");
        check(!UsbPowerEvidence.hasVbusTrigger(root,"init.rc"),"truncated final line not handler");
        Files.delete(init);write(p.resolve("elsewhere"),"on property:sys.usb.vbus_ctrl=0\n");Files.createSymbolicLink(init,p.resolve("elsewhere"));
        check(!UsbPowerEvidence.hasVbusTrigger(root,"init.rc"),"init symlink rejected");
        check(observeReadonly(root,selected,"STOPPING").contains("vbus_handler=UNKNOWN"),"fixed output for rejected init path");
        System.out.println("UsbPowerEvidenceTest PASS checks="+checks);
    }
}
