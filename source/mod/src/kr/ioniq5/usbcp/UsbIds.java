// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;
import java.io.*;
import java.util.*;

/** Only runtime IDs present in HKG USB Wi-Fi 2.0.2's detector.
 * CD-ROM mode 0bda:1a2b is intentionally not accepted as a working AP.
 */
public final class UsbIds {
    private static final Set<String> AC=ids("0bda:b812 0bda:b82c 0bda:b81a 0bda:b820 0bda:b82b 2357:012d 2357:012e 2357:0138 2357:0115 0b05:184c 0b05:1841 0b05:19aa 7392:b822 7392:c822 2001:331c 2001:331e 0846:9055 13b1:0043 056e:4011");
    private static final Set<String> AX=ids("0bda:b831 0bda:b851 0bda:b832 3625:010b 2357:0142 7392:e611 2001:332a 2c4e:0128 056e:4027 056e:4028 0411:03ed");
    private static Set<String> ids(String all){return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(all.split(" "))));}
    public static String family(int vendor,int product) {
        if(vendor<0||vendor>0xffff||product<0||product>0xffff)return null;
        String id=String.format(Locale.US,"%04x:%04x",vendor,product);
        return AC.contains(id)?"88x2bu":AX.contains(id)?"8851bu":null;
    }
    public static boolean supports(int vendor,int product){return family(vendor,product)!=null;}
    public static final class Identity {
        public final int vendor,product;public final String path,family,busdev,method;
        Identity(int v,int p,String path,String family){this(v,p,path,family,"direct","ancestry");}
        Identity(int v,int p,String path,String family,String busdev,String method){this.vendor=v;this.product=p;this.path=path;this.family=family;this.busdev=busdev;this.method=method;}
        public String id(){return String.format(Locale.US,"%04x:%04x",vendor,product);}
        public boolean same(Identity other){return other!=null && vendor==other.vendor && product==other.product && path.equals(other.path) && busdev.equals(other.busdev) && method.equals(other.method);}
    }
    static String oneLine(File f)throws IOException {
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("USB attribute symlink refused");
        BufferedReader r=new BufferedReader(new FileReader(f));
        try{String s=r.readLine();if(s==null||!s.trim().matches("(?i)[0-9a-f]{4}"))throw new IOException("USB attribute invalid");return s.trim();}
        finally{r.close();}
    }
    public static final class Lookup {
        public final Identity identity; public final String reason, id;
        Lookup(Identity i,String r,String id){identity=i;reason=r;this.id=id;}
    }
    public static final class LookupFailure extends IOException {
        public final String reason,id;
        LookupFailure(Lookup r){super("unsupported USB identity");reason=r.reason;id=r.id;}
    }
    static String decimal(File f)throws IOException {
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("USB attribute symlink refused");
        BufferedReader r=new BufferedReader(new FileReader(f));try{String s=r.readLine();
            if(s==null || !s.trim().matches("[0-9]{1,4}"))throw new IOException("USB bus attribute invalid");
            return Integer.toString(Integer.parseInt(s.trim()));}finally{r.close();}
    }
    /** HKG hot-unplug patch intentionally removes netdev/wiphy USB parent links.
     * The fallback associates one driver-bound USB device with its per-interface
     * Realtek proc entry. This is a single-device inference, not direct ancestry.
     */
    public static Lookup lookup(File root,String iface)throws IOException {
        if(iface==null || !iface.matches("rtwlan[0-9]{1,3}"))return new Lookup(null,"bad_iface","none");
        File sys=new File(root,"sys/devices").getCanonicalFile();String prefix=sys.getPath()+File.separator;
        File net=new File(root,"sys/class/net/"+iface);
        if(!net.exists())return new Lookup(null,"iface_missing","none");
        File link=new File(net,"device");
        if(link.exists()) {
            File d=link.getCanonicalFile();
            if(!d.getPath().startsWith(prefix))return new Lookup(null,"parent_outside_sys","none");
            for(int i=0;i<12 && d!=null && d.getPath().startsWith(prefix);i++,d=d.getParentFile()) {
                File vendor=new File(d,"idVendor"),product=new File(d,"idProduct");
                if(vendor.isFile() && product.isFile()) {
                    int v=Integer.parseInt(oneLine(vendor),16),p=Integer.parseInt(oneLine(product),16);
                    String family=family(v,p),id=String.format(Locale.US,"%04x:%04x",v,p);
                    return family==null?new Lookup(null,"id_unsupported",id):new Lookup(new Identity(v,p,d.getPath(),family),"direct","none");
                }
            }
            return new Lookup(null,"parent_without_usb","none");
        }
        File realNet=net.getCanonicalFile();
        if(!realNet.getPath().startsWith(prefix) || !realNet.getPath().contains("/virtual/net/"))
            return new Lookup(null,"no_parent_not_virtual","none");
        boolean ac=new File(root,"proc/net/rtl88x2bu/"+iface).isDirectory();
        boolean ax=new File(root,"proc/net/rtl8851bu/"+iface).isDirectory();
        if(ac==ax)return new Lookup(null,ac?"ambiguous_proc_driver":"proc_driver_missing","none");
        String expected=ac?"88x2bu":"8851bu";
        File[] buses=new File(root,"sys/bus/usb/devices").listFiles();
        if(buses==null)return new Lookup(null,"usb_bus_missing","none");
        Identity candidate=null;int boundCount=0;String unknown="none";
        Set<String> seen=new HashSet<String>();
        for(File entry:buses){
            File d=entry.getCanonicalFile();
            if(!d.getPath().startsWith(prefix) || !seen.add(d.getPath()))continue;
            File vid=new File(d,"idVendor"),pid=new File(d,"idProduct");
            if(!vid.isFile()||!pid.isFile())continue;
            File[] children=d.listFiles();if(children==null)continue;
            String boundFamily=null;
            for(File child:children){
                if(!child.getName().startsWith(d.getName()+":"))continue;
                File driver=new File(child,"driver");if(!driver.exists())continue;
                for(String familyName:new String[]{"88x2bu","8851bu"}) {
                    File wanted=new File(root,"sys/bus/usb/drivers/rtl"+familyName).getCanonicalFile();
                    if(driver.getCanonicalFile().equals(wanted)) {
                        if(boundFamily!=null && !boundFamily.equals(familyName))return new Lookup(null,"ambiguous_usb_driver","none");
                        boundFamily=familyName;
                    }
                }
            }
            if(boundFamily==null)continue;
            boundCount++;
            int v=Integer.parseInt(oneLine(vid),16),p=Integer.parseInt(oneLine(pid),16);
            String id=String.format(Locale.US,"%04x:%04x",v,p),family=family(v,p);
            if(!expected.equals(boundFamily) || !expected.equals(family)){unknown=id;continue;}
            String bus=decimal(new File(d,"busnum")),dev=decimal(new File(d,"devnum"));
            candidate=new Identity(v,p,d.getPath(),family,bus+"-"+dev,"single_bound_usb");
        }
        if(boundCount>1)return new Lookup(null,"ambiguous_usb","none");
        if(boundCount==0)return new Lookup(null,"usb_driver_unbound","none");
        if(candidate==null)return new Lookup(null,"id_unsupported",unknown);
        return new Lookup(candidate,"single_bound_usb",candidate.id());
    }
    public static Identity identify(File root,String iface)throws IOException {return lookup(root,iface).identity;}
}
