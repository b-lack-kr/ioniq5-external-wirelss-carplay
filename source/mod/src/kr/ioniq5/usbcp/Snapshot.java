package kr.ioniq5.usbcp;

import java.io.*;
import java.util.*;

/** Immutable, bounded IPC record. Never stringify credentials. */
public final class Snapshot {
    public static final int MAGIC = 0x49413531;
    public static final long MAX_AGE_MS = 4000;
    public final String generation, iface, ip, bssid, ssid, password;
    public final long observed;
    public final int ifindex, frequency;
    public final boolean ready;
    public Snapshot(String generation, long observed, boolean ready, String iface, int ifindex,
                    String ip, String bssid, String ssid, String password, int frequency) {
        this.generation=generation; this.observed=observed; this.ready=ready; this.iface=iface;
        this.ifindex=ifindex; this.ip=ip; this.bssid=bssid; this.ssid=ssid;
        this.password=password; this.frequency=frequency;
        if (!valid()) throw new IllegalArgumentException("invalid AP record");
    }
    public static Snapshot down(String epoch, long now) {
        return new Snapshot(epoch,now,false,"",0,"","","","",0);
    }
    private boolean valid() {
        if (generation==null || !generation.matches("[a-zA-Z0-9-]{1,80}") || observed<0) return false;
        if (!ready) return iface.isEmpty() && ifindex==0 && ip.isEmpty() && bssid.isEmpty()
            && ssid.isEmpty() && password.isEmpty() && frequency==0;
        return iface.matches("rtwlan[0-9]{1,3}") && ifindex>0 && ipv4(ip)
            && bssid.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
            && !bssid.equals("00:00:00:00:00:00") && (Integer.parseInt(bssid.substring(0,2),16)&1)==0
            && utf8Length(ssid)>0 && utf8Length(ssid)<=32 && clean(ssid)
            && password.length()>=8 && password.length()<=63 && password.matches("[ -~]+")
            && frequency==5180;
    }
    private static int utf8Length(String s) { try{return s.getBytes("UTF-8").length;}catch(Exception e){return -1;} }
    private static boolean clean(String s) {for(char c:s.toCharArray())if(c<32 || c==127)return false;return true;}
    public static boolean ipv4(String ip) {
        if (ip==null || !ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] a=ip.split("\\."); for(String n:a) if(Integer.parseInt(n)>255 || (n.length()>1 && n.charAt(0)=='0'))return false;
        return Integer.parseInt(a[0])==192 && Integer.parseInt(a[1])==168 && Integer.parseInt(a[3])>0 && Integer.parseInt(a[3])<255;
    }
    public boolean fresh(long now) {return ready && now>=observed && now-observed<=MAX_AGE_MS;}
    public boolean sameAp(Snapshot s) {return s!=null && ready==s.ready && iface.equals(s.iface)
        && ifindex==s.ifindex && ip.equals(s.ip) && bssid.equals(s.bssid) && ssid.equals(s.ssid)
        && password.equals(s.password) && frequency==s.frequency;}
    public void write(DataOutput out) throws IOException {
        out.writeInt(MAGIC);out.writeUTF(generation);out.writeLong(observed);out.writeBoolean(ready);
        out.writeUTF(iface);out.writeInt(ifindex);out.writeUTF(ip);out.writeUTF(bssid);
        out.writeUTF(ssid);out.writeUTF(password);out.writeInt(frequency);
    }
    public static Snapshot read(DataInput in) throws IOException {
        if(in.readInt()!=MAGIC)throw new IOException("protocol version");
        try{return new Snapshot(in.readUTF(),in.readLong(),in.readBoolean(),in.readUTF(),in.readInt(),
            in.readUTF(),in.readUTF(),in.readUTF(),in.readUTF(),in.readInt());}
        catch(IllegalArgumentException e){throw new IOException("invalid AP record");}
    }
    public static Map<String,String> pairs(String data) throws IOException {
        if(data.length()>16384)throw new IOException("record too large");
        Map<String,String> out=new HashMap<String,String>();
        for(String line:data.split("\n")) {
            if(line.isEmpty() || line.charAt(0)=='#')continue;
            int eq=line.indexOf('=');if(eq<1)continue;
            String k=line.substring(0,eq),v=line.substring(eq+1);
            if(out.put(k,v)!=null)throw new IOException("duplicate configuration key");
        }return out;
    }
    @Override public String toString(){return ready ? "AP_READY (credentials redacted)" : "AP_UNAVAILABLE";}
}
