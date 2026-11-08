package kr.ioniq5.usbcp;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.regex.Pattern;

/** Private bounded retention for this mod's own logcat records. Never called by a hook. */
public final class DevelopmentLogStore implements Closeable {
    public static final int FILE_LIMIT=2*1024*1024, ARCHIVES=3, LINE_LIMIT=2048;
    public static final long SPACE_FLOOR=64L*1024*1024;
    interface Space {long available(File directory);}
    private static final Pattern OWN=Pattern.compile("^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3} [VDIWEF]/HMG-API *\\( *[0-9]{1,10}\\): \\[neev-usb-carplay\\] .*$");
    private final File directory,file;
    private final int limit;
    private final Space space;
    private final RandomAccessFile owner;
    private final FileLock lock;
    private long lastSync;
    private int writes;
    public long accepted,rejected,oversized,lowSpace;
    public DevelopmentLogStore(File directory)throws IOException {
        this(directory,FILE_LIMIT,new Space(){public long available(File dir){return dir.getUsableSpace();}});
    }
    DevelopmentLogStore(File directory,int limit,Space space)throws IOException {
        if(limit<LINE_LIMIT||limit>FILE_LIMIT)throw new IOException("log limit");
        this.directory=directory;this.file=new File(directory,"carplay.log");this.limit=limit;this.space=space;
        canonical(directory);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("log directory");
        privateMode(directory,true);
        for(int i=0;i<=ARCHIVES;i++) {
            File candidate=at(i);regular(candidate);
            if(candidate.exists()) {
                if(candidate.length()>limit)throw new IOException("oversize existing log");
                privateMode(candidate,false);
            }
        }
        File lockFile=new File(directory,".logger.lock");regular(lockFile);
        RandomAccessFile opened=new RandomAccessFile(lockFile,"rw");FileLock acquired=null;
        try {
            privateMode(lockFile,false);
            acquired=opened.getChannel().tryLock();
            if(acquired==null)throw new IOException("logger already running");
        }catch(Exception unavailable){opened.close();throw new IOException("logger lock unavailable",unavailable);}
        owner=opened;lock=acquired;
    }
    private File at(int number){return number==0?file:new File(file.getPath()+"."+number);}
    static void canonical(File path)throws IOException {
        if(!path.getAbsoluteFile().equals(path.getCanonicalFile()))throw new IOException("noncanonical log path");
    }
    private static void regular(File path)throws IOException {
        canonical(path);if(path.exists()&&!path.isFile())throw new IOException("nonregular log file");
    }
    private static void privateMode(File path,boolean directory)throws IOException {
        if(!path.setReadable(false,false)||!path.setWritable(false,false)||!path.setExecutable(false,false)||
                !path.setReadable(true,true)||!path.setWritable(true,true)||(directory&&!path.setExecutable(true,true)))
            throw new IOException("private log permissions");
    }
    public boolean append(byte[] line,int length)throws IOException {
        if(length<0||length>line.length||length>=LINE_LIMIT){oversized++;return false;}
        String text=new String(line,0,length,"UTF-8");
        if(!OWN.matcher(text).matches()){rejected++;return false;}
        // Check before rotating or appending; low space preserves existing records.
        canonical(directory);regular(file);
        boolean separator=false;
        if(file.length()>0) {
            RandomAccessFile tail=new RandomAccessFile(file,"r");
            try{tail.seek(tail.length()-1);separator=tail.read()!='\n';}finally{tail.close();}
        }
        if(space.available(directory)<SPACE_FLOOR+length+1L+(separator?1:0)){lowSpace++;return false;}
        for(int i=1;i<=ARCHIVES;i++)regular(at(i));
        if(file.length()+length+1+(separator?1:0)>limit){rotate();separator=false;}
        FileOutputStream out=new FileOutputStream(file,true);
        try {
            privateMode(file,false);
            // Preserve an interrupted final write, but never concatenate the
            // first new record to it after a process restart or power loss.
            if(separator)out.write('\n');out.write(line,0,length);out.write('\n');
            long now=System.nanoTime()/1000000;
            if(++writes>=32||now-lastSync>=5000){out.getFD().sync();lastSync=now;writes=0;}
        }finally{out.close();}
        accepted++;return true;
    }
    private void rotate()throws IOException {
        File oldest=at(ARCHIVES);
        if(oldest.exists()&&!oldest.delete())throw new IOException("log rotation remove");
        for(int i=ARCHIVES-1;i>=0;i--){File from=at(i);if(from.exists()&&!from.renameTo(at(i+1)))throw new IOException("log rotation rename");}
    }
    /** A fixed-size decoder drains overlong records without retaining their contents. */
    public void pump(InputStream input)throws IOException {
        byte[] buffer=new byte[LINE_LIMIT-1],chunk=new byte[4096];int used=0;boolean tooLong=false;int count;
        while((count=input.read(chunk))!=-1)for(int i=0;i<count;i++) {
            byte value=chunk[i];
            if(value=='\n') {
                if(tooLong)oversized++;else append(buffer,used);
                used=0;tooLong=false;
            }else if(!tooLong){if(used==buffer.length)tooLong=true;else buffer[used++]=value;}
        }
        if(tooLong)oversized++;else if(used>0)append(buffer,used);
    }
    @Override public void close()throws IOException {try{lock.release();}finally{owner.close();}}
}
