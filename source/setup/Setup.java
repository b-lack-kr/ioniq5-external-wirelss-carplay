// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp.setup;
import java.io.*;
import java.util.*;
import java.security.SecureRandom;
import hmgapi.engine.Json;
/** Installer helpers. No native code/firmware writes; credentials are never printed. */
public final class Setup {
    static String read(File f)throws IOException {
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile()) || f.length()>2097152)throw new IOException("unsafe input");
        ByteArrayOutputStream b=new ByteArrayOutputStream();InputStream in=new FileInputStream(f);
        try{byte[] a=new byte[8192];int n;while((n=in.read(a))!=-1)b.write(a,0,n);}finally{in.close();}
        return b.toString("UTF-8");
    }
    static void write(File f,String s)throws IOException {
        if(!f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("unsafe output");
        FileOutputStream out=new FileOutputStream(f);try{out.write(s.getBytes("UTF-8"));out.getFD().sync();}finally{out.close();}
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") static List<Object> list(Object o){return (List<Object>)o;}
    static Map<String,Object> json(File f)throws IOException{return Json.object(read(f));}
    static void scan(File d,List<File> found)throws IOException {
        if(!d.getCanonicalFile().equals(d.getAbsoluteFile()))throw new IOException("map symlink");
        File[] fs=d.listFiles();if(fs==null)return;
        for(File f:fs)if(f.isDirectory())scan(f,found);else if(f.getName().equals("car.json")){
            Map<String,Object> j=json(f);
            if(!"260902".equals(j.get("firmware")))continue;
            Object ps=j.get("products");if(!(ps instanceof List))continue;
            for(Object p:list(ps))if("NEEV.KOR.0000.V039.001.260902".startsWith(String.valueOf(p))){found.add(f.getParentFile());break;}
        }
    }
    static File target(File maps)throws IOException {
        List<File> found=new ArrayList<File>();scan(maps,found);
        if(found.size()>1)throw new IOException("multiple NEEV maps");
        return found.isEmpty()?new File(maps,"hyundai/neev/kor/260902"):found.get(0);
    }
    static void merge(File maps,File source)throws IOException {
        File target=target(maps);boolean existing=new File(target,"car.json").isFile();
        Map<String,Object> src=json(new File(source,"car.json"));
        Map<String,Object> car=existing?json(new File(target,"car.json")):new LinkedHashMap<String,Object>(src);
        if(!existing){car.put("apps",new ArrayList<Object>());car.put("files",new LinkedHashMap<String,Object>());car.put("app_files",new LinkedHashMap<String,Object>());car.put("missing",new LinkedHashMap<String,Object>());}
        if(!target.isDirectory()&&!target.mkdirs())throw new IOException("map mkdir");
        List<Object> apps=list(car.get("apps"));Map<String,Object> files=map(car.get("files")),afs=map(car.get("app_files"));
        for(String app:new String[]{"framework","dipo","iap2"}){
            File out=new File(target,app+".json");Map<String,Object> merged=out.isFile()?json(out):new LinkedHashMap<String,Object>();
            Map<String,Object> add=json(new File(source,app+".json"));
            for(Map.Entry<String,Object> entry:add.entrySet()){
                Object prior=merged.get(entry.getKey());
                if(prior!=null&&!prior.equals(entry.getValue()))throw new IOException("map entry conflict: "+entry.getKey());
                merged.put(entry.getKey(),entry.getValue());
            }
            write(out,Json.write(merged));if(!apps.contains(app))apps.add(app);
            Map<String,Object> required=map(map(src.get("app_files")).get(app));
            Map<String,Object> af=afs.containsKey(app)?map(afs.get(app)):new LinkedHashMap<String,Object>(required);
            List<Object> paths=new ArrayList<Object>(list(af.get("files")));
            for(Object f:list(required.get("files"))){if(!paths.contains(f))paths.add(f);
                Object hash=map(src.get("files")).get(f);
                if(files.containsKey(f)&&!hash.equals(files.get(f)))throw new IOException("map file hash conflict");
                files.put(String.valueOf(f),hash);
            }
            af.put("files",paths);afs.put(app,af);
            Object missing=car.get("missing");if(missing instanceof Map)for(String k:add.keySet())map(missing).remove(k);
        }
        car.put("status","probed");car.put("verified",null);write(new File(target,"car.json"),Json.write(car));
        System.out.println("MAP_MERGED");
    }
    static void config(File f)throws Exception {
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random=new SecureRandom();StringBuilder pass=new StringBuilder();for(int i=0;i<24;i++)pass.append(alphabet.charAt(random.nextInt(alphabet.length())));
        File identity = new File(f.getParentFile(), "ap-identity");
        if (!f.getName().equals("hotspot.conf") || !f.getParentFile().getName().equals("config")
                || f.exists() || identity.exists()) throw new IOException("config already exists");
        String ssid = String.format(Locale.US, "Hyundai_%06d", random.nextInt(1000000));
        String text="USB_VID=2357\nUSB_PID=0138\nMODULE_NAME=88x2bu.ko\nMODULE_ARGS=\"rtw_power_mgnt=0 rtw_ips_mode=0 rtw_enusbss=0 rtw_en_napi=0 rtw_en_gro=0 ifname=rtwlan%d if2name=rtwlan%d\"\nWIFI_MODE=ap\nAP_SSID="+ssid+"\nAP_PASS="+pass+"\nAP_HW_MODE=a\nAP_CHANNEL=36\nFORCE_SUBNET=192.168.43.1\nAP_IP=192.168.43.1\nAP_PREFIX=24\nDHCP_START=192.168.43.10\nDHCP_END=192.168.43.100\nENABLE_LTE_NAT=0\nAUTO_UPDATE=0\n";
        write(f,text);
        write(identity, ssid + "\n");
        System.out.println("AP_CONFIG_CREATED_5GHZ");
    }
    static void uid()throws Exception {
        Object pm=Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        java.lang.reflect.Method get=Class.forName("android.content.pm.IPackageManager").getMethod("getApplicationInfo",String.class,int.class,int.class);
        for(String name:new String[]{"com.daudio.app.dipo","com.tcc.iap2server"}){
            Object info=get.invoke(pm,name,0,0);
            if(info==null||info.getClass().getField("uid").getInt(info)!=1000)throw new IOException("projection UID unsupported");
        }
        System.out.println("PROJECTION_UID_OK");
    }
    static String digest(File f)throws Exception {
        java.security.MessageDigest d=java.security.MessageDigest.getInstance("SHA-256");
        InputStream in=new FileInputStream(f);try{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}finally{in.close();}
        StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();
    }
    static void treeWalk(File root,File dir,Map<String,Object> files)throws Exception {
        if(!dir.getCanonicalFile().equals(dir.getAbsoluteFile()))throw new IOException("tree symlink");
        File[] children=dir.listFiles();if(children==null)throw new IOException("tree unreadable");
        for(File f:children){if(!f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("tree symlink");
            if(f.isDirectory())treeWalk(root,f,files);else if(f.isFile())files.put(f.getPath().substring(root.getPath().length()+1),digest(f));
        }
    }
    static Map<String,Object> tree(File root)throws Exception {Map<String,Object> m=new TreeMap<String,Object>();treeWalk(root,root,m);return m;}
    public static void main(String[] a)throws Exception {
        if(a.length==2&&a[0].equals("history")) {
            System.exit(History.collect(new File(a[1]), new File("/data/local/tmp/neev_usbcp/daemon_stdout.log"),
                    new File("/data/nemesis/neev-usbcp-logs/provider.log"), new File("/system/bin/logcat")));
        } else if(a.length==3&&a[0].equals("merge"))merge(new File(a[1]),new File(a[2]));
        else if(a.length==2&&a[0].equals("config"))config(new File(a[1]));
        else if(a.length==1&&a[0].equals("uid"))uid();
        else if(a.length==3&&a[0].equals("tree"))write(new File(a[2]),Json.write(tree(new File(a[1]))));
        else if(a.length==3&&a[0].equals("same-tree")){if(!tree(new File(a[1])).equals(json(new File(a[2]))))throw new IOException("map tree changed");}
        else throw new IllegalArgumentException("setup command");
    }
}

/** USB export. Sources are fixed in the public entry point; each output is reconstructed from a finite vocabulary. */
final class History {
    private static final int EVENT_LIMIT = 256, FILE_WINDOW = 192 * 1024, LOGCAT_LIMIT = 2 * 1024 * 1024;
    private static final String AP_STATES =
            "STARTING WAIT_DONGLE DEVICE_VERIFIED HOSTAPD_STARTING DHCP_STARTING SERVICES_RUNNING WAIT_INTERFACE " +
            "AP_PERMISSION_FAILED CONFIG_MISSING CONFIG_INVALID DNSMASQ_EXITED DRIVER_FAILED HOSTAPD_EXITED " +
            "HOSTAPD_PATCH_FAILED IDENTITY_REJECTED NETWORK_SETUP_FAILED STOP_TIMEOUT VENDOR_ASSETS_INVALID";
    private static final String PHASES =
            "init configuration net_identity usb_identity process_identity hostapd_status hostapd_config " +
            "hostapd_ready_security credentials_match interface_identity stable_processes";
    private static final String CP_CODES =
            "APP_READY APP_UID_MISMATCH AP_NOTIFY_FAILED ATTACH_DEFERRED ATTACH_WAIT_AP_TIMEOUT BT_BOOTSTRAP " +
            "CREDENTIALS_SEND_EXCEPTION CREDENTIALS_SEND_FAILED CREDENTIALS_SENT CREDENTIALS_SESSION_GONE " +
            "CREDENTIALS_WAIT_AP_TIMEOUT IAP_ATTACH IAP_ATTACH_FAILED IAP_RECLAIM LIFECYCLE MEDIA RETRY_EXHAUSTED " +
            "RETRY_FAILED RETRY_SKIPPED STALE_CALLBACK TBT_ADVERTISED TBT_ADVERTISEMENT_FAILED TBT_DISPLAY " +
            "TBT_DISTANCE TBT_LISTENER TBT_MANEUVER_DETAIL TBT_NATIVE_DISTANCE_YIELDED TBT_RECEIVED TBT_REQUEST " +
            "TBT_SOURCE_CHANGED TRANSPORT_INFERRED WRONG_TARGET ZYGOTE_READY";
    private static final java.util.regex.Pattern PROVIDER = java.util.regex.Pattern.compile(
            "^wall_ms=([0-9]{1,15}) elapsed_ms=([0-9]{1,15}) CP_(PROVIDER|OBSERVER) " +
            "(?:version=[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3} )?event=([a-z]+)(?: phase=([a-z_]+))?(?: .*)?$");
    private static final java.util.regex.Pattern PROJECTION = java.util.regex.Pattern.compile(
            "^([0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}) [IWE]/HMG-API *\\( *[0-9]+\\): " +
            "\\[neev-usb-carplay\\] (bridge: )?(.+)$");

    static boolean listed(String options, String token) {
        return token != null && token.indexOf(' ') < 0 && (" " + options + " ").contains(" " + token + " ");
    }
    static String sanitized(String stream, String line) {
        if (line.length() > 4096 || line.indexOf('\r') >= 0) return null;
        if ("ap".equals(stream)) {
            String prefix = "AP_EVENT state=";
            if (!line.startsWith(prefix)) return null;
            String phase = line.substring(prefix.length());
            return listed(AP_STATES, phase) ? phase : null;
        }
        if ("provider".equals(stream)) {
            java.util.regex.Matcher m = PROVIDER.matcher(line);
            if (!m.matches()) return null;
            boolean provider = "PROVIDER".equals(m.group(3));
            String event = m.group(4), phase = m.group(5);
            if (!listed(provider ? "started ready down heartbeat" : "retry unavailable recovered", event)) return null;
            if (phase != null && (provider || !listed(PHASES, phase))) return null;
            return m.group(1) + " " + m.group(2) + (provider ? " provider " : " observer ") +
                    event + (phase == null ? "" : " " + phase);
        }
        java.util.regex.Matcher m = PROJECTION.matcher(line);
        if (!m.matches()) return null;
        String message = m.group(3), token = message.split(" ", 2)[0];
        if (token.startsWith("USB_CP_") && listed(CP_CODES, token.substring(7))) return m.group(1) + " " + token;
        if (m.group(2) == null) return null;
        if (message.startsWith("CarPlay session created (usb=")) return m.group(1) + " SESSION_CREATED";
        if (message.startsWith("CarPlay session started transport=")) return m.group(1) + " SESSION_STARTED";
        if (message.startsWith("CarPlay session finalized type=")) return m.group(1) + " SESSION_FINALIZED";
        return null;
    }
    static final class Events {
        final ArrayDeque<String> lines = new ArrayDeque<String>();
        String state = "OK";
        void append(String event) {
            if (event == null) return;
            if (lines.size() == EVENT_LIMIT) lines.removeFirst();
            lines.addLast(event);
        }
    }
    private static boolean canonical(File file) throws IOException {
        return file.isAbsolute() && file.equals(file.getCanonicalFile());
    }
    private static Events fileEvents(String stream, File file) {
        Events events = new Events();
        try {
            if (!canonical(file)) { events.state = "UNSAFE_PATH"; return events; }
            if (!file.exists()) { events.state = "MISSING"; return events; }
            if (!file.isFile()) { events.state = "NOT_A_FILE"; return events; }
            RandomAccessFile in = new RandomAccessFile(file, "r");
            try {
                long size = in.length();
                int length = (int)Math.min(size, FILE_WINDOW);
                long start = size - length;
                in.seek(start);
                byte[] data = new byte[length];
                in.readFully(data);
                String[] rows = new String(data, "UTF-8").split("\n", -1);
                // Skip an incomplete first record at the window, or a final partial write.
                for (int i = start == 0 ? 0 : 1; i + 1 < rows.length; i++) events.append(sanitized(stream, rows[i]));
            } finally { in.close(); }
        } catch (IOException error) { events.state = "READ_FAILED"; }
        return events;
    }
    private static Events logcat(File executable) throws InterruptedException {
        final Events events = new Events();
        final Process process;
        try {
            if (!canonical(executable) || !executable.isFile()) { events.state = "MISSING"; return events; }
            process = new ProcessBuilder(executable.getPath(), "-d", "-v", "time", "-s", "HMG-API:I", "*:S")
                    .redirectErrorStream(true).start();
        } catch (IOException error) { events.state = "START_FAILED"; return events; }
        Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream input = process.getInputStream();
                    try {
                        byte[] buf = new byte[4096];
                        int total = 0, n;
                        StringBuilder line = new StringBuilder();
                        boolean discard = false;
                        while ((n = input.read(buf)) >= 0) {
                            if (n > LOGCAT_LIMIT - total) { events.state = "READ_LIMIT"; return; }
                            total += n;
                            for (int i = 0; i < n; i++) {
                                int c = buf[i] & 255;
                                if (c == 10) {
                                    if (!discard) events.append(sanitized("carplay", line.toString()));
                                    line.setLength(0); discard = false;
                                } else if (line.length() < 4096) line.append((char)c);
                                else discard = true;
                            }
                        }
                    } finally { input.close(); }
                    if (process.waitFor() != 0) events.state = "DUMP_FAILED";
                } catch (Exception unavailable) { events.state = "READ_FAILED"; }
            }
        }, "neev-history");
        reader.setDaemon(true);
        reader.start();
        try {
            reader.join(4000);
            if (reader.isAlive()) {
                Events timeout = new Events();
                timeout.state = "TIMEOUT";
                return timeout;
            }
            return events;
        } finally { process.destroy(); }
    }
    private static void create(File output, String name, String content) throws IOException {
        File target = new File(output,name);
        if (!canonical(target) || !target.createNewFile()) throw new IOException("history output exists");
        Setup.write(target, content);
    }
    private static String body(Events events) {
        StringBuilder text = new StringBuilder();
        for (String s : events.lines) text.append(s).append('\n');
        return text.toString();
    }
    static int collect(File output, File apLog, File providerLog, File logcat) throws Exception {
        if (!canonical(output) || !output.isDirectory() ||
                !output.getName().matches("NEEV_BUNDLE_RESULT_[0-9]{8}_[0-9]{6}_[0-9]+"))
            throw new IOException("invalid history destination");
        Events ap = fileEvents("ap", apLog);
        Events rotated = fileEvents("provider", new File(providerLog.getPath() + ".1"));
        Events current = fileEvents("provider", providerLog);
        Events cp = logcat(logcat);
        Events provider = new Events();
        for (String line : rotated.lines) provider.append(line);
        for (String line : current.lines) provider.append(line);
        create(output, "ap-events.txt", body(ap));
        create(output, "provider-events.txt", body(provider));
        create(output, "carplay-events.txt", body(cp));
        create(output, "history-status.txt", "ap=" + ap.state + " events=" + ap.lines.size() +
                "\nprovider_rotated=" + rotated.state + " events=" + rotated.lines.size() +
                "\nprovider_current=" + current.state + " events=" + current.lines.size() +
                "\nprovider_exported=" + provider.lines.size() + "\ncarplay=" + cp.state + " events=" + cp.lines.size() + "\n");
        boolean ok = "OK".equals(ap.state) && "OK".equals(current.state) && "OK".equals(cp.state) &&
                ("OK".equals(rotated.state) || "MISSING".equals(rotated.state));
        return ok ? 0 : 7;
    }
}
