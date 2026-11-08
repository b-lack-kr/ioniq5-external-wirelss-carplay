package kr.ioniq5.usbcp;

import java.io.*;
import java.nio.charset.Charset;

/** Root-owned, boot-scoped OEM power decision. No screen/ACC inference. */
public final class PowerLatch {
    static final Charset ASCII=Charset.forName("US-ASCII");
    public static final int ACCEPTED=0, DUPLICATE=1, REJECTED=2, FAILED=3;
    public static final int DIPO_COMMAND=1, WELCOME_COMMAND=2;
    public static boolean powerCommand(int command){return command==DIPO_COMMAND||command==WELCOME_COMMAND;}
    public static String source(int command){return command==DIPO_COMMAND?"DIPO":command==WELCOME_COMMAND?"WELCOME":"UNKNOWN";}
    public static final class Event {
        public final String boot;
        public final long nanos;
        public final boolean off;
        public Event(String boot,long nanos,boolean off){this.boot=boot;this.nanos=nanos;this.off=off;}
        public void write(DataOutputStream out)throws IOException {
            if(!validBoot(boot)||nanos<=0)throw new IOException("invalid power event");
            out.write(boot.getBytes(ASCII));out.writeLong(nanos);out.writeByte(off?0:1);
        }
        public static Event read(DataInputStream in)throws IOException {
            byte[] boot=new byte[36];in.readFully(boot);long nanos=in.readLong();int state=in.readUnsignedByte();
            String id=new String(boot,ASCII);
            if(!validBoot(id)||nanos<=0||state>1)throw new IOException("invalid power event");
            return new Event(id,nanos,state==0);
        }
    }
    private final File file,next;
    private final String boot;
    private Event last;
    private boolean fault;
    public PowerLatch(File file,String boot,long now)throws IOException {
        if(!validBoot(boot))throw new IOException("boot unavailable");
        this.file=file;this.next=new File(file.getPath()+".new");this.boot=boot;
        try {
            safe(file);safe(next);
            if(file.exists()) {
                String[] parts=readSmall(file,128).trim().split(" ");
                if(parts.length!=3||!validBoot(parts[0])||!parts[1].matches("[1-9][0-9]{0,17}")||
                        !("OFF".equals(parts[2])||"ON".equals(parts[2])))throw new IOException("invalid latch");
                long stamp=Long.parseLong(parts[1]);
                // A different boot is not a decision about this boot.
                if(boot.equals(parts[0])) {
                    if(stamp>now)throw new IOException("future latch");
                    last=new Event(boot,stamp,"OFF".equals(parts[2]));
                }
            }
        }catch(Exception invalid){fault=true;}
    }
    public synchronized boolean paused(){return fault||(last!=null&&last.off);}
    public synchronized String state(){return fault?"UNKNOWN":last==null?"UNKNOWN":last.off?"OFF":"ON";}
    public synchronized int accept(Event event,long now) {
        if(event==null||!boot.equals(event.boot)||event.nanos<=0||event.nanos>now)return REJECTED;
        if(last!=null) {
            if(event.nanos<last.nanos)return REJECTED;
            if(event.nanos==last.nanos)return event.off==last.off?DUPLICATE:REJECTED;
        }
        try {
            safe(file);safe(next);
            File parent=file.getParentFile();
            if(!parent.exists()&&!parent.mkdirs())throw new IOException("power directory");
            safe(parent);
            byte[] data=(boot+" "+event.nanos+" "+(event.off?"OFF":"ON")+"\n").getBytes(ASCII);
            FileOutputStream out=new FileOutputStream(next);
            try {out.write(data);out.getFD().sync();}finally{out.close();}
            if(!next.renameTo(file))throw new IOException("power commit");
            last=event;fault=false;return ACCEPTED;
        }catch(Exception unwritable){fault=true;return FAILED;}
    }
    static void safe(File file)throws IOException {
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile())||
                (file.exists()&&!file.isFile()&&!file.isDirectory()))throw new IOException("unsafe power path");
    }
    static String readSmall(File file,int max)throws IOException {
        FileInputStream in=new FileInputStream(file);
        try {ByteArrayOutputStream out=new ByteArrayOutputStream();int value;
            while((value=in.read())!=-1){if(out.size()>=max)throw new IOException("oversize");out.write(value);}
            return new String(out.toByteArray(),ASCII);
        }finally{in.close();}
    }
    public static boolean validBoot(String value){return value!=null&&value.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    public static String bootId()throws IOException {
        String boot=readSmall(new File("/proc/sys/kernel/random/boot_id"),40).trim();
        if(!validBoot(boot))throw new IOException("boot unavailable");return boot;
    }
    public static boolean dipoPeer(int uid,int pid) {
        return dipoPeer(uid,pid,new File("/proc"));
    }
    static boolean dipoPeer(int uid,int pid,File proc) {
        return powerPeer(DIPO_COMMAND,uid,pid,proc);
    }
    public static boolean powerPeer(int command,int uid,int pid) {
        return powerPeer(command,uid,pid,new File("/proc"));
    }
    static boolean powerPeer(int command,int uid,int pid,File proc) {
        if(!powerCommand(command))return false;
        if(uid!=1000||pid<=1)return false;
        try {FileInputStream in=new FileInputStream(new File(proc,pid+"/cmdline"));
            try {ByteArrayOutputStream name=new ByteArrayOutputStream();int value;
                while((value=in.read())>0){if(name.size()>=64)return false;name.write(value);}
                String expected=command==DIPO_COMMAND?"com.daudio.app.dipo":"com.mobis.welcome";
                return expected.equals(new String(name.toByteArray(),ASCII));
            }finally{in.close();}
        }catch(IOException unavailable){return false;}
    }
}
