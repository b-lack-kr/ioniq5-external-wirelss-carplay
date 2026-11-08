package kr.ioniq5.usbcp;

import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/** Independent root service; hook and UI threads only emit their existing logcat messages. */
public final class DevelopmentLogger {
    // Own only this logcat process. The birth check also removes it after an
    // ungraceful Java-service death; PID reuse cannot adopt the old logger.
    static final String WATCH=
        "parent=$1; birth=$2; program=$3; proc=$4; child=; childbirth=; stopping=0\n"+
        "identity() { stat=$(cat \"$proc/$1/stat\" 2>/dev/null) || return 1; rest=${stat##*) }; "+
        "set -- $rest; [ \"$#\" -ge 20 ] || return 1; state=$1; owner=$2; shift 19; echo \"$state $owner $1\"; }\n"+
        "owned() { [ -n \"$childbirth\" ] || return 1; value=$(identity \"$child\") || return 1; "+
        "set -- $value; [ \"$3\" = \"$childbirth\" ] && [ \"$2\" = \"$$\" ] && [ \"$1\" != Z ] && kill -0 \"$child\" 2>/dev/null; }\n"+
        "cleanup() { trap - 0 HUP INT TERM; if owned; then kill \"$child\" 2>/dev/null; "+
        "rounds=0; while owned && [ \"$rounds\" -lt 2 ]; do sleep 1; rounds=$((rounds+1)); done; "+
        "if owned; then kill -9 \"$child\" 2>/dev/null; fi; wait \"$child\" 2>/dev/null; fi; }\n"+
        "trap 'cleanup' 0; trap 'stopping=1' HUP INT TERM\n"+
        "\"$program\" -v time -s HMG-API:V '*:S' & child=$!\n"+
        "value=$(identity \"$child\") || { sleep 1; value=$(identity \"$child\"); }; set -- $value; "+
        "[ \"$2\" != \"$$\" ] || childbirth=$3\n"+
        "trap 'exit 0' HUP INT TERM; [ \"$stopping\" = 0 ] || exit 0\n"+
        "while owned; do\n"+
        "  value=$(identity \"$parent\") || exit 0; set -- $value\n"+
        "  [ \"$3\" = \"$birth\" ] && [ \"$1\" != Z ] || exit 0\n"+
        "  sleep 1\n"+
        "done\n";
    static ProcessBuilder watcher(File shell,File logcat,File proc,int pid,String birth) {
        return new ProcessBuilder(shell.getPath(),"-c",WATCH,"neev-development-log",Integer.toString(pid),birth,logcat.getPath(),proc.getPath()).redirectErrorStream(true);
    }
    static String birth(File proc,int pid)throws IOException {
        String stat=PowerLatch.readSmall(new File(proc,pid+"/stat"),4096);
        int end=stat.lastIndexOf(") ");if(end<0)throw new IOException("process identity");
        String[] fields=stat.substring(end+2).trim().split("\\s+");
        if(fields.length<20||!fields[19].matches("[0-9]{1,20}"))throw new IOException("process birth");
        return fields[19];
    }
    static void cleanup(Process process) {
        if(process==null)return;
        process.destroy();
        try{process.getInputStream().close();}catch(IOException ignored){}
        try{process.getErrorStream().close();}catch(IOException ignored){}
        try{process.getOutputStream().close();}catch(IOException ignored){}
        try{process.waitFor();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}
    }
    static String marker(long wall,long elapsed) {
        return "USB_CP_DEV_LOG start wall_ms="+Math.max(0,wall)+" elapsed_ms="+Math.max(0,elapsed);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=0||android.os.Process.myUid()!=0||!Provider.PRODUCT.equals(Provider.property("ro.build.product"))||
                !"tcc803x".equals(Provider.property("ro.board.platform")))return;
        final AtomicReference<Process> child=new AtomicReference<Process>();
        final Thread shutdown=new Thread(new Runnable(){public void run(){cleanup(child.getAndSet(null));}},"ioniq5-log-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        DevelopmentLogStore store=null;
        try {
            store=new DevelopmentLogStore(new File("/data/nemesis/neev-usbcp-logs/development"));
            int pid=android.os.Process.myPid();File proc=new File("/proc");
            Process process=watcher(new File("/system/bin/sh"),new File("/system/bin/logcat"),proc,pid,birth(proc,pid)).start();
            child.set(process);
            // Let logcat supply its normal timestamp and this process's real
            // PID. This marker follows any already-buffered startup records.
            android.util.Log.i("HMG-API","[neev-usb-carplay] "+marker(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime()));
            System.out.println("DEVELOPMENT_LOG event=started");
            store.pump(process.getInputStream());
        }finally {
            cleanup(child.getAndSet(null));
            if(store!=null){
                System.out.println("DEVELOPMENT_LOG event=stopped accepted="+store.accepted+" rejected="+store.rejected+" oversized="+store.oversized+" low_space="+store.lowSpace);
                store.close();
            }
            try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException stopping){}
        }
    }
}
