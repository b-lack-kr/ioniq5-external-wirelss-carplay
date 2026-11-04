package kr.ioniq5.usbcp;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import java.net.*;
import java.util.*;

/** Root-only observer of the owned USB access point. Does not start another hostapd. */
public final class LiveAp {
    public String phase="init";
    public String credentialEvidence="none";
    private int lastHostapd,lastDnsmasq;
    private Map<String,String> lastWanted,lastConfig;
    private UsbIds.Identity lastUsbIdentity;
    private String usbCandidate="method=none id=none";
    public String usbCandidateDiagnostic(){return usbCandidate;}
    public String usbDiagnostic() {
        UsbIds.Identity x=lastUsbIdentity;
        return x==null?"method=none id=none":"method="+x.method+" id="+x.id();
    }
    /** Used only after a read timeout, never after an explicit security/identity failure. */
    public boolean sameIdentity(Snapshot s) {
        try {
            if(s==null||!s.ready||lastWanted==null||lastConfig==null)return false;
            if(pid("hostapd")!=lastHostapd||pid("dnsmasq")!=lastDnsmasq)return false;
            if(!command(lastHostapd).contains("hostapd_usb\u0000")||!command(lastDnsmasq).contains("--interface="+s.iface+"\u0000"))return false;
            NetworkInterface ni=NetworkInterface.getByName(s.iface);
            return ni!=null&&ni.isUp()&&lastUsbIdentity!=null
                &&lastUsbIdentity.same(UsbIds.identify(new File("/"),s.iface))&&address(ni).equals(s.ip)
                &&Integer.parseInt(kernelText("/sys/class/net/"+s.iface+"/ifindex").trim())==s.ifindex
                &&kernelText("/sys/class/net/"+s.iface+"/address").trim().equalsIgnoreCase(s.bssid)
                &&lastWanted.equals(Snapshot.pairs(text(new File(CONFIG),8192)))
                &&lastConfig.equals(Snapshot.pairs(text(new File(WORK,"hostapd.conf"),8192)));
        }catch(Exception e){return false;}
    }
    static final String WORK="/data/misc/wifi/neev_usbcp";
    static final String TMP="/data/local/tmp/neev_usbcp";
    static final String CONFIG="/data/nemesis/neev-usbcp/config/hotspot.conf";
    static String text(File f,int max) throws IOException {
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("symlink refused");
        InputStream in=new FileInputStream(f);
        try {ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;
            while((n=in.read(buf))!=-1){if(b.size()+n>max)throw new IOException("file too large");b.write(buf,0,n);}
            return b.toString("UTF-8");
        }finally{in.close();}
    }
    static int pid(String name) throws Exception {
        String s=text(new File(TMP,name+".pid"),32).trim();
        if(!s.matches("[1-9][0-9]{0,8}"))throw new IOException("invalid PID");
        return Integer.parseInt(s);
    }
    static String command(int pid) throws Exception {return text(new File("/proc/"+pid+"/cmdline"),8192);}
    public static final class ControlFailure extends IOException {
        public final String step, kind;
        ControlFailure(String step,Exception cause){super("hostapd control failed");this.step=step;this.kind=errorKind(cause);}
    }
    static String errorKind(Exception e) {
        if(e instanceof java.net.SocketTimeoutException)return "timeout";
        String message=e.getMessage();
        for(String known:new String[]{"unexpected AP configuration","unsupported interface","unsupported USB identity","USB identity changed during read","process ownership mismatch","interface unavailable","AP identity mismatch","AP restarted while reading","AP not ready or unsupported security","ambiguous address","no AP address","invalid PID","symlink refused","file too large"})
            if(known.equals(message))return known.toLowerCase(java.util.Locale.US).replace(' ','_');
        if(message!=null && (message.equals("configured_ssid_mismatch") || message.equals("active_ssid_mismatch")
            || message.equals("configured_passphrase_mismatch") || message.equals("active_passphrase_mismatch")
            || message.equals("active_psk_mismatch") || message.equals("missing_active_credentials")
            || message.equals("configuration_changed_during_read")))return message;
        String m=e.getMessage()==null?"":e.getMessage().toLowerCase(java.util.Locale.US);
        if(m.contains("permission denied") || m.contains("eacces"))return "permission_denied";
        if(m.contains("operation not permitted") || m.contains("eperm"))return "not_permitted";
        if(m.contains("no such file") || m.contains("enoent"))return "missing_endpoint";
        if(m.contains("connection refused") || m.contains("econnrefused"))return "connection_refused";
        if(m.contains("timed out") || m.contains("try again") || m.contains("temporarily unavailable") || m.contains("eagain"))return "timeout";
        if(m.contains("socket not created"))return "socket_not_created";
        if(m.contains("duplicate configuration key"))return "duplicate_record_key";
        if(m.contains("invalid control response"))return "invalid_response";
        return "io_or_runtime_error";
    }
    static String control(String iface,String request) throws Exception {
        // Abstract client address: hostapd can reply even if it drops privileges.
        // A root-only filesystem client directory can prevent the reply sendto().
        if(!iface.matches("rtwlan[0-9]{1,3}") || !(request.equals("STATUS") || request.equals("GET_CONFIG")))
            throw new IOException("unsupported control request");
        LocalSocket socket=new LocalSocket(LocalSocket.SOCKET_DGRAM);
        String step="bind";
        try {
            socket.bind(new LocalSocketAddress("ioniq5-aa-ctrl-"+UUID.randomUUID().toString(),LocalSocketAddress.Namespace.ABSTRACT));
            step="timeout";socket.setSoTimeout(500);
            step="connect";socket.connect(new LocalSocketAddress(WORK+"/ctrl/"+iface,LocalSocketAddress.Namespace.FILESYSTEM));
            step="send";socket.getOutputStream().write(request.getBytes("US-ASCII"));
            step="receive";byte[] data=new byte[8192];int n=socket.getInputStream().read(data);
            if(n<=0 || n==data.length)throw new IOException("invalid control response");
            return new String(data,0,n,"UTF-8");
        }catch(Exception e){throw new ControlFailure(step,e);}
        finally{try{socket.close();}catch(IOException ignored){}}
    }
    static String trim(String value){return value==null?null:value.trim();}
    static String address(NetworkInterface ni) throws Exception {
        String ip=null;Enumeration<InetAddress> all=ni.getInetAddresses();
        while(all.hasMoreElements()) {InetAddress a=all.nextElement();if(a instanceof Inet4Address && Snapshot.ipv4(a.getHostAddress())){
            if(ip!=null)throw new IOException("ambiguous address");ip=a.getHostAddress();}}
        if(ip==null)throw new IOException("no AP address");return ip;
    }
    public Snapshot observe(String generation,long now) throws Exception {
        usbCandidate="method=none id=none";
        phase="configuration";
        Map<String,String> wanted=Snapshot.pairs(text(new File(CONFIG),8192));
        if(!"ap".equals(wanted.get("WIFI_MODE"))
            || !"a".equals(wanted.get("AP_HW_MODE")) || !"36".equals(wanted.get("AP_CHANNEL"))
            || !"0".equals(wanted.get("ENABLE_LTE_NAT")) || !"0".equals(wanted.get("AUTO_UPDATE")))
            throw new IOException("unexpected AP configuration");
        Map<String,String> conf=Snapshot.pairs(text(new File(WORK,"hostapd.conf"),8192));
        String iface=conf.get("interface");
        if(iface==null || !iface.matches("rtwlan[0-9]{1,3}"))throw new IOException("unsupported interface");
        phase="net_identity";
        int initialIndex=Integer.parseInt(kernelText("/sys/class/net/"+iface+"/ifindex").trim());
        phase="usb_identity";
        UsbIds.Lookup lookup=UsbIds.lookup(new File("/"),iface);
        UsbIds.Identity actual=lookup.identity;
        if(actual==null)throw new UsbIds.LookupFailure(lookup);
        usbCandidate="method="+actual.method+" id="+actual.id();
        phase="process_identity";
        int hp=pid("hostapd"),dp=pid("dnsmasq");
        String hc=command(hp),dc=command(dp);
        if(!hc.contains("hostapd_usb\u0000") || !hc.contains(WORK+"/hostapd.conf\u0000")
            || !dc.contains("dnsmasq") || !dc.contains("--interface="+iface+"\u0000"))throw new IOException("process ownership mismatch");
        phase="hostapd_status";
        Map<String,String> status=Snapshot.pairs(control(iface,"STATUS"));
        phase="hostapd_config";
        Map<String,String> active=Snapshot.pairs(control(iface,"GET_CONFIG"));
        phase="hostapd_ready_security";
        requireSecurity(status,active);
        phase="credentials_match";
        String ssid=wanted.get("AP_SSID"),password=wanted.get("AP_PASS");
        credentialEvidence=requireCredentials(wanted,conf,active);
        phase="interface_identity";
        NetworkInterface ni=NetworkInterface.getByName(iface);
        if(ni==null || !ni.isUp())throw new IOException("interface unavailable");
        String ip=address(ni),mac=kernelText("/sys/class/net/"+iface+"/address").trim();
        // /sys/class/net entries are symlinks: allow their known kernel-owned canonical paths.
        if(!ip.equals(wanted.get("AP_IP")) || !mac.equalsIgnoreCase(active.get("bssid")))throw new IOException("AP identity mismatch");
        int index=Integer.parseInt(kernelText("/sys/class/net/"+iface+"/ifindex").trim());
        phase="stable_processes";
        if(hp!=pid("hostapd") || dp!=pid("dnsmasq"))throw new IOException("AP restarted while reading");
        if(!wanted.equals(Snapshot.pairs(text(new File(CONFIG),8192)))
            || !conf.equals(Snapshot.pairs(text(new File(WORK,"hostapd.conf"),8192))))
            throw new IOException("configuration_changed_during_read");
        if(index!=initialIndex || !actual.same(UsbIds.identify(new File("/"),iface)))throw new IOException("USB identity changed during read");
        lastHostapd=hp;lastDnsmasq=dp;lastWanted=new HashMap<String,String>(wanted);lastConfig=new HashMap<String,String>(conf);
        lastUsbIdentity=actual;
        return new Snapshot(generation+"-"+hp+"-"+dp+"-"+actual.busdev.replace("direct","0-0"),now,true,iface,index,ip,mac,ssid,password,5180);
    }
    /** GET_CONFIG omits secret fields when WPS is disabled. Never treat an omitted
     * secret as an observed match: report the file-backed source explicitly. */
    static String requireCredentials(Map<String,String> wanted,Map<String,String> conf,Map<String,String> active) throws Exception {
        String ssid=wanted.get("AP_SSID"),pass=wanted.get("AP_PASS");
        if(ssid==null || !ssid.equals(conf.get("ssid")))throw new IOException("configured_ssid_mismatch");
        if(!ssid.equals(active.get("ssid")))throw new IOException("active_ssid_mismatch");
        if(pass==null || !pass.equals(conf.get("wpa_passphrase")))throw new IOException("configured_passphrase_mismatch");
        boolean verified=false;
        if(active.containsKey("passphrase")) {
            if(!pass.equals(active.get("passphrase")))throw new IOException("active_passphrase_mismatch");
            verified=true;
        }
        if(active.containsKey("psk")) {
            String psk=active.get("psk");
            if(!psk.matches("(?i)[0-9a-f]{64}"))throw new IOException("active_psk_mismatch");
            javax.crypto.spec.PBEKeySpec spec=new javax.crypto.spec.PBEKeySpec(pass.toCharArray(),ssid.getBytes("UTF-8"),4096,256);
            byte[] derived;
            try{derived=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();}
            finally{spec.clearPassword();}
            byte[] expected=new byte[32];
            for(int i=0;i<32;i++)expected[i]=(byte)Integer.parseInt(psk.substring(i*2,i*2+2),16);
            boolean same=java.security.MessageDigest.isEqual(derived,expected);
            java.util.Arrays.fill(derived,(byte)0);java.util.Arrays.fill(expected,(byte)0);
            if(!same)throw new IOException("active_psk_mismatch");
            verified=true;
        }
        if(verified)return "control_verified";
        // Only the documented WPS-disabled omission is supported. The running
        // PID's command must name this config; caller checks security, identity,
        // and unchanged config before returning a snapshot.
        if(!"disabled".equals(active.get("wps_state"))
            || (conf.containsKey("wps_state") && !"0".equals(conf.get("wps_state"))))
            throw new IOException("missing_active_credentials");
        return "config_file_wps_disabled";
    }
    static void requireSecurity(Map<String,String> status,Map<String,String> active) throws IOException {
        if(!"ENABLED".equals(status.get("state")) || !"5180".equals(status.get("freq"))
            || !"2".equals(active.get("wpa")) || !"WPA-PSK".equals(trim(active.get("key_mgmt")))
            || !"CCMP".equals(trim(active.get("rsn_pairwise_cipher"))))throw new IOException("AP not ready or unsupported security");
    }
    static String kernelText(String path) throws IOException {
        File f=new File(path).getCanonicalFile();
        if(!f.getPath().startsWith("/sys/devices/"))throw new IOException("unexpected sysfs path");
        return text(f,256);
    }
}
