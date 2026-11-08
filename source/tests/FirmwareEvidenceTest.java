package kr.ioniq5.usbcp.setup;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

public final class FirmwareEvidenceTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static File put(File root,String path,byte[] bytes)throws Exception {
        File file=new File(root,path.startsWith("/")?path.substring(1):path);
        file.getParentFile().mkdirs();Files.write(file.toPath(),bytes);return file;
    }
    static byte[] bytes(String kind,int size) {
        byte[] b=new byte[size];Arrays.fill(b,(byte)42);
        if(kind.equals("ELF")){
            byte[] head={127,'E','L','F',1,1,1,0};System.arraycopy(head,0,b,0,head.length);b[18]=40;b[19]=0;
        }else if(kind.equals("APK")) {b[0]='P';b[1]='K';b[2]=3;b[3]=4;}
        else {byte[] head={'d','e','y',10,'0','3','6',0};System.arraycopy(head,0,b,0,head.length);}
        return b;
    }
    static File output(File root,int number)throws Exception {
        File f=new File(root,"NEEV_BUNDLE_RESULT_20261105_120000_"+number);check(f.mkdir(),"mkdir");return f;
    }
    static String read(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),"UTF-8");}
    public static void main(String[] args)throws Exception {
        File root=new File(args[0]).getCanonicalFile(),fs=new File(root,"fs");check(fs.mkdir(),"fs mkdir");
        check(FirmwareEvidence.ARTIFACTS.length==8,"fixed artifact cap");
        for(FirmwareEvidence.Artifact a:FirmwareEvidence.ARTIFACTS)put(fs,a.candidates[0],bytes(a.format,256));
        // No unrelated app file or map data is ever traversed/copied.
        put(fs,"/data/data/com.mnsoft.navi/preferences.xml","PRIVATE_PASSWORD".getBytes("UTF-8"));
        put(fs,"/storage/navi/navi/user_history.db","PRIVATE_HISTORY".getBytes("UTF-8"));
        File out=output(root,1);
        check(FirmwareEvidence.collect(out,fs,FirmwareEvidence.PRODUCT,FirmwareEvidence.PLATFORM)==0,"all fixed artifacts");
        check(new File(out,"firmware-code").list().length==8,"only eight code files");
        String manifest=read(new File(out,"firmware-code-status.tsv"));
        check(manifest.contains("total_copied_bytes\t2048"),"actual bytes");
        check(manifest.contains("FIXED_FALLBACK_UNCONFIRMED"),"no false active claim");
        check(!manifest.contains("PRIVATE")&&!manifest.contains("preferences"),"no private metadata");
        for(FirmwareEvidence.Artifact a:FirmwareEvidence.ARTIFACTS){
            File copied=new File(out,"firmware-code/"+a.name);
            check(Arrays.equals(Files.readAllBytes(copied.toPath()),bytes(a.format,256)),"exact bytes");
            check(manifest.contains(Setup.digest(copied)),"recorded SHA256");
        }
        try {FirmwareEvidence.collect(out,fs,FirmwareEvidence.PRODUCT,FirmwareEvidence.PLATFORM);throw new AssertionError("overwrite");}
        catch(java.io.IOException expected){}
        File wrong=output(root,2);
        check(FirmwareEvidence.collect(wrong,fs,"PRIVATE_WRONG_PRODUCT",FirmwareEvidence.PLATFORM)==7,"target guard");
        check(!new File(wrong,"firmware-code").exists(),"wrong target no copy");
        check(!read(new File(wrong,"firmware-code-status.tsv")).contains("PRIVATE"),"do not echo product");

        FirmwareEvidence.Artifact a=FirmwareEvidence.ARTIFACTS[2];
        File destDir=new File(root,"copy");check(destDir.mkdir(),"copy mkdir");
        File original=new File(fs,a.candidates[0].substring(1));
        FirmwareEvidence.Copy over=FirmwareEvidence.copy(fs,new File(destDir,"over"),a,255);
        check(over.state.equals("TOTAL_LIMIT")&&!new File(destDir,"over").exists(),"pre-copy total cap");
        FirmwareEvidence.Artifact small=new FirmwareEvidence.Artifact("small","ELF",128,"",a.candidates[0]);
        check(FirmwareEvidence.copy(fs,new File(destDir,"small"),small,1024).state.equals("SIZE_LIMIT"),"per-file cap");
        Files.write(original.toPath(),"PRIVATE_PASSWORD_BAD_HEADER".getBytes("UTF-8"));
        check(FirmwareEvidence.copy(fs,new File(destDir,"bad"),a,1024).state.equals("FORMAT_MISMATCH"),"ELF validation");
        check(!new File(destDir,"bad.partial").exists(),"failed output removed");
        Files.delete(original.toPath());
        Files.createSymbolicLink(original.toPath(),new File(fs,"data/data/com.mnsoft.navi/preferences.xml").toPath());
        check(FirmwareEvidence.copy(fs,new File(destDir,"link"),a,1024).state.equals("UNSAFE_PATH"),"symlink refusal");
        Files.delete(original.toPath());Files.write(original.toPath(),bytes("ELF",256));

        // Resolve the exact loaded code path without copying raw maps/cmdline.
        String active="/storage/navi/navi/BIN/G45/libExSLNavi.so";
        String jni="/storage/navi/navi/BIN/G45/libExSLAndroidJNI.so";
        put(fs,active,bytes("ELF",300));put(fs,jni,bytes("ELF",301));
        put(fs,"proc/100/cmdline","com.mnsoft.navi\0".getBytes("UTF-8"));
        put(fs,"proc/100/maps",("1000-2000 r-xp 0000 00:00 123 "+active+"\n"+
                "2000-3000 r--p 0000 00:00 123 "+active+"\n"+
                "3000-4000 r-xp 0000 00:00 124 "+jni+"\n"+
                "4000-5000 r-xp 0000 00:00 125 /data/private/SECRET.so\n").getBytes("UTF-8"));
        Map<String,String> selected=FirmwareEvidence.activeNative(fs,100);
        check(selected.size()==2&&active.equals(selected.get(a.name)),"only allowlisted active paths");
        FirmwareEvidence.Copy loaded=FirmwareEvidence.copy(fs,new File(destDir,"active.so"),a,1024,selected.get(a.name));
        check(loaded.state.equals("COPIED")&&loaded.selection.equals("ACTIVE_PROCESS_MAP")&&loaded.bytes==300,"loaded path preferred");
        put(fs,"proc/100/cmdline","com.mnsoft.navi:OTHER\0".getBytes("UTF-8"));
        check(FirmwareEvidence.activeNative(fs,100).isEmpty(),"exact process name");
        put(fs,"proc/100/cmdline","com.mnsoft.navi\0".getBytes("UTF-8"));
        put(fs,"proc/100/maps",("1000-2000 r-xp 0000 00:00 123 "+active+"\n"+
                "2000-3000 r-xp 0000 00:00 123 "+a.candidates[0]+"\n").getBytes("UTF-8"));
        check(!FirmwareEvidence.activeNative(fs,100).containsKey(a.name),"ambiguous loaded versions not guessed");
        put(fs,"proc/100/maps",new byte[1024*1024+1]);
        check(FirmwareEvidence.activeNative(fs,100).isEmpty(),"bounded proc reads");
        System.out.println("FirmwareEvidenceTest PASS: fixed target, exact files, hashes, size budgets, source choice, symlinks, headers, bounded active-path filtering and no overwrite");
    }
}
