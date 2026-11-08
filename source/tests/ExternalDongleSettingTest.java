package kr.ioniq5.usbcp;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class ExternalDongleSettingTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static void put(File f,String value)throws Exception{Files.write(f.toPath(),value.getBytes(StandardCharsets.UTF_8));}
    static void rejected(File f,String value)throws Exception{put(f,value);try{ExternalDongleSetting.read(f);throw new AssertionError("accepted malformed fixture");}catch(IOException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        File dir=new File(args[0]);dir.mkdirs();File f=new File(dir,"settings.json");
        check(ExternalDongleSetting.read(f),"missing owner file retains working default");
        for(String value:new String[]{"{}","{\"other\":false}","{\"external_dongle\":true}","{\"nested\":{\"external_dongle\":false},\"text\":\"external_dongle false\"}"}){put(f,value);check(ExternalDongleSetting.read(f),"true/missing key/nested key default");}
        for(String value:new String[]{"{\"external_dongle\":false}"," { \"external_\\u0064ongle\" : false, \"other\" : [null,{},-1.2e+3,\"한글\\n\"] } \n"}){put(f,value);check(!ExternalDongleSetting.read(f),"literal and escaped-key false");}
        for(String value:new String[]{"","[]","null","true","{\"external_dongle\":\"false\"}","{\"external_dongle\":0}","{\"external_dongle\":null}","{\"external_dongle\":{}}","{\"external_dongle\":false,\"external_dongle\":true}","{\"external_dongle\":false,\"external_\\u0064ongle\":true}","{\"external_dongle\":false}{}","{\"external_dongle\":true,}","{\"external_dongle\":trueish}","{\"x\":01}","{\"x\":1.}","{\"x\":1e}","{\"x\":+1}","{\"x\":NaN}","{\"x\":\"\\uＦＦＦＦ\"}","{\"x\":\"line\nline\"}","{\"x\":[1,]}","{\"x\":{\"a\":1,\"a\":2}}"})rejected(f,value);
        String deep="0";for(int i=0;i<18;i++)deep="["+deep+"]";rejected(f,"{\"x\":"+deep+"}");
        byte[] badUtf={(byte)'{',(byte)'"',(byte)0xff,(byte)'"',(byte)':',(byte)'0',(byte)'}'};Files.write(f.toPath(),badUtf);
        try{ExternalDongleSetting.read(f);throw new AssertionError("invalid UTF8 accepted");}catch(IOException ok){checks++;}
        StringBuilder exact=new StringBuilder("{}");while(exact.length()<ExternalDongleSetting.MAX_BYTES)exact.append(' ');put(f,exact.toString());check(ExternalDongleSetting.read(f),"exact size accepted");rejected(f,exact.append(' ').toString());
        f.delete();f.mkdir();try{ExternalDongleSetting.read(f);throw new AssertionError("directory accepted");}catch(IOException ok){checks++;}f.delete();
        File target=new File(dir,"target.json");put(target,"{\"external_dongle\":false}");Files.createSymbolicLink(f.toPath(),target.toPath());
        try{ExternalDongleSetting.read(f);throw new AssertionError("link accepted");}catch(IOException ok){checks++;}Files.delete(f.toPath());
        Files.createSymbolicLink(f.toPath(),new File(dir,"missing-target").toPath());
        try{ExternalDongleSetting.read(f);throw new AssertionError("dangling link treated missing");}catch(IOException ok){checks++;}Files.delete(f.toPath());
        File parentLink=new File(dir,"parent-link");Files.createSymbolicLink(parentLink.toPath(),new File(dir,"missing-parent").toPath());
        try{ExternalDongleSetting.read(new File(parentLink,"settings.json"));throw new AssertionError("dangling parent treated missing");}catch(IOException ok){checks++;}Files.delete(parentLink.toPath());
        final String boot="12345678-1234-1234-1234-123456789abc";File event=new File(dir,"power-event");
        ApState off=new ApState("test",0,false);PowerLatch latch=new PowerLatch(event,boot,100);
        PowerControl control=new PowerControl(latch,off,0);
        Snapshot ready=new Snapshot("radio",1,true,"rtwlan0",3,"192.168.43.1","02:11:22:33:44:55","Hyundai_123456","fixture-secret",5180);
        off.publish(off.revision(),ready);check(!off.current(1).ready&&!off.demanded(),"disabled provider cannot publish startup snapshot");
        check(control.accept(new PowerLatch.Event(boot,10,true),100,1)==PowerLatch.ACCEPTED,"logical OFF retained");
        check(control.accept(new PowerLatch.Event(boot,20,false),100,1)==PowerLatch.ACCEPTED,"logical ON recorded independently");
        off.publish(off.revision(),ready);check(!off.current(1).ready&&!off.demanded(),"OEM ON cannot override user OFF");
        check(!off.failed(off.revision(),2,true),"observer retry cannot revive disabled AP");
        ApState stillOff=new ApState("restart",1,false);new PowerControl(new PowerLatch(event,boot,100),stillOff,1);
        stillOff.publish(stillOff.revision(),ready);check(!stillOff.current(1).ready,"disabled sameboot restart remains off");
        ApState enabled=new ApState("enabled",1,true);new PowerControl(new PowerLatch(event,boot,100),enabled,1);enabled.publish(enabled.revision(),ready);
        check(enabled.current(1).ready,"enabled restart may publish with saved logical ON");
        PowerControl onControl=new PowerControl(new PowerLatch(event,boot,100),enabled,1);onControl.accept(new PowerLatch.Event(boot,30,true),100,2);
        ApState enabledOff=new ApState("again",2,true);new PowerControl(new PowerLatch(event,boot,100),enabledOff,2);enabledOff.publish(enabledOff.revision(),ready);
        check(!enabledOff.current(2).ready,"user enabled never clears saved logical OFF");
        System.out.println("ExternalDongleSettingTest PASS checks="+checks);
    }
}
