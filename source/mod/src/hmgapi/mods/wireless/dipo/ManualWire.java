package hmgapi.mods.wireless.dipo;

import java.io.*;
import java.util.Locale;

/** Fixed-size local control protocol. Never contains names, paths or Wi-Fi credentials. */
public final class ManualWire {
    public static final String SOCKET="ioniq5.cp.manual.v1", UI="com.android.btsettings", DIPO="com.daudio.app.dipo";
    public static final int MAGIC=0x49434d31, QUERY=0, RETRY=1, PROVIDER_GATE=3, PROVIDER_PREPARE=4;
    public static final int READY=0, ACCEPTED=1, CONNECTED=2, BUSY=3, POWER_OFF=4,
        DISABLED=5, AP_UNAVAILABLE=6, PHONE_REQUIRED=7, NOT_APPLE=8, TIMED_OUT=9, FAILED=10;
    public static final int CURRENT_HFP=1, ACTIVE_CP=2, ACTIVE_TARGET=4, PENDING_TARGET=8;
    private ManualWire() {}
    public static boolean peer(int uid,int pid,String expected) { return peer(uid,pid,expected,new File("/proc")); }
    static boolean peer(int uid,int pid,String expected,File proc) {
        if(uid!=1000 || pid<=1 || !(UI.equals(expected)||DIPO.equals(expected)))return false;
        try {
            FileInputStream in=new FileInputStream(new File(proc,pid+"/cmdline"));
            try {
                ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
                while((c=in.read())>0) {if(out.size()>=64)return false;out.write(c);}
                return c==0 && expected.equals(new String(out.toByteArray(),"US-ASCII"));
            }finally{in.close();}
        }catch(IOException e){return false;}
    }
    public static byte[] address(String text) {
        if(text==null || !text.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))throw new IllegalArgumentException("invalid phone identity");
        byte[] out=new byte[6];for(int i=0;i<6;i++)out[i]=(byte)Integer.parseInt(text.substring(i*3,i*3+2),16);
        requireAddress(out);return out;
    }
    public static void requireAddress(byte[] value) {
        if(value==null || value.length!=6)throw new IllegalArgumentException("invalid phone identity");
        int any=0,notFF=0;for(byte b:value){any|=b&255;notFF|=(b&255)^255;}
        if(any==0 || notFF==0)throw new IllegalArgumentException("invalid phone identity");
    }
    public static String format(byte[] value) {
        requireAddress(value);StringBuilder out=new StringBuilder(17);
        for(int i=0;i<6;i++){if(i!=0)out.append(':');out.append(String.format(Locale.US,"%02X",value[i]&255));}return out.toString();
    }
    public static final class Request {
        public final int action;public final long sequence;public final byte[] address;
        public Request(int action,long sequence,byte[] address) {
            if((action!=QUERY&&action!=RETRY)||sequence<=0)throw new IllegalArgumentException("invalid manual request");
            requireAddress(address);this.action=action;this.sequence=sequence;this.address=address.clone();
        }
        public void write(DataOutput out,long nonceHi,long nonceLo)throws IOException {
            out.writeLong(nonceHi);out.writeLong(nonceLo);out.writeByte(action);out.writeLong(sequence);out.write(address);
        }
        public static Request read(DataInput in,long nonceHi,long nonceLo)throws IOException {
            if(in.readLong()!=nonceHi || in.readLong()!=nonceLo)throw new IOException("stale manual server");
            int action=in.readUnsignedByte();long sequence=in.readLong();byte[] address=new byte[6];in.readFully(address);
            try{return new Request(action,sequence,address);}catch(IllegalArgumentException e){throw new IOException("invalid manual request");}
        }
    }
    /** Same-process requests are monotonic; the per-server nonce scopes this window. */
    public static final class ReplayWindow {
        private long last;
        public boolean accept(long sequence,long now) {
            if(sequence<=last||sequence>now||now-sequence>2000000000L)return false;
            last=sequence;return true;
        }
    }
    public static class Result {
        public final int status,flags;
        public Result(int status,int flags) {
            if(status<READY||status>FAILED||(flags&~15)!=0)throw new IllegalArgumentException("invalid manual result");
            this.status=status;this.flags=flags;
        }
        public void write(DataOutput out)throws IOException {out.writeInt(MAGIC);out.writeByte(status);out.writeByte(flags);}
        public static Result read(DataInput in)throws IOException {
            if(in.readInt()!=MAGIC)throw new IOException("manual response version");
            try{return new Result(in.readUnsignedByte(),in.readUnsignedByte());}
            catch(IllegalArgumentException e){throw new IOException("invalid manual response");}
        }
    }
}
