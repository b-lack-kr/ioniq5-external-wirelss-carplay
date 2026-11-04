// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.publiccp;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Import exactly three independently obtained, pinned tcc803x runtime files. */
public final class VendorAssets {
    static final String INNER = "packages/usbwifi.hotspot.zip";
    static final String INNER_HASH = "d9077ba275398c1578444fdea479a5675e21f1b36f9ad3217e57b65aeadc512b";
    static final String[] FILES = {"modules/88x2bu.ko","modules/8851bu.ko","bin/usbwifistatus.jar"};
    static final int[] SIZES = {2749600,5752612,61560};
    static final String[] SUMS = {
        "19b256a2710ea8613ce1018ec214131b30f71d4a075eebaeb25c2d4e29d2f7e7",
        "200806b86759b35c6bbba1b06a5c70393ed5441d2a45397e2c9bb6f77cee1bdf",
        "45025b7359cee448c423a2119da43da99d039bb5e36cfc9db8876947353403fe"
    };
    static final int MAX_ZIP = 12*1024*1024;

    static File strict(File path) throws IOException {
        if (!path.getCanonicalFile().equals(path.getAbsoluteFile())) throw new IOException("UNSAFE_PATH");
        return path;
    }
    static void mkdir(File path) throws IOException {
        strict(path);
        if (!path.isDirectory() && !path.mkdir()) throw new IOException("DIRECTORY_FAILED");
    }
    static String transfer(InputStream in, OutputStream out, long limit, long required) throws Exception {
        byte[] buffer = new byte[32768];
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        int n;
        while ((n=in.read(buffer))!=-1) {
            total += n;
            if (total>limit) throw new IOException("SIZE_MISMATCH");
            digest.update(buffer,0,n);
            if (out!=null) out.write(buffer,0,n);
        }
        if (required>=0 && total!=required) throw new IOException("SIZE_MISMATCH");
        StringBuilder hex=new StringBuilder();
        for (byte b:digest.digest()) {
            hex.append(Character.forDigit((b&255)>>>4,16)).append(Character.forDigit(b&15,16));
        }
        return hex.toString();
    }
    static void asset(InputStream in, File stage, int i) throws Exception {
        FileOutputStream out=null;
        if (stage!=null) {
            File dest=strict(new File(stage,FILES[i]));
            mkdir(dest.getParentFile());
            out=new FileOutputStream(dest);
        }
        try {
            if (!SUMS[i].equals(transfer(in,out,SIZES[i],SIZES[i]))) throw new IOException("ASSET_HASH_MISMATCH");
            if (out!=null) out.getFD().sync();
        } finally { if (out!=null) out.close(); }
    }
    static void directory(File source, File stage) throws Exception {
        for (int i=0;i<FILES.length;i++) {
            File f=strict(new File(source,FILES[i]));
            if (!f.isFile() || f.length()!=SIZES[i]) throw new IOException("ASSET_MISSING_OR_WRONG_SIZE");
            try(InputStream in=new FileInputStream(f)) { asset(in,stage,i); }
        }
    }
    static void fromZip(File file, boolean outer, File stage) throws Exception {
        strict(file);
        if (!file.isFile() || file.length()>MAX_ZIP) throw new IOException("ARCHIVE_MISSING_OR_TOO_LARGE");
        ByteArrayOutputStream memory=new ByteArrayOutputStream();
        String hash;
        if (outer) {
            try (ZipFile z=new ZipFile(file)) {
                ZipEntry child=z.getEntry(INNER);
                if (child==null || child.getSize()>MAX_ZIP) throw new IOException("EXPECTED_HKG_OUTER_ZIP");
                try (InputStream in=z.getInputStream(child)) {hash=transfer(in,memory,MAX_ZIP,-1);}
            }
        } else {
            try(InputStream in=new FileInputStream(file)) {hash=transfer(in,memory,MAX_ZIP,-1);}
        }
        if (!INNER_HASH.equals(hash)) throw new IOException("EXPECTED_HKG_2_0_2_PACKAGE");
        boolean[] found=new boolean[FILES.length];
        try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(memory.toByteArray()))) {
            ZipEntry entry;
            while ((entry=z.getNextEntry())!=null) {
                for (int i=0;i<FILES.length;i++) if (FILES[i].equals(entry.getName())) {
                    if (found[i]) throw new IOException("DUPLICATE_ENTRY");
                    asset(z,stage,i);
                    found[i]=true;
                }
                z.closeEntry();
            }
        }
        for(boolean ok:found) if (!ok) throw new IOException("ASSET_MISSING");
    }
    // Pending is inside the installer's private workspace. Only our three relative names are removed.
    static void clearPending(File dir) throws IOException {
        strict(dir);
        if (!dir.exists()) return;
        if (!dir.isDirectory()) throw new IOException("UNEXPECTED_PENDING_FILE");
        for (String name:FILES) {
            File f=strict(new File(dir,name));
            if (f.exists() && (!f.isFile() || !f.delete())) throw new IOException("PENDING_CLEANUP_FAILED");
        }
        for(String s:new String[]{"modules","bin",""}) {
            File d=s.length()==0 ? dir : strict(new File(dir,s));
            if (d.exists() && (!d.isDirectory() || !d.delete())) throw new IOException("UNEXPECTED_PENDING_CONTENTS");
        }
    }
    static void run(File usb, File workspace, File old, boolean install) throws Exception {
        File parent=strict(new File(workspace,"vendor"));
        File dest=strict(new File(parent,"hkg-2.0.2"));
        if (dest.exists()) {
            directory(dest,null);
            System.out.println("HKG_ASSETS=EXISTING_VERIFIED");
            return;
        }
        boolean legacy=false;
        if (old.isDirectory()) {
            try { directory(old,null); legacy=true; } catch(IOException unavailable) { /* Can import from USB instead. */ }
        }
        File source=null;
        boolean outer=false;
        if (!legacy) {
            source = new File(usb,INNER);
            if (!source.isFile()) {
                source=new File(usb,"HKG_5W_USBWifi_Hotspot.zip"); outer=true;
            }
            fromZip(source,outer,null);
        }
        if (!install) {System.out.println("HKG_ASSETS=SOURCE_VERIFIED"); return;}
        mkdir(parent);
        File pending=strict(new File(parent,".hkg-2.0.2.pending"));
        clearPending(pending);
        if (!pending.mkdir()) throw new IOException("STAGE_FAILED");
        try {
            if (legacy) directory(old,pending); else fromZip(source,outer,pending);
            directory(pending,null);
            if (dest.exists() || !pending.renameTo(dest)) throw new IOException("PUBLISH_FAILED");
        } finally { clearPending(pending); }
        System.out.println(legacy?"HKG_ASSETS=VERIFIED_BETA_IMPORT":"HKG_ASSETS=VERIFIED_USB_IMPORT");
    }
    public static void main(String[] args) {
        try {
            if (args.length!=2 || !("check".equals(args[1]) || "install".equals(args[1])))
                throw new IOException("INVALID_ARGUMENTS");
            run(new File(args[0]).getCanonicalFile(),new File("/data/nemesis/neev-usbcp"),
                new File("/data/hmgapi/mods/neev-usb-carplay/ap"),"install".equals(args[1]));
        } catch(Exception e) {System.err.println("HKG_IMPORT_FAILED "+e.getMessage());System.exit(4);}
    }
}
