// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import java.io.*;
import java.security.SecureRandom;
import java.util.*;

/** Root-only local AP SSID migration. Never evaluates shell config or emits its values. */
public final class ApIdentity {
    private static final int MAX_CONFIG_BYTES = 8192; // Same limit as LiveAp.observe().
    private static final String[] KEYS = {"USB_VID", "USB_PID", "MODULE_NAME", "MODULE_ARGS", "WIFI_MODE", "AP_SSID", "AP_PASS", "AP_HW_MODE", "AP_CHANNEL", "FORCE_SUBNET", "AP_IP", "AP_PREFIX", "DHCP_START", "DHCP_END", "ENABLE_LTE_NAT", "AUTO_UPDATE"};
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(KEYS));

    private static IOException fail(String code) { return new IOException(code); }
    private static boolean entryExists(File f) throws IOException {
        String[] names = f.getParentFile().list();
        if (names == null) throw fail("DIRECTORY_UNREADABLE");
        for (String name : names) if (name.equals(f.getName())) return true;
        return false;
    }
    private static void safeDirectory(File f) throws IOException {
        if (!f.isDirectory() || !f.getCanonicalFile().equals(f.getAbsoluteFile())) throw fail("DIRECTORY_UNSAFE");
    }
    private static void safeFile(File f, boolean required) throws IOException {
        safeDirectory(f.getParentFile());
        if (!f.getCanonicalFile().equals(f.getAbsoluteFile())) throw fail("FILE_UNSAFE");
        if (entryExists(f)) {
            if (!f.isFile()) throw fail("FILE_UNSAFE");
        } else if (required) throw fail("FILE_MISSING");
    }
    private static String read(File f) throws IOException {
        safeFile(f, true);
        if (f.length() > MAX_CONFIG_BYTES) throw fail("FILE_TOO_LARGE");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[1024]; int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_CONFIG_BYTES) throw fail("FILE_TOO_LARGE");
                out.write(buf, 0, n);
            }
        } finally { in.close(); }
        byte[] data = out.toByteArray();
        // The locally generated shell config is ASCII. Reject NUL/CR and hidden
        // encoding changes instead of normalizing any password/config bytes.
        for (byte b : data) if ((b & 255) > 126 || ((b & 255) < 32 && b != '\n' && b != '\t')) throw fail("CONFIG_ENCODING");
        return new String(data, "US-ASCII");
    }
    private static String literal(String value) throws IOException {
        if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
            char quote = value.charAt(0);
            if (value.charAt(value.length() - 1) != quote) throw fail("CONFIG_LITERAL");
            String result = value.substring(1, value.length() - 1);
            if (result.indexOf(quote) >= 0) throw fail("CONFIG_LITERAL");
            if (quote == '"' && (result.indexOf('$') >= 0 || result.indexOf('`') >= 0 || result.indexOf('\\') >= 0)) throw fail("CONFIG_LITERAL");
            return result;
        }
        if (!value.matches("[A-Za-z0-9_.%+:,@/=-]+")) throw fail("CONFIG_LITERAL");
        return value;
    }
    static final class Config {
        final String text, ssid;
        final int start, end;
        Config(String text, String ssid, int start, int end) { this.text=text; this.ssid=ssid; this.start=start; this.end=end; }
        String renamed(String name) { return text.substring(0,start) + "AP_SSID=" + name + text.substring(end); }
    }
    private static boolean legacyName(String name) {
        return "IONIQ5_USB_CP".equals(name) || "IONIQ_USB_CP".equals(name);
    }
    private static Config parse(String text) throws IOException {
        Map<String,String> values = new HashMap<String,String>();
        int offset=0, ssidStart=-1, ssidEnd=-1;
        for (String line : text.split("\n", -1)) {
            if (line.length() == 0 || line.startsWith("#")) { offset += line.length()+1; continue; }
            int eq=line.indexOf('=');
            if (eq < 1) throw fail("CONFIG_LINE");
            String key=line.substring(0,eq);
            if (!ALLOWED.contains(key)) throw fail("CONFIG_KEY");
            if (values.containsKey(key)) throw fail("CONFIG_DUPLICATE");
            String raw = line.substring(eq + 1);
            String value = literal(raw);
            // The AP shell interprets quotes but the provider reads data records.
            // Keep both readers in agreement before migrating a working installation.
            if (!key.equals("MODULE_ARGS") && !raw.equals(value)) throw fail("CONFIG_LITERAL");
            values.put(key, value);
            if (key.equals("AP_SSID")) { ssidStart=offset; ssidEnd=offset+line.length(); }
            offset += line.length()+1;
        }
        if (values.size() != KEYS.length) throw fail("CONFIG_INCOMPLETE");
        if (!values.get("USB_VID").matches("[0-9a-fA-F]{4}") || !values.get("USB_PID").matches("[0-9a-fA-F]{4}")) throw fail("CONFIG_USB");
        if (!values.get("MODULE_NAME").matches("(?:88x2bu|8851bu)\\.ko") || !values.get("MODULE_ARGS").matches("[A-Za-z_][A-Za-z0-9_]*=[A-Za-z0-9_%.-]+(?: +[A-Za-z_][A-Za-z0-9_]*=[A-Za-z0-9_%.-]+)*")) throw fail("CONFIG_MODULE");
        String[][] fixed={{"WIFI_MODE","ap"},{"AP_HW_MODE","a"},{"AP_CHANNEL","36"},{"FORCE_SUBNET","192.168.43.1"},{"AP_IP","192.168.43.1"},{"AP_PREFIX","24"},{"DHCP_START","192.168.43.10"},{"DHCP_END","192.168.43.100"},{"ENABLE_LTE_NAT","0"},{"AUTO_UPDATE","0"}};
        for (String[] pair : fixed) if (!pair[1].equals(values.get(pair[0]))) throw fail("CONFIG_UNSUPPORTED");
        String pass=values.get("AP_PASS");
        if (pass.length()<8 || pass.length()>63) throw fail("CONFIG_PASSWORD");
        for (int i=0;i<pass.length();i++) if (pass.charAt(i)<32 || pass.charAt(i)>126) throw fail("CONFIG_PASSWORD");
        String ssid=values.get("AP_SSID");
        if (!legacyName(ssid) && !ssid.matches("Hyundai_[0-9]{6}")) throw fail("CONFIG_SSID");
        return new Config(text,ssid,ssidStart,ssidEnd);
    }
    private static String identity(File f) throws IOException {
        safeFile(f,false);
        if (!entryExists(f)) return null;
        String text=read(f);
        if (!text.matches("Hyundai_[0-9]{6}\\n")) throw fail("IDENTITY_INVALID");
        return text.substring(0,text.length()-1);
    }
    private static Config checked(File config, File identity) throws IOException {
        if (!config.getName().equals("hotspot.conf") || !identity.getName().equals("ap-identity") || !config.getParentFile().equals(identity.getParentFile()) || !config.getParentFile().getName().equals("config")) throw fail("ARGUMENT_PATH");
        File base=config.getParentFile().getParentFile();
        safeDirectory(base);
        File external=new File(base,"use-existing-ap");
        if (entryExists(external)) throw fail("EXTERNAL_AP");
        Config parsed=parse(read(config));
        String stored=identity(identity);
        if (stored!=null && !legacyName(parsed.ssid) && !parsed.ssid.equals(stored)) throw fail("IDENTITY_MISMATCH");
        return parsed;
    }
    static void preflight(File config, File identity) throws IOException { checked(config,identity); }
    private static void privateMode(File f) throws IOException {
        if (!f.setReadable(false,false) || !f.setWritable(false,false) || !f.setExecutable(false,false) || !f.setReadable(true,true) || !f.setWritable(true,true)) throw fail("PRIVATE_MODE");
    }
    private static void atomic(File target, String text) throws IOException {
        safeFile(target,false);
        File temporary=File.createTempFile(".ap-identity-", ".tmp", target.getParentFile());
        try {
            privateMode(temporary);
            FileOutputStream out=new FileOutputStream(temporary);
            try { out.write(text.getBytes("US-ASCII")); out.getFD().sync(); } finally { out.close(); }
            safeFile(target,false);
            if (!temporary.renameTo(target)) throw fail("ATOMIC_RENAME");
            if (!text.equals(read(target))) throw fail("WRITE_VERIFY");
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    static void apply(File config, File identity) throws IOException {
        Config before=checked(config,identity);
        String stored=identity(identity);
        String chosen=stored;
        if (chosen==null) {
            if (before.ssid.matches("Hyundai_[0-9]{6}")) chosen=before.ssid;
            else {
                int value = new SecureRandom().nextInt(1_000_000);
                chosen = String.format(Locale.US, "Hyundai_%06d", value);
            }
            // The updater snapshots both files before this operation. Publishing
            // identity first also makes a retry repair the old generic name.
            atomic(identity,chosen+"\n");
        }
        if (!before.text.equals(read(config))) throw fail("CONFIG_CHANGED");
        if (!chosen.equals(identity(identity))) throw fail("IDENTITY_CHANGED");
        if (!before.ssid.equals(chosen)) atomic(config,before.renamed(chosen));
        else privateMode(config);
        privateMode(identity);
        Config after=checked(config,identity);
        if (!after.ssid.equals(chosen) || !after.text.equals(before.ssid.equals(chosen)?before.text:before.renamed(chosen))) throw fail("MIGRATION_VERIFY");
    }
    private static void requireRoot() throws IOException {
        BufferedReader in=new BufferedReader(new FileReader("/proc/self/status"));
        try {
            String line;
            while ((line=in.readLine())!=null) if (line.startsWith("Uid:")) {
                String[] ids=line.substring(4).trim().split("\\s+");
                if (ids.length==4 && ids[0].equals("0") && ids[1].equals("0")) return;
                throw fail("ROOT_REQUIRED");
            }
        } finally { in.close(); }
        throw fail("ROOT_REQUIRED");
    }
    public static void main(String[] args) {
        try {
            if (args.length!=3 || !(args[0].equals("preflight") || args[0].equals("apply"))) throw fail("ARGUMENTS");
            if (!args[1].equals("/data/nemesis/neev-usbcp/config/hotspot.conf") || !args[2].equals("/data/nemesis/neev-usbcp/config/ap-identity")) throw fail("ARGUMENT_PATH");
            requireRoot();
            if (args[0].equals("preflight")) preflight(new File(args[1]),new File(args[2]));
            else apply(new File(args[1]),new File(args[2]));
            System.out.println("AP_IDENTITY_OK");
        } catch (Throwable error) {
            String code=error instanceof IOException?error.getMessage():"INTERNAL";
            if (code==null || !code.matches("[A-Z_]{1,32}")) code="IO_FAILURE";
            System.err.println("AP_IDENTITY_ERROR="+code);
            System.exit(1);
        }
    }
}
