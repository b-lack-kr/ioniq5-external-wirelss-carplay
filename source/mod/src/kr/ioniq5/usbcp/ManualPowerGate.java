// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import java.io.*;
import java.nio.charset.Charset;

/** Read-only manual-connect permission. Never changes the OEM power latch. */
public final class ManualPowerGate {
    private static final File BASE=new File("/data/local/tmp/neev_usbcp");
    private static final File PROC=new File("/proc");
    private static final Charset ASCII=Charset.forName("US-ASCII");
    private static final Decision SYSTEM=new Decision();
    private static final Reader SYSTEM_READER=new Reader(BASE,PROC);
    private ManualPowerGate() {}

    public static synchronized boolean allowed(long nowNanos) {
        try {
            String boot=read(new File(PROC,"sys/kernel/random/boot_id"),128).trim();
            File event=new File(BASE,"power-event");
            // Canonical-path validation also detects dangling symlinks.
            safe(event);
            String eventText=event.exists()?read(event,256):null;
            String state=read(new File(BASE,"power-state"),512);
            return SYSTEM.allow(boot,nowNanos,eventText,state,SYSTEM_READER.alive(nowNanos));
        } catch(Exception unavailable) { return false; }
    }

    static void safe(File file)throws IOException {
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile()) ||
                (file.exists()&&!file.isFile()))throw new IOException("invalid power input");
    }
    static String read(File file,int limit)throws IOException {
        if(limit<0)throw new IOException("invalid input limit");
        safe(file);
        FileInputStream in=new FileInputStream(file);
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(limit,1024));
            byte[] buffer=new byte[1024];
            while(true) {
                // Read at most one byte beyond the limit, including exact-limit EOF checks.
                int wanted=(int)Math.min(buffer.length,(long)limit-out.size()+1);
                int count=in.read(buffer,0,wanted);
                if(count<0)break;
                if(count==0||count>limit-out.size())throw new IOException("bounded power input");
                out.write(buffer,0,count);
            }
            return new String(out.toByteArray(),ASCII);
        }finally{in.close();}
    }
    private static boolean argument(String command,String expected) {
        for(String s:command.split("\u0000"))if(expected.equals(s))return true;
        return false;
    }
    private static final String SERVICE="/data/hmgapi/mods/neev-usb-carplay/ap/service.sh";
    private static final String POWER="kr.ioniq5.usbcp.HeadUnitPower";
    private static int statusParent(String status)throws IOException {
        boolean root=false,live=false,uidSeen=false,stateSeen=false,parentSeen=false;int parent=-1;
        for(String line:status.split("\n")) {
            String[] p=line.trim().split("\\s+");
            if("Uid:".equals(p[0])) {
                if(uidSeen||p.length!=5)throw new IOException("invalid uid");uidSeen=true;
                root="0".equals(p[1])&&"0".equals(p[2])&&"0".equals(p[3])&&"0".equals(p[4]);
            }
            if("State:".equals(p[0])) {
                if(stateSeen||p.length<2)throw new IOException("invalid state");stateSeen=true;
                live=p[1].matches("[RSDTtWPIK]");
            }
            if("PPid:".equals(p[0])) {
                if(parentSeen||p.length!=2||!p[1].matches("0|[1-9][0-9]{0,8}"))throw new IOException("invalid parent");
                parentSeen=true;parent=Integer.parseInt(p[1]);
            }
        }
        if(!root||!live||!parentSeen)throw new IOException("not a live root process");
        return parent;
    }
    static boolean process(String status,int parent) {
        try{int actual=statusParent(status);return parent==0||parent==actual;}
        catch(IOException invalid){return false;}
    }
    static boolean readerAlive(File base,File proc)throws IOException {
        return new Reader(base,proc).alive(System.nanoTime());
    }
    private static final class Identity {
        final int pid,parent;final long started;
        Identity(int pid,int parent,long started){this.pid=pid;this.parent=parent;this.started=started;}
        boolean same(Identity other){return other!=null&&pid==other.pid&&parent==other.parent&&started==other.started;}
    }
    private static Identity stat(File file,int pid)throws IOException {
        String value=read(file,2048);int open=value.indexOf('('),close=value.lastIndexOf(')');
        if(open<1||close<=open||!value.substring(0,open).trim().equals(Integer.toString(pid)))throw new IOException("invalid stat pid");
        // comm may contain spaces or parentheses; fields after its final ')' are numeric/state.
        String[] p=value.substring(close+1).trim().split("\\s+");
        if(p.length<20||!p[0].matches("[RSDTtWPIK]")||!p[1].matches("0|[1-9][0-9]{0,8}")||!p[19].matches("0|[1-9][0-9]{0,18}"))
            throw new IOException("invalid stat fields");
        try{return new Identity(pid,Integer.parseInt(p[1]),Long.parseLong(p[19]));}
        catch(NumberFormatException invalid){throw new IOException("invalid stat time");}
    }
    private static Identity identity(File proc,int pid,String expected)throws IOException {
        File directory=new File(proc,Integer.toString(pid));File stat=new File(directory,"stat");
        Identity before=stat(stat,pid);
        int parent=statusParent(read(new File(directory,"status"),8192));
        if(parent!=before.parent||!argument(read(new File(directory,"cmdline"),2048),expected))throw new IOException("process identity changed");
        Identity after=stat(stat,pid);
        if(!before.same(after))throw new IOException("process replaced during read");
        return after;
    }
    /** Cache only process locations/incarnations. Re-read all authority on every request. */
    static final class Reader {
        static final long RESCAN_NANOS=2000000000L;
        private final File base,proc;
        private Identity parent,child;
        private long lastScan=-1;
        Reader(File base,File proc){this.base=base;this.proc=proc;}
        private void forget(){parent=null;child=null;}
        synchronized boolean alive(long now) {
            if(now<0)return false;
            try {
                String pid=read(new File(base,"daemon.pid"),32).trim();
                if(!pid.matches("[1-9][0-9]{0,8}"))throw new IOException("invalid daemon pid");
                int number=Integer.parseInt(pid);if(number<=1)throw new IOException("invalid daemon pid");
                Identity current=identity(proc,number,SERVICE);
                if(current.parent<=0)throw new IOException("invalid daemon parent");
                if(parent!=null) {
                    if(!parent.same(current)){forget();return false;}
                    Identity verified=identity(proc,child.pid,POWER);
                    if(!child.same(verified)||verified.parent!=current.pid){forget();return false;}
                    return true;
                }
                if(lastScan>=0&&(now<lastScan||now-lastScan<RESCAN_NANOS))return false;
                lastScan=now;
                File[] all=proc.listFiles();if(all==null)return false;
                int seen=0;
                for(File p:all) {
                    if(!p.getName().matches("[1-9][0-9]{0,8}"))continue;
                    if(++seen>4096)return false;
                    try {
                        int candidate=Integer.parseInt(p.getName());
                        // Status first avoids cmdline/stat reads for unrelated processes.
                        if(!process(read(new File(p,"status"),8192),current.pid))continue;
                        Identity found=identity(proc,candidate,POWER);
                        if(found.parent==current.pid&&current.same(identity(proc,current.pid,SERVICE))) {
                            parent=current;child=found;return true;
                        }
                    }catch(IOException exited){ /* A process may exit during enumeration. */ }
                }
            }catch(IOException unavailable){forget();}
            return false;
        }
    }

    static final class Decision {
        private String boot;
        private long latest=-1;
        private boolean off=true,eventSeen;
        private static boolean bootId(String s){return s!=null&&s.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
        private static long number(String s,long max)throws IOException {
            if(!s.matches("0|[1-9][0-9]{0,17}"))throw new IOException("invalid time");
            long n=Long.parseLong(s);if(n>max)throw new IOException("future time");return n;
        }
        private static String[] fields(String s,int count)throws IOException {
            if(s==null||s.length()>512)throw new IOException("missing record");
            if(s.endsWith("\n"))s=s.substring(0,s.length()-1);
            String[] p=s.split(" ",-1);
            if(p.length!=count||s.indexOf('\n')>=0||s.indexOf('\r')>=0)throw new IOException("invalid record");
            return p;
        }
        private void choose(boolean nextOff,long nanos) {
            if(nanos>latest||(nanos==latest&&nextOff)){latest=nanos;off=nextOff;}
        }
        synchronized boolean allow(String currentBoot,long now,String event,String reader,boolean live) {
            if(!bootId(currentBoot)||now<0)return false;
            if(!currentBoot.equals(boot)){boot=currentBoot;latest=-1;off=true;eventSeen=false;}
            try {
                if(event!=null) {
                    String[] p=fields(event,3);
                    if(!bootId(p[0]))return false;
                    if(boot.equals(p[0])) {
                        eventSeen=true;
                        long n=number(p[1],now);
                        if(n==0||!("ON".equals(p[2])||"OFF".equals(p[2])))return false;
                        choose("OFF".equals(p[2]),n);
                    } else eventSeen=false;
                } else if(eventSeen)return false;
                String[] p=fields(reader,6);
                if(!boot.equals(p[3])||!("ON".equals(p[1])||"OFF".equals(p[1]))||
                        !p[2].matches("unknown|0|[1-9][0-9]*")||!p[5].matches("[A-Z_]+"))return false;
                long seconds=number(p[0],now/1000000000L),decision=number(p[4],now);
                boolean readerOff="OFF".equals(p[1]);
                if(readerOff&&decision==0)return false;
                // An OFF is durable even if its writer has since stopped.
                boolean fresh=live&&now/1000000000L-seconds<=6;
                if(readerOff||fresh)choose(readerOff,decision);
                // Manual connect is stricter than background boot startup:
                // require a live, fresh OEM reader as well as latest ON.
                return fresh&&!off;
            }catch(Exception invalid){return false;}
        }
    }
}
