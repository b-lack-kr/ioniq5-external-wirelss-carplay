package kr.ioniq5.usbcp;
import java.io.*;
import java.nio.file.*;
public final class ManualApRecoveryTest {
    static final String BOOT="12345678-1234-1234-1234-123456789abc";
    static final ManualApRecovery.Ownership OWNER=new ManualApRecovery.Ownership(){
        public void rootPrivate(File f,boolean directory)throws IOException{
            if(!directory&&!Files.getPosixFilePermissions(f.toPath()).equals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))throw new IOException("permissions");
        }
    };
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        File base=new File(args[0]);base.mkdirs();
        File config=new File(base,"hotspot.conf");Files.write(config.toPath(),new byte[]{1,2,3});
        ManualApRecovery.arm(base,BOOT,1000,OWNER);
        File record=new File(base,ManualApRecovery.NAME);
        check(new String(Files.readAllBytes(record.toPath()),"US-ASCII").equals(BOOT+" 1000\n"));
        check(Files.readAllBytes(config.toPath()).length==3);
        ManualApRecovery.arm(base,BOOT,1001,OWNER);
        check(new String(Files.readAllBytes(record.toPath()),"US-ASCII").equals(BOOT+" 1001\n"));
        try{ManualApRecovery.arm(base,"bad",1002,OWNER);throw new AssertionError();}catch(IOException expected){}
        try{ManualApRecovery.arm(base,BOOT,-1,OWNER);throw new AssertionError();}catch(IOException expected){}
        ManualApRecovery.Ownership denied=new ManualApRecovery.Ownership(){public void rootPrivate(File f,boolean d)throws IOException{throw new IOException("owner");}};
        try{ManualApRecovery.arm(base,BOOT,1002,denied);throw new AssertionError();}catch(IOException expected){}
        check(new String(Files.readAllBytes(record.toPath()),"US-ASCII").equals(BOOT+" 1001\n"));
        record.delete();Files.createSymbolicLink(record.toPath(),config.toPath());
        try{ManualApRecovery.arm(base,BOOT,1002,OWNER);throw new AssertionError();}catch(IOException expected){}
        check(Files.readAllBytes(config.toPath()).length==3);Files.delete(record.toPath());
        File next=new File(base,ManualApRecovery.NAME+".new");Files.createSymbolicLink(next.toPath(),config.toPath());
        try{ManualApRecovery.arm(base,BOOT,1002,OWNER);throw new AssertionError();}catch(IOException expected){}
        check(Files.readAllBytes(config.toPath()).length==3);
        System.out.println("ManualApRecovery: ownership, path, identity and credential-preservation checks passed");
    }
}
