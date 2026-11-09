package kr.ioniq5.usbcp;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public class ManualPowerGateTest {
    static final String B="b47b5244-1f58-4a0d-ae48-77d26d1ca874",OLD="91a7d19b-402b-41b6-8044-31e65241ac7b";
    static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static String r(long heartbeat,String state,long time){return heartbeat+" "+state+" 0 "+B+" "+time+" MICOM_ACC_ON\n";}
    static String e(String state,long time){return B+" "+time+" "+state+"\n";}
    static void put(File p,String text)throws Exception{p.getParentFile().mkdirs();Files.write(p.toPath(),text.getBytes(StandardCharsets.US_ASCII));}
    static String stat(int pid,int parent,long started) {
        StringBuilder s=new StringBuilder(pid+" (reader (name)) S "+parent);
        for(int i=0;i<17;i++)s.append(" 0");return s.append(' ').append(started).append(" 0\n").toString();
    }
    static void processFile(File proc,int pid,int parent,long started,String command)throws Exception {
        put(new File(proc,pid+"/status"),"Name:\treader\nState:\tS (sleeping)\nPPid:\t"+parent+"\nUid:\t0\t0\t0\t0\n");
        put(new File(proc,pid+"/cmdline"),command+"\0");
        put(new File(proc,pid+"/stat"),stat(pid,parent,started));
    }
    static final String SERVICE="/system/bin/sh\0/data/hmgapi/mods/neev-usb-carplay/ap/service.sh";
    static final String POWER="/system/bin/app_process\0/system/bin\0kr.ioniq5.usbcp.HeadUnitPower";
    static final class Proc extends File {
        int scans;
        Proc(File f){super(f.getPath());}
        @Override public File[] listFiles(){scans++;return super.listFiles();}
    }
    static void cachedReader(Path root)throws Exception {
        File base=root.resolve("cached-base").toFile();Proc proc=new Proc(root.resolve("cached-proc").toFile());
        put(new File(base,"daemon.pid"),"200\n");
        processFile(proc,200,1,100,SERVICE);processFile(proc,300,200,200,POWER);
        ManualPowerGate.Reader cache=new ManualPowerGate.Reader(base,proc);
        long now=10000000000L;
        check(cache.alive(now),"cold reader discovered");check(proc.scans==1,"cold single proc enumeration");
        for(int i=0;i<100;i++)check(cache.alive(now+i),"cached authority revalidated");
        check(proc.scans==1,"100 hot calls do not enumerate proc");
        put(new File(proc,"300/cmdline"),"kr.ioniq5.usbcp.HeadUnitPowerImposter\0");
        check(!cache.alive(now+100),"cached reader cmdline is not authority");
        processFile(proc,300,200,200,POWER);
        check(!cache.alive(now+101),"failed cached reader cannot bypass scan cooldown");
        check(proc.scans==1,"invalid cache does not trigger full scans");
        now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"verified reader discovered after cooldown");
        check(proc.scans==2,"one rediscovery");
        // A real directory replacement uses the same PID and otherwise identical command.
        File moved=new File(proc,"old-reader");check(new File(proc,"300").renameTo(moved),"replace PID directory");
        processFile(proc,300,200,201,POWER);
        check(!cache.alive(now+1),"PID reuse with same command is invalidated by starttime");
        now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"replacement incarnation must pass a new scan");
        put(new File(proc,"200/cmdline"),"/system/bin/sh\0unrelated.sh\0");
        check(!cache.alive(now+1),"cached parent command rechecked");
        processFile(proc,200,1,100,SERVICE);now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"parent restored only after fresh discovery");
        processFile(proc,200,1,101,SERVICE);
        check(!cache.alive(now+1),"same daemon PID reused is rejected");
        now+=ManualPowerGate.Reader.RESCAN_NANOS;check(cache.alive(now),"new daemon incarnation is rediscovered");
        processFile(proc,200,7,101,SERVICE);
        check(!cache.alive(now+1),"cached daemon PPid rechecked");
        now+=ManualPowerGate.Reader.RESCAN_NANOS;check(cache.alive(now),"reparented daemon requires new scan");
        processFile(proc,300,99,201,POWER);
        check(!cache.alive(now+1),"cached reader PPid rechecked");
        now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(!cache.alive(now),"unrelated root reader is not discovered");
        int scanned=proc.scans;
        for(int i=0;i<20;i++)check(!cache.alive(now+i+1),"missing child stays denied");
        check(proc.scans==scanned,"missing reader rescan bounded");
        processFile(proc,300,200,201,POWER);now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"reader child restored");
        put(new File(proc,"300/status"),"State:\tZ (zombie)\nPPid:\t200\nUid:\t0\t0\t0\t0\n");
        check(!cache.alive(now+1),"cached zombie cannot authorize");
        processFile(proc,300,200,202,POWER);now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"reader recreated");
        put(new File(proc,"300/status"),"State:\tS\nPPid:\t200\nUid:\t0\t0\t1000\t0\n");
        check(!cache.alive(now+1),"cached reader saved UID must be root");
        processFile(proc,300,200,202,POWER);now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"reader restored before parent UID change");
        put(new File(proc,"200/status"),"State:\tS\nPPid:\t7\nUid:\t0\t0\t0\t1000\n");
        check(!cache.alive(now+1),"cached parent filesystem UID must be root");
        processFile(proc,201,1,500,SERVICE);processFile(proc,301,201,600,POWER);
        put(new File(base,"daemon.pid"),"201\n");now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"new daemon PID and child discovered");
        processFile(proc,202,1,700,SERVICE);processFile(proc,302,202,800,POWER);
        put(new File(base,"daemon.pid"),"202\n");
        scanned=proc.scans;
        check(!cache.alive(now+1),"changing to a valid different parent invalidates old cache");
        check(proc.scans==scanned,"parent switch does not force immediate enumeration");
        now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"replacement parent and child pair must be verified afresh");
        check(new File(proc,"302/status").delete(),"reader disappears between calls");
        check(!cache.alive(now+1),"missing cached reader denied immediately");
        check(proc.scans==scanned+1,"missing cached reader does not enumerate immediately");
        processFile(proc,302,202,801,POWER);now+=ManualPowerGate.Reader.RESCAN_NANOS;
        check(cache.alive(now),"new live reader is discovered after disappearance");
        put(new File(base,"daemon.pid"),"200\n");
        check(!cache.alive(now+1),"daemon pid file rechecked on every hot call");
        File secondBase=root.resolve("other-base").toFile();Proc secondProc=new Proc(root.resolve("other-proc").toFile());
        put(new File(secondBase,"daemon.pid"),"201\n");processFile(secondProc,201,1,500,SERVICE);
        ManualPowerGate.Reader independent=new ManualPowerGate.Reader(secondBase,secondProc);
        check(!independent.alive(now),"independent fixture cannot inherit PID authority");
        check(secondProc.scans==1,"independent discovery scope");
        processFile(secondProc,301,201,600,POWER);check(!independent.alive(now+1),"independent missing scan throttled");
        check(independent.alive(now+ManualPowerGate.Reader.RESCAN_NANOS),"independent root reader discovered");
        put(new File(secondProc,"301/stat"),stat(301,201,600).replace(" 600 "," 9223372036854775808 "));
        check(!independent.alive(now+ManualPowerGate.Reader.RESCAN_NANOS+1),"malformed incarnation fails closed");
        check(!independent.alive(-1),"negative lookup clock denied");
    }
    public static void main(String[] args)throws Exception {
        final long N=100000000000L;
        ManualPowerGate.Decision d=new ManualPowerGate.Decision();
        check(d.allow(B,N,null,r(100,"ON",0),true),"fresh initial OEM ON permits manual request");
        check(!d.allow(B,N,null,r(100,"ON",0),false),"dead reader denied");
        check(!d.allow(B,N,null,r(93,"ON",0),true),"stale ON denied");
        check(!d.allow(B,N,null,r(101,"ON",0),true),"future heartbeat denied");
        check(!d.allow(B,N,e("OFF",90),r(100,"ON",89),true),"newer OFF beats fresh older ON");
        check(!d.allow(B,N,e("OFF",90),r(100,"ON",90),true),"OFF wins equal timestamp");
        check(d.allow(B,N,e("OFF",90),r(100,"ON",91),true),"new OEM ON recovers");
        check(!d.allow(B,N,null,r(100,"ON",92),true),"removing seen event fails closed");
        check(!d.allow(B,N,e("OFF",93),r(90,"OFF",93),false),"durable OFF denies when reader stopped");
        check(!d.allow(B,N,e("ON",92),r(100,"ON",92),true),"older records do not undo last OFF");
        check(d.allow(B,N,e("ON",94),r(100,"OFF",93),true),"newer verified event ON can beat older reader OFF");
        check(!d.allow(B,N,e("ON",N+1),r(100,"ON",95),true),"future event denied");
        check(!d.allow(B,N,e("ON",0),r(100,"ON",95),true),"zero event invalid");
        for(String malformed:new String[]{"",B+" 1 YES",e("ON",96)+"extra",B+" 01 ON",B+" -1 ON"})
            check(!d.allow(B,N,malformed,r(100,"ON",97),true),"malformed event");
        for(String malformed:new String[]{"",r(100,"UNKNOWN",1),r(100,"OFF",0),r(100,"ON",1)+"extra",r(100,"ON",1).replace("MICOM_ACC_ON","path/unsafe"),r(100,"ON",1).replace(B,OLD)})
            check(!new ManualPowerGate.Decision().allow(B,N,null,malformed,true),"malformed reader");
        check(new ManualPowerGate.Decision().allow(B,N,OLD+" 99 OFF\n",r(100,"ON",1),true),"old boot event ignored");
        check(!new ManualPowerGate.Decision().allow("unknown",N,null,r(100,"ON",1),true),"bad boot denied");
        check(!new ManualPowerGate.Decision().allow(B,-1,null,r(100,"ON",1),true),"negative clock denied");
        // Captured 2026-11-08 reader heartbeat/uptime: 3309s at uptime 3311.74s,
        // ON decision 440963441209ns; persisted OFF belongs to the previous boot.
        String captured=r(3309,"ON",440963441209L),previous=OLD+" 182241649585 OFF\n";
        check(new ManualPowerGate.Decision().allow(B,3311740000000L,previous,captured,true),"captured live OEM ON and previous-boot OFF permit");
        check(!new ManualPowerGate.Decision().allow(B,3311740000000L,previous,captured,false),"captured record still requires a verified writer");
        check(!new ManualPowerGate.Decision().allow(B,3316000000000L,previous,captured,true),"captured heartbeat expires without refresh");
        Path temp=Files.createTempDirectory("manual-power-test-").toRealPath();File base=temp.resolve("base").toFile(),proc=temp.resolve("proc").toFile();
        try {
            put(new File(base,"daemon.pid"),"12520\n");
            put(new File(proc,"12520/status"),"Name:\tsh\nState:\tS (sleeping)\nPPid:\t181\nUid:\t0\t0\t0\t0\n");
            put(new File(proc,"12520/cmdline"),"/system/bin/sh\0/data/hmgapi/mods/neev-usb-carplay/ap/service.sh\0");
            put(new File(proc,"13911/status"),"State:\tS (sleeping)\nPPid:\t12520\nUid:\t0\t0\t0\t0\n");
            put(new File(proc,"13911/cmdline"),"/system/bin/app_process\0/system/bin\0kr.ioniq5.usbcp.HeadUnitPower\0");
            put(new File(proc,"12520/stat"),stat(12520,181,100));
            put(new File(proc,"13911/stat"),stat(13911,12520,200));
            check(ManualPowerGate.readerAlive(base,proc),"exact root child and script accepted");
            put(new File(proc,"13911/status"),"State:\tS\nPPid:\t99\nUid:\t0\t0\t0\t0\n");
            check(!ManualPowerGate.readerAlive(base,proc),"reader must belong to owned service");
            put(new File(proc,"13911/status"),"State:\tZ\nPPid:\t12520\nUid:\t0\t0\t0\t0\n");
            check(!ManualPowerGate.readerAlive(base,proc),"zombie reader denied");
            put(new File(proc,"13911/status"),"State:\tS\nPPid:\t12520\nUid:\t1000\t1000\t1000\t1000\n");
            check(!ManualPowerGate.readerAlive(base,proc),"non-root reader denied");
            put(new File(proc,"13911/status"),"State:\tS\nPPid:\t12520\nUid:\t0\t0\t0\t0\n");
            put(new File(proc,"13911/cmdline"),"kr.ioniq5.usbcp.HeadUnitPowerImposter\0");
            check(!ManualPowerGate.readerAlive(base,proc),"partial process name denied");
            File f=new File(base,"text");put(f,"12345");
            try{ManualPowerGate.read(f,4);throw new AssertionError("oversize read accepted");}catch(IOException expected){checks++;}
            File bulk=new File(base,"bulk");
            for(int length:new int[]{0,1,1023,1024,1025,8192}) {
                char[] chars=new char[length];java.util.Arrays.fill(chars,'a');String text=new String(chars);put(bulk,text);
                check(ManualPowerGate.read(bulk,length).equals(text),"bulk exact-limit content preserved");
                if(length>0)try{ManualPowerGate.read(bulk,length-1);throw new AssertionError("bulk oversize accepted");}catch(IOException expected){checks++;}
            }
            try{ManualPowerGate.read(bulk,-1);throw new AssertionError("negative limit accepted");}catch(IOException expected){checks++;}
            Path link=base.toPath().resolve("link");Files.createSymbolicLink(link,f.toPath());
            try{ManualPowerGate.read(link.toFile(),64);throw new AssertionError("symlink accepted");}catch(IOException expected){checks++;}
            check(Files.readAllBytes(f.toPath()).length==5,"read-only original unchanged");
            cachedReader(temp);
        }finally{Files.walk(temp).sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ex){throw new RuntimeException(ex);}});}
        System.out.println("ManualPowerGate: "+checks+" checks PASS");
    }
}
