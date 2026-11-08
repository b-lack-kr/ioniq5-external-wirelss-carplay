package kr.ioniq5.usbcp;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class DevelopmentLoggerTest {
    static int checks;
    static final String PREFIX="11-06 10:11:12.123 I/HMG-API ( 1234): [neev-usb-carplay] ";
    static final class Space implements DevelopmentLogStore.Space {long bytes=100*1024*1024;public long available(File dir){return bytes;}}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static File dir(File root,String name){File f=new File(root,name);f.mkdirs();return f;}
    static byte[] bytes(String text)throws Exception{return text.getBytes("UTF-8");}
    static String read(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),"UTF-8");}
    static void append(DevelopmentLogStore log,String line)throws Exception {byte[] b=bytes(line);check(log.append(b,b.length),"own line accepted");}
    static void rejectOpen(File dir,String message)throws Exception {
        try{new DevelopmentLogStore(dir).close();throw new AssertionError(message);}catch(IOException expected){checks++;}
    }
    static void privateMode(File file,boolean directory)throws Exception {
        Set<PosixFilePermission> mode=Files.getPosixFilePermissions(file.toPath());
        Set<PosixFilePermission> want=EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
        if(directory)want.add(PosixFilePermission.OWNER_EXECUTE);
        check(mode.equals(want),"private mode "+file.getName());
    }
    static String stat(int pid,String birth){StringBuilder s=new StringBuilder(pid+" (test process ) name) S");for(int i=0;i<18;i++)s.append(" 0");return s.append(' ').append(birth).append(" 0\n").toString();}
    static File script(File root,String name,String body)throws Exception {File f=new File(root,name);Files.write(f.toPath(),bytes("#!/bin/sh\n"+body+"\n"));f.setExecutable(true);return f;}
    static String childIdentity(){StringBuilder format=new StringBuilder("%s (logcat) S %s");for(int i=0;i<17;i++)format.append(" 0");return "mkdir -p \"$FAKE_PROC/$$\"\nprintf '"+format+" 333 0\\n' \"$$\" \"$PPID\" > \"$FAKE_PROC/$$/stat\"\n";}
    static ProcessBuilder watcher(File program,File proc,File pid){ProcessBuilder b=DevelopmentLogger.watcher(new File("/bin/sh"),program,proc,42,"111");b.environment().put("FAKE_PROC",proc.getPath());if(pid!=null)b.environment().put("CHILD_PID",pid.getPath());return b;}
    static boolean alive(String pid)throws Exception{return new ProcessBuilder("/bin/kill","-0",pid).redirectErrorStream(true).start().waitFor()==0;}
    static void waitFile(File file)throws Exception {long deadline=System.currentTimeMillis()+3000;while(!file.isFile()&&System.currentTimeMillis()<deadline)Thread.sleep(10);check(file.isFile(),"child launched");}
    public static void main(String[] args)throws Exception {
        File root=new File(args[0]);root.mkdirs();
        check(DevelopmentLogger.marker(1234567890123L,987654321L).equals("USB_CP_DEV_LOG start wall_ms=1234567890123 elapsed_ms=987654321"),"fixed restart marker retains wall and boot-relative clock");
        check(DevelopmentLogStore.FILE_LIMIT==2*1024*1024&&DevelopmentLogStore.ARCHIVES==3,"8 MiB retention envelope");
        File retention=dir(root,"retention");Space space=new Space();
        DevelopmentLogStore store=new DevelopmentLogStore(retention,2048,space);
        String padding=new String(new char[1100]).replace('\0','x');
        for(int i=1;i<=10;i++)append(store,PREFIX+"USB_CP_TEST id="+i+" "+padding);
        for(int i=0;i<4;i++) {
            File f=new File(retention,"carplay.log"+(i==0?"":"."+i));
            check(f.length()<=2048,"per-file cap");check(read(f).contains("id="+(10-i)+" "),"newest retained order");privateMode(f,false);
        }
        check(!new File(retention,"carplay.log.4").exists(),"only three archives");privateMode(retention,true);
        String before=read(new File(retention,"carplay.log"));
        rejectOpen(retention,"duplicate writer acquired lock");store.close();
        store=new DevelopmentLogStore(retention,2048,space);check(read(new File(retention,"carplay.log")).equals(before),"restart does not reset logs");
        append(store,PREFIX+"USB_CP_TEST after_restart");store.close();
        File partial=dir(root,"partial"),partialFile=new File(partial,"carplay.log");
        Files.write(partialFile.toPath(),bytes(PREFIX+"USB_CP_TEST interrupted"));
        store=new DevelopmentLogStore(partial,2048,space);append(store,PREFIX+"USB_CP_TEST after_crash");store.close();
        check(read(partialFile).equals(PREFIX+"USB_CP_TEST interrupted\n"+PREFIX+"USB_CP_TEST after_crash\n"),"restart separates interrupted record without resetting history");

        File filtered=dir(root,"filter");store=new DevelopmentLogStore(filtered,2048,space);
        String foreign="11-06 10:11:12.123 I/HMG-API ( 1234): [other-mod] [neev-usb-carplay] secret";
        String wrongTag="11-06 10:11:12.123 I/DiPO ( 1234): [neev-usb-carplay] CAN";
        String huge=PREFIX+new String(new char[1024*1024]).replace('\0','z');
        store.pump(new ByteArrayInputStream(bytes(foreign+"\n"+wrongTag+"\n"+huge+"\n"+PREFIX+"USB_CP_VIDEO event=window\n")));
        check(store.accepted==1&&store.rejected==2&&store.oversized==1,"ownership and oversize filtering");
        check(!read(new File(filtered,"carplay.log")).contains("secret")&&!read(new File(filtered,"carplay.log")).contains("CAN"),"other logs never retained");
        append(store,PREFIX+DevelopmentLogger.marker(1234567890123L,987654321L));
        check(read(new File(filtered,"carplay.log")).contains("I/HMG-API ( 1234): [neev-usb-carplay] USB_CP_DEV_LOG start"),"marker and original PID retained");
        String max=PREFIX+new String(new char[2047-PREFIX.length()]).replace('\0','m');append(store,max);
        check(new File(filtered,"carplay.log").length()==2048,"2 KiB including newline boundary");
        byte[] oversized=bytes(max+"x");check(!store.append(oversized,oversized.length),"one byte over cap rejected");
        before=read(new File(filtered,"carplay.log"));space.bytes=DevelopmentLogStore.SPACE_FLOOR;
        byte[] line=bytes(PREFIX+"USB_CP_TEST lowspace");check(!store.append(line,line.length)&&store.lowSpace==1,"space floor reserves write size");
        check(read(new File(filtered,"carplay.log")).equals(before),"low space preserves existing history");
        space.bytes=100*1024*1024;append(store,PREFIX+"USB_CP_TEST recovered");store.close();

        File outside=dir(root,"outside"),linked=new File(root,"linked");Files.createSymbolicLink(linked.toPath(),outside.toPath());rejectOpen(linked,"linked dir accepted");
        File archiveDir=dir(root,"archive-link");File target=new File(outside,"private");Files.write(target.toPath(),bytes("unchanged"));
        Files.createSymbolicLink(new File(archiveDir,"carplay.log.1").toPath(),target.toPath());rejectOpen(archiveDir,"linked archive accepted");check(read(target).equals("unchanged"),"foreign target unchanged");
        File over=dir(root,"oversize-file");RandomAccessFile overFile=new RandomAccessFile(new File(over,"carplay.log"),"rw");overFile.setLength(DevelopmentLogStore.FILE_LIMIT+1L);overFile.close();
        rejectOpen(over,"oversize existing log reset");check(new File(over,"carplay.log").length()==DevelopmentLogStore.FILE_LIMIT+1L,"startup never truncates preexisting data");

        File proc=dir(root,"proc"),parent=dir(proc,"42"),parentStat=new File(parent,"stat");Files.write(parentStat.toPath(),bytes(stat(42,"111")));
        check(DevelopmentLogger.birth(proc,42).equals("111"),"proc comm spaces/parentheses parsed correctly");
        File eof=script(root,"logcat-eof",childIdentity()+"printf '%s\\n' '"+PREFIX+"USB_CP_TEST EOF'");
        Process p=watcher(eof,proc,null).start();
        store=new DevelopmentLogStore(dir(root,"eof"),2048,space);store.pump(p.getInputStream());
        check(p.waitFor(4,TimeUnit.SECONDS)&&store.accepted==1,"EOF completes bounded logcat session");DevelopmentLogger.cleanup(p);store.close();
        File childPid=new File(root,"child.pid");
        File sleeper=script(root,"logcat-sleep",childIdentity()+"echo $$ > \"$CHILD_PID\"\nexec /bin/sleep 60");
        ProcessBuilder builder=watcher(sleeper,proc,childPid);
        p=builder.start();waitFile(childPid);String pid=read(childPid).trim();check(alive(pid),"owned logcat running");
        DevelopmentLogger.cleanup(p);check(!alive(pid),"normal cleanup destroys/waits owned child");
        childPid.delete();builder=watcher(sleeper,proc,childPid);
        p=builder.start();waitFile(childPid);pid=read(childPid).trim();Files.write(parentStat.toPath(),bytes(stat(42,"222")));
        check(p.waitFor(4,TimeUnit.SECONDS),"parent birth change exits watcher");check(!alive(pid),"PID reuse cannot orphan logcat");DevelopmentLogger.cleanup(p);
        childPid.delete();Files.write(parentStat.toPath(),bytes(stat(42,"111")));
        builder=watcher(sleeper,proc,childPid);
        p=builder.start();waitFile(childPid);pid=read(childPid).trim();parentStat.delete();
        check(p.waitFor(4,TimeUnit.SECONDS),"missing parent exits watcher");check(!alive(pid),"abrupt parent loss cleans owned child");DevelopmentLogger.cleanup(p);
        childPid.delete();Files.write(parentStat.toPath(),bytes(stat(42,"111")));
        builder=watcher(sleeper,proc,childPid);p=builder.start();waitFile(childPid);pid=read(childPid).trim();Thread.sleep(1200);
        File childStat=new File(proc,pid+"/stat");
        Files.write(childStat.toPath(),bytes(read(childStat).replace(" 333 0\n"," 444 0\n")));
        check(p.waitFor(4,TimeUnit.SECONDS),"changed child birth stops watcher");check(alive(pid),"reused child identity is never signalled");
        new ProcessBuilder("/bin/kill","-KILL",pid).start().waitFor();DevelopmentLogger.cleanup(p);
        childPid.delete();builder=watcher(sleeper,proc,childPid);p=builder.start();waitFile(childPid);pid=read(childPid).trim();Thread.sleep(1200);
        childStat=new File(proc,pid+"/stat");
        Files.write(childStat.toPath(),bytes(read(childStat).replaceFirst(" S [0-9]+ "," S 0 ")));
        check(p.waitFor(4,TimeUnit.SECONDS),"changed child parent stops watcher");check(alive(pid),"nonchild PID is never signalled");
        new ProcessBuilder("/bin/kill","-KILL",pid).start().waitFor();DevelopmentLogger.cleanup(p);
        childPid.delete();File stubborn=script(root,"logcat-stubborn",childIdentity()+"echo $$ > \"$CHILD_PID\"\ntrap '' TERM\nexec /bin/sleep 60");
        builder=watcher(stubborn,proc,childPid);p=builder.start();waitFile(childPid);pid=read(childPid).trim();Thread.sleep(1200);
        long stopStart=System.nanoTime();DevelopmentLogger.cleanup(p);
        check(System.nanoTime()-stopStart<5000000000L&&!alive(pid),"bounded verified TERM to KILL cleanup");
        System.out.println("DevelopmentLoggerTest PASS checks="+checks);
    }
}
