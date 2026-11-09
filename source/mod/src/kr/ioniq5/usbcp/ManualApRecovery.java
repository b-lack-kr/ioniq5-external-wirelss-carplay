package kr.ioniq5.usbcp;

import java.io.*;

/** Root-only request for an AP startup barrier; never changes power decisions or credentials. */
final class ManualApRecovery {
    static final String BASE="/data/nemesis/neev-usbcp", NAME="manual-ap-recovery";
    interface Ownership { void rootPrivate(File path,boolean directory)throws IOException; }
    private ManualApRecovery() {}
    static final class AndroidOwnership implements Ownership {
        public void rootPrivate(File path,boolean directory)throws IOException {
            try {
                Object os=Class.forName("libcore.io.Libcore").getField("os").get(null);
                Object stat=Class.forName("libcore.io.Os").getMethod("lstat",String.class).invoke(os,path.getPath());
                int uid=stat.getClass().getField("st_uid").getInt(stat);
                int mode=stat.getClass().getField("st_mode").getInt(stat);
                if(uid!=0||(mode&0022)!=0||(mode&0170000)!=(directory?0040000:0100000))
                    throw new IOException("unsafe recovery ownership");
                if(!directory&&(mode&0777)!=0600)throw new IOException("unsafe recovery permissions");
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException("recovery ownership unavailable");}
        }
    }
    static void arm()throws IOException {
        if(android.os.Process.myUid()!=0)throw new IOException("root recovery required");
        arm(new File(BASE),PowerLatch.bootId(),android.os.SystemClock.elapsedRealtime()/1000,new AndroidOwnership());
    }
    private static void path(File file,boolean directory,boolean required)throws IOException {
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile())||
                (required&&!file.exists())||(file.exists()&&(directory?!file.isDirectory():!file.isFile())))
            throw new IOException("unsafe recovery path");
    }
    private static void privateMode(File f)throws IOException {
        if(!f.setReadable(false,false)||!f.setWritable(false,false)||!f.setExecutable(false,false)
            ||!f.setReadable(true,true)||!f.setWritable(true,true))throw new IOException("recovery permissions");
    }
    static void arm(File base,String boot,long seconds,Ownership owner)throws IOException {
        if(!PowerLatch.validBoot(boot)||seconds<0||seconds>999999999L)throw new IOException("invalid recovery identity");
        path(base,true,true);owner.rootPrivate(base,true);
        File file=new File(base,NAME),next=new File(base,NAME+".new");
        path(file,false,false);path(next,false,false);
        if(file.exists())owner.rootPrivate(file,false);
        if(next.exists())owner.rootPrivate(next,false);
        else if(!next.createNewFile())throw new IOException("recovery staging race");
        privateMode(next);owner.rootPrivate(next,false);
        FileOutputStream out=new FileOutputStream(next);
        try{out.write((boot+" "+seconds+"\n").getBytes("US-ASCII"));out.getFD().sync();}
        finally{out.close();}
        path(base,true,true);path(file,false,false);path(next,false,true);
        owner.rootPrivate(base,true);owner.rootPrivate(next,false);
        if(!next.renameTo(file))throw new IOException("recovery commit");
    }
}
