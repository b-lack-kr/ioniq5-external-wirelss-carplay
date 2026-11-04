package kr.ioniq5.usbcp;
import java.io.*;
import java.util.*;

/** Small asynchronous decision trace, separate from general logcat. Never pass credentials or exceptions. */
public final class DecisionLog {
    static final int LIMIT=128*1024;
    private final File file;
    private final ArrayDeque<String> queue=new ArrayDeque<String>();
    private long dropped;
    public DecisionLog(File file){this.file=file;Thread t=new Thread(new Runnable(){public void run(){drain();}},"ioniq5-aa-decisions");t.setDaemon(true);t.start();}
    public synchronized void event(String detail){
        // All callers supply fixed labels and numeric counters only.
        if(detail==null || detail.length()>300 || !detail.matches("[a-zA-Z0-9_= .:-]+"))return;
        if(queue.size()>=64){dropped++;return;}
        queue.add("wall_ms="+System.currentTimeMillis()+" elapsed_ms="+android.os.SystemClock.elapsedRealtime()+" "+detail);
        notifyAll();
    }
    private void drain(){while(true){String line;
        synchronized(this){while(queue.isEmpty())try{wait();}catch(InterruptedException e){return;}
            line=queue.remove();if(dropped>0){line+=" dropped="+dropped;dropped=0;}}
        try{append(file,line);}catch(Exception ignored){/* Diagnostics must never disable AA. */}
    }}
    static void append(File f,String line)throws IOException {
        File dir=f.getParentFile();if(!dir.getCanonicalFile().equals(dir.getAbsoluteFile()))throw new IOException("log directory");
        if(!dir.isDirectory()&&!dir.mkdirs())return;
        if(dir.getUsableSpace()<64L*1024*1024)return;
        File old=new File(f.getPath()+".1");
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile())||!old.getCanonicalFile().equals(old.getAbsoluteFile()))throw new IOException("log path");
        byte[] bytes=(line+"\n").getBytes("UTF-8");
        if(f.length()+bytes.length>LIMIT){if(old.exists()&&!old.delete())return;if(f.exists()&&!f.renameTo(old))return;}
        FileOutputStream out=new FileOutputStream(f,true);
        try{out.write(bytes);out.getFD().sync();}finally{out.close();}
    }
}
