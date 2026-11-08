// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import java.io.*;

/** Read-only observations. Runtime suspend controls and an init trigger header do
 * not prove that any action switches physical VBUS for the selected USB port. */
public final class UsbPowerEvidence {
    static final int MAX_INIT_BYTES=262144;
    private static final java.util.regex.Pattern VBUS_TRIGGER=java.util.regex.Pattern.compile(
        "(?m)^[ \\t]*on[ \\t]+(?:[^#\\r\\n]*?[ \\t]+&&[ \\t]+)?property:sys\\.usb\\.vbus_ctrl=[^ \\t\\r\\n#]+(?:[ \\t\\r]|$)");
    static String value(File file,String allowed) {
        try {
            if(!file.getCanonicalFile().equals(file.getAbsoluteFile()) || !file.isFile())return "UNKNOWN";
            InputStream in=new FileInputStream(file); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try {int b;while((b=in.read())!=-1){if(bytes.size()>=64)return "UNKNOWN";bytes.write(b);}}
            finally{in.close();}
            String v=bytes.toString("US-ASCII").trim();
            for(String entry:allowed.split(" "))if(entry.equals(v))return v.toUpperCase(java.util.Locale.US);
        }catch(IOException ignored){}
        return "UNKNOWN";
    }
    static boolean hasVbusTrigger(File root,String relative) {
        File file=new File(root,relative);
        try {
            if(!file.getCanonicalFile().equals(file.getAbsoluteFile()) || !file.isFile())return false;
            InputStream in=new FileInputStream(file);
            try {
                int n,total=0;byte[] buf=new byte[2048];StringBuilder text=new StringBuilder();
                while(total<MAX_INIT_BYTES&&(n=in.read(buf,0,Math.min(buf.length,MAX_INIT_BYTES-total)))!=-1){
                    total+=n;text.append(new String(buf,0,n,"US-ASCII"));
                }
                // Ignore the final partial line if the scan hit the size limit. A token
                // in a comment, write/setprop command or truncated header is not a trigger.
                if(total==MAX_INIT_BYTES){int end=text.lastIndexOf("\n");if(end<0)return false;text.setLength(end+1);}
                return VBUS_TRIGGER.matcher(text).find();
            }finally{in.close();}
        }catch(IOException ignored){}
        return false;
    }
    static String observe(File root,String iface,String token,String phase)throws Exception {
        if(!iface.matches("rtwlan[0-9]{1,3}") || !token.matches("[0-9a-f]{64}") ||
                !("ACTIVE".equals(phase)||"STOPPING".equals(phase)))throw new IOException("invalid request");
        String before=SafeInterface.selection(root,iface);
        if(before==null||!before.endsWith(" "+token))throw new IOException("identity changed");
        UsbIds.Identity owner=UsbIds.identify(root,iface);
        if(owner==null)throw new IOException("no owner");
        File device=new File(owner.path);
        String control=value(new File(device,"power/control"),"on auto");
        String runtime=value(new File(device,"power/runtime_status"),"active suspended suspending resuming unsupported error");
        String authorized=value(new File(device,"authorized"),"0 1");
        String wakeup=value(new File(device,"power/wakeup"),"enabled disabled");
        boolean handler=false;
        for(String path:new String[]{"init.rc","init.tcc803x.rc","init.tcc8030.rc","init.usb.rc"})
            handler|=hasVbusTrigger(root,path);
        String after=SafeInterface.selection(root,iface);
        if(!before.equals(after))throw new IOException("identity changed");
        return "AP_USB_POWER phase="+phase+" control="+control+" runtime="+runtime+
                " authorized="+authorized+" wakeup="+wakeup+" vbus_handler="+(handler?"FOUND":"UNKNOWN")+
                " physical_switch=UNVERIFIED";
    }
    public static void main(String[] args) {
        try {
            if(args.length!=3)System.exit(2);
            System.out.println(observe(new File("/"),args[0],args[1],args[2]));
        }catch(Exception unavailable){System.out.println("AP_USB_POWER unavailable=1");System.exit(1);}
    }
}
