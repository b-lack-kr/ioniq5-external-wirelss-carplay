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
        for(String app:new String[]{"framework","dipo","iap2","btsettings","phoneprojection","gpm","welcome"}){
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
        for(String name:new String[]{"com.daudio.app.dipo","com.tcc.iap2server","com.mobis.welcome"}){
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
            int result=History.collect(new File(a[1]), new File("/data/local/tmp/neev_usbcp/daemon_stdout.log"),
                    new File("/data/nemesis/neev-usbcp-logs/provider.log"), new File("/system/bin/logcat"));
            History.boot(new File(a[1]), new File("/data/hmgapi/logs/neev-usb-carplay/usb-ap.log"),
                    new File("/data/hmgapi/state/services.status"), new File("/proc/modules"));
            int persistent=History.persistent(new File(a[1]),new File("/data/hmgapi/logs/hmgapi/log.log"));
            int development=History.development(new File(a[1]),new File("/data/nemesis/neev-usbcp-logs/development"));
            System.exit(result==0 && persistent==0 && development==0 ? 0 : 7);
        } else if(a.length==2&&a[0].equals("firmware")) {
            System.exit(FirmwareEvidence.collect(new File(a[1]),new File("/"),
                    FirmwareEvidence.property("ro.build.product"),FirmwareEvidence.property("ro.board.platform")));
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
            "STARTING DRIVER_PRELOAD WAIT_ANDROID WAIT_DONGLE DEVICE_VERIFIED HOSTAPD_STARTING DHCP_STARTING SERVICES_RUNNING WAIT_INTERFACE " +
            "AP_PERMISSION_FAILED CONFIG_MISSING CONFIG_INVALID DNSMASQ_EXITED DRIVER_FAILED HOSTAPD_EXITED " +
            "HOSTAPD_PATCH_FAILED IDENTITY_REJECTED NETWORK_SETUP_FAILED STOP_TIMEOUT VENDOR_ASSETS_INVALID HU_POWER_OFF " +
            "EXTERNAL_DONGLE_OFF EXTERNAL_CONFIG_INVALID WAIT_POWER_READER";
    private static final String PHASES =
            "init configuration net_identity usb_identity process_identity hostapd_status hostapd_config " +
            "hostapd_ready_security credentials_match interface_identity stable_processes";
    private static final String CP_CODES =
            "APP_READY APP_UID_MISMATCH AP_NOTIFY_FAILED ATTACH_DEFERRED ATTACH_WAIT_AP_TIMEOUT BT_BOOTSTRAP " +
            "CREDENTIALS_SEND_EXCEPTION CREDENTIALS_SEND_FAILED CREDENTIALS_SENT CREDENTIALS_SESSION_GONE " +
            "CREDENTIALS_WAIT_AP_TIMEOUT IAP_ATTACH IAP_ATTACH_FAILED IAP_RECLAIM LIFECYCLE MEDIA RETRY_EXHAUSTED " +
            "RETRY_FAILED RETRY_SKIPPED STALE_CALLBACK TBT_ADVERTISED TBT_ADVERTISEMENT_FAILED TBT_DISPLAY " +
            "TBT_DISTANCE TBT_LISTENER TBT_MANEUVER_DETAIL TBT_NATIVE_DISTANCE_YIELDED TBT_NATIVE_LANES_YIELDED TBT_NATIVE_TURN_LIST_YIELDED TBT_NATIVE_ADVANCED_YIELDED TBT_RECEIVED TBT_REQUEST " +
            "TBT_SOURCE_CHANGED TBT_IPC_PUBLISH_RECOVERED TBT_IPC_PUBLISH_RETRY TBT_IPC_READ_RECOVERED TBT_IPC_READ_RETRY TRANSPORT_INFERRED WRONG_TARGET ZYGOTE_READY";
    private static final java.util.regex.Pattern PROVIDER = java.util.regex.Pattern.compile(
            "^wall_ms=([0-9]{1,15}) elapsed_ms=([0-9]{1,15}) CP_(PROVIDER|OBSERVER) " +
            "(?:version=[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3} )?event=([a-z]+)(?: phase=([a-z_]+))?(?: (.*))?$");
    private static final java.util.regex.Pattern PROVIDER_POWER = java.util.regex.Pattern.compile(
            "^wall_ms=([0-9]{1,15}) elapsed_ms=([0-9]{1,15}) CP_POWER " +
            "event=(restored|accepted|duplicate|rejected|failed) state=(ON|OFF|UNKNOWN)(?: source=(DIPO|WELCOME))?(?: dropped=[0-9]{1,19})?$");
    private static final java.util.regex.Pattern PROJECTION = java.util.regex.Pattern.compile(
            "^([0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}) [IWE]/HMG-API *\\( *[0-9]+\\): " +
            "\\[neev-usb-carplay\\] (bridge: )?(.+)$");
    private static final java.util.regex.Pattern DEVELOPMENT_PID = java.util.regex.Pattern.compile(
            "^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3} [IWE]/HMG-API *\\( *([0-9]{1,10})\\): .+$");

    static boolean listed(String options, String token) {
        return token != null && token.indexOf(' ') < 0 && (" " + options + " ").contains(" " + token + " ");
    }
    private static final String LOCATION_FIELDS =
            "generation aggregate_ms no_sensor_values window_ms speed_in speed_in_max_gap_ms " +
            "gga_in gga_in_max_gap_ms rmc_in rmc_in_max_gap_ms gga_out gga_out_max_gap_ms " +
            "rmc_out rmc_out_max_gap_ms pascd_out pascd_out_max_gap_ms send_ok send_false send_error " +
            "speed_callback_age_ms gga_callback_age_ms rmc_callback_age_ms max_speed_age_at_pascd_ms " +
            "max_gga_age_at_send_ms max_rmc_age_at_send_ms max_binder_ms numeric_speed_only no_coordinates " +
            "micom_kmh micom_observed_age_ms rmc_kmh_milli rmc_fix_valid rmc_speed_age_ms rmc_speed_invalid";
    private static final String PULSE_FIELDS="window_ms change_callbacks invoke_ok invoke_error format_invalid " +
            "gate_allow gate_disabled gate_usb gate_not_wireless gate_inactive gate_not_requested gate_unreadable";
    private static final String AUDIO_FIELDS =
            "generation transport aggregate_ms detail_limit_per_session window_ms native_start native_stop " +
            "stock_start stock_stop duck unduck micom_start micom_end micom_music micom_status volume_key " +
            "failure detail_suppressed last_native_stream last_native_type last_micom_stream";
    private static final String VIDEO_KINDS = "screen_started screen_stopped screen_timeout decode_failure mode_changed " +
            "show_display hide_display activity_resume activity_pause surface_created surface_changed surface_destroyed iap_control";
    private static final String VIDEO_STATE_FIELDS = "surface_null surface_valid width height format";
    private static String videoRecord(String[] words) {
        String event=words[1], numbers, kind=null;
        if ("observers_ready".equals(event)) numbers="aggregate_ms detail_limit_per_window no_frame_counter control_is_video inflight_limit main_probe_pending_limit";
        else if ("observers_unavailable".equals(event)) numbers="";
        else if ("recovery_ready".equals(event)) numbers="cooldown_ms periodic native_serial";
        else if ("recovery_unavailable".equals(event)) numbers="";
        else if ("rebind_ready".equals(event)) numbers="cooldown_ms grace_ms stop_window_ms periodic";
        else if ("rebind_request".equals(event)) numbers="generation elapsed_ms epoch";
        else if ("rebind_result".equals(event)) numbers="generation elapsed_ms duration_ms hide_result show_result exception current";
        else if ("recovery_request".equals(event)) numbers="generation elapsed_ms reason code";
        else if ("recovery_result".equals(event)) numbers="generation elapsed_ms duration_ms native_result exception current";
        else if (listed("session_started session_finalized",event)) numbers="generation transport elapsed_ms";
        else if ("event".equals(event)) {
            numbers="generation elapsed_ms exception code "+VIDEO_STATE_FIELDS+
                    " screen_owner audio_owner phone_owner tbt_owner speech_owner speech_mode";
            for(int i=2;i<words.length;i++) if(words[i].startsWith("kind=")) {
                String value=words[i].substring(5);
                if(kind!=null || !listed(VIDEO_KINDS,value)) return null;
                kind=value;
            }
            if(kind==null)return null;
        } else if ("window".equals(event)) numbers="generation elapsed_ms window_ms "+VIDEO_KINDS+
                " failure detail_suppressed last_screen_owner last_audio_owner last_phone_owner last_tbt_owner"+
                " last_speech_owner last_speech_mode resumed show_result last_decode "+VIDEO_STATE_FIELDS+
                " surface_valid_now last_event_age_ms decode_age_ms screen_event_age_ms control_age_ms control_bytes"+
                " hook_path_max_ms hook_path_kind inflight_tracked inflight_oldest_ms inflight_kind_mask inflight_overflow"+
                " worker_late_ms main_pending_age_ms main_ack_age_ms main_lag_max_ms main_post_failed";
        else return null;
        StringBuilder out=new StringBuilder("USB_CP_VIDEO ").append(event);
        if(kind!=null)out.append(" kind=").append(kind);
        return fields(out,words,2,words.length,numbers,"","","","") ? out.toString() : null;
    }

    /** Parse and reconstruct integers. Never copy an opaque value, including NaN/Infinity. */
    private static String integer(String value, boolean wide) {
        if (value == null || !value.matches("-?[0-9]{1,19}")) return null;
        try {
            long n = Long.parseLong(value);
            if (!wide && (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)) return null;
            return Long.toString(n);
        } catch (NumberFormatException invalid) { return null; }
    }
    private static String ranged(String value,long min,long max) {
        String safe=integer(value,true);if(safe==null)return null;
        long n=Long.parseLong(safe);return n<min || n>max ? null : safe;
    }
    private static String numericList(String value,int limit,long min,long max) {
        String[] items=value.split(",",-1);if(items.length==0 || items.length>limit)return null;
        StringBuilder out=new StringBuilder();
        for(String item:items) {
            String safe=ranged(item,min,max);if(safe==null)return null;
            if(out.length()>0)out.append(',');out.append(safe);
        }
        return out.toString();
    }
    /** New diagnostic fields have bounds from their producer, not arbitrary numeric payloads. */
    private static String diagnosticNumber(String key,String value) {
        if("micom_kmh".equals(key))return ranged(value,-1,510);
        if("rmc_kmh_milli".equals(key))return ranged(value,-1,511000);
        if("rmc_fix_valid".equals(key) || "surface_valid_now".equals(key))return ranged(value,-1,1);
        if(listed("numeric_speed_only no_coordinates control_is_video",key))return ranged(value,0,1);
        if("hook_path_kind".equals(key))return ranged(value,-1,12);
        if("inflight_kind_mask".equals(key))return ranged(value,0,8191);
        if(listed("inflight_tracked inflight_limit",key))return ranged(value,0,32);
        if("main_probe_pending_limit".equals(key))return ranged(value,0,1);
        if("rmc_speed_invalid".equals(key))return ranged(value,0,Integer.MAX_VALUE);
        if(listed("micom_observed_age_ms rmc_speed_age_ms last_event_age_ms decode_age_ms screen_event_age_ms " +
                "control_age_ms inflight_oldest_ms main_pending_age_ms main_ack_age_ms",key))return ranged(value,-1,Long.MAX_VALUE);
        if(listed("control_bytes hook_path_max_ms inflight_overflow worker_late_ms main_lag_max_ms main_post_failed",key))
            return ranged(value,0,Long.MAX_VALUE);
        return integer(value,true);
    }
    private static String tbtDiagnostic(String[] words) {
        if(words.length<3 || !words[1].startsWith("kind="))return null;
        String kind=words[1].substring(5),keys;
        if("route".equals(kind))keys="component maneuver fields clear raw text textlen unit lane records state phase phasePresent dropped";
        else if("maneuver".equals(kind))keys="component maneuver type junction exit side lane distance unit angles dropped";
        else if("lane_input".equals(kind))keys="component index source exact count indexes states highlights angleCounts angles frame dropped";
        else if("lane_select".equals(kind))keys="component maneuver index source exact laneAge templatePolicy guide arrow frame dropped";
        else if("distance_wait".equals(kind))keys="component maneuver fields clear raw text unit state dropped";
        else if("lane_write".equals(kind) || "pattern_write".equals(kind))keys="returned frame dropped";
        else return null;
        StringBuilder out=new StringBuilder("USB_CP_TBT_DIAG kind=").append(kind);
        Set<String> seen=new HashSet<String>();
        for(int i=2;i<words.length;i++) {
            int eq=words[i].indexOf('=');if(eq<1)return null;
            String key=words[i].substring(0,eq),value=words[i].substring(eq+1),safe;
            if(!listed(keys,key) || !seen.add(key))return null;
            if("angles".equals(key))safe=numericList(value,128,Integer.MIN_VALUE,Integer.MAX_VALUE);
            else if("indexes".equals(key))safe=numericList(value,8,-1,7);
            else if("states".equals(key))safe=numericList(value,8,-1,2);
            else if("highlights".equals(key))safe=numericList(value,8,Integer.MIN_VALUE,Integer.MAX_VALUE);
            else if("angleCounts".equals(key))safe="-1".equals(value)?value:numericList(value,8,0,16);
            else if("frame".equals(key))safe="pattern_write".equals(kind)
                    ? numericList(value,5,Integer.MIN_VALUE,Integer.MAX_VALUE) : numericList(value,81,0,255);
            else if("laneAge".equals(key))safe=ranged(value,-1,Long.MAX_VALUE);
            else if("fields".equals(key))safe=ranged(value,0,2097151);
            else if("clear".equals(key))safe=ranged(value,0,7);
            else if("textlen".equals(key))safe=ranged(value,0,256);
            else if("source".equals(key))safe=ranged(value,-1,1);
            else if("count".equals(key))safe=ranged(value,0,8);
            else if("dropped".equals(key))safe=ranged(value,0,Integer.MAX_VALUE);
            else if(listed("text phasePresent exact returned",key))safe=ranged(value,0,1);
            else if("templatePolicy".equals(key))safe=ranged(value,0,0);
            else safe=integer(value,false);
            if(safe==null)return null;
            out.append(' ').append(key).append('=').append(safe);
        }
        return out.toString();
    }
    private static String pulseRecord(String[] words) {
        if(words.length<3)return null;
        StringBuilder out=new StringBuilder("USB_CP_LOCATION pulse_window");Set<String> seen=new HashSet<String>();
        for(int i=2;i<words.length;i++) {
            int eq=words[i].indexOf('=');if(eq<1)return null;
            String key=words[i].substring(0,eq),value=words[i].substring(eq+1);
            if(!listed(PULSE_FIELDS,key) || !seen.add(key))return null;
            String safe=ranged(value,0,"window_ms".equals(key)?Long.MAX_VALUE:Integer.MAX_VALUE);
            if(safe==null)return null;out.append(' ').append(key).append('=').append(safe);
        }
        return out.toString();
    }
    private static String rmcRecord(String[] words) {
        if(words.length<3)return null;
        String keys="window_ms seen replaced unchanged skip_gate skip_source skip_invalid skip_no_fix original_kmh_milli output_kmh_milli micom_kmh micom_age_ms";
        StringBuilder out=new StringBuilder("USB_CP_LOCATION rmc_window");Set<String> seen=new HashSet<String>();
        for(int i=2;i<words.length;i++) {
            int eq=words[i].indexOf('=');if(eq<1)return null;
            String key=words[i].substring(0,eq),value=words[i].substring(eq+1),safe;
            if(!listed(keys,key) || !seen.add(key))return null;
            if(listed("original_kmh_milli output_kmh_milli",key))safe=ranged(value,-1,511000);
            else if("micom_kmh".equals(key))safe=ranged(value,-1,510);
            else if("micom_age_ms".equals(key))safe=ranged(value,-1,Long.MAX_VALUE);
            else safe=ranged(value,0,"window_ms".equals(key)?Long.MAX_VALUE:Integer.MAX_VALUE);
            if(safe==null)return null;out.append(' ').append(key).append('=').append(safe);
        }
        return out.toString();
    }
    private static String cameraRecord(String[] words) {
        if(words.length==4 && "event=ready".equals(words[1]) && "legacy_fields=17".equals(words[2]) &&
                ("modern_fields=0".equals(words[3]) || "modern_fields=21".equals(words[3])))
            return "USB_CP_CAMERA event=ready legacy_fields=17 "+words[3];
        if(words.length!=8 || !listed("writer=trap writer=trap_new",words[1]) || !"source=navi".equals(words[2]))return null;
        String[] keys={"owner","returned","elapsed_ms","values","dropped"};
        StringBuilder out=new StringBuilder("USB_CP_CAMERA ").append(words[1]).append(" source=navi");
        for(int i=0;i<keys.length;i++) {
            if(!words[i+3].startsWith(keys[i]+"="))return null;
            String value=words[i+3].substring(keys[i].length()+1),safe;
            if(i==0)safe=ranged(value,0,3);
            else if(i==1)safe=ranged(value,0,1);
            else if(i==2)safe=ranged(value,0,Long.MAX_VALUE);
            else if(i==3) {
                int count="writer=trap".equals(words[1])?17:21;
                if(value.split(",",-1).length!=count)return null;
                safe=numericList(value,count,Integer.MIN_VALUE,Integer.MAX_VALUE);
            } else safe=ranged(value,0,Integer.MAX_VALUE);
            if(safe==null)return null;out.append(' ').append(keys[i]).append('=').append(safe);
        }
        return out.toString();
    }
    private static boolean fields(StringBuilder out, String[] words, int from, int end,
            String numbers, String booleans, String streams, String types, String directions) {
        Set<String> seen = new HashSet<String>();
        for (int i = from; i < end; i++) {
            int eq = words[i].indexOf('=');
            if (eq < 1) continue;
            String key = words[i].substring(0,eq), value = words[i].substring(eq+1), safe;
            if (listed(numbers,key)) safe = diagnosticNumber(key,value);
            else if (listed(booleans,key)) safe = listed("true false",value) ? value : null;
            else if ("stream".equals(key) && !streams.isEmpty()) safe = listed(streams,value) ? value : null;
            else if ("type".equals(key) && !types.isEmpty()) safe = listed(types,value) ? value : null;
            else if ("direction".equals(key) && !directions.isEmpty()) safe = listed(directions,value) ? value : null;
            else continue; // Road names, coordinates, SSIDs, IDs and future unknown fields are excluded.
            if (safe == null || !seen.add(key)) return false;
            out.append(' ').append(key).append('=').append(safe);
        }
        return true;
    }
    private static String metricRecord(String message) {
        String[] words = message.split(" +");
        if (words.length < 2) return null;
        String marker = words[0], event = words[1];
        if ("USB_CP_TBT_DIAG".equals(marker)) return tbtDiagnostic(words);
        if ("USB_CP_CAMERA".equals(marker)) return cameraRecord(words);
        if ("USB_CP_TBT_PHASE".equals(marker)) {
            if (!message.matches("USB_CP_TBT_PHASE route=-?[0-9]{1,10} phase=-?[0-9]{1,10} present=[01] component=-?[0-9]{1,10} maneuver=-?[0-9]{1,10} meters=-?[0-9]{1,10} dropped=[0-9]{1,10}")) return null;
            for (int i = 1; i < words.length; i++) {
                String value = words[i].substring(words[i].indexOf('=') + 1);
                if (integer(value, false) == null) return null;
            }
            return message;
        }
        if ("USB_CP_TBT_PROGRESS".equals(marker)) {
            if (message.matches("USB_CP_TBT_PROGRESS event=native_progress native=-?[0-9]{1,10}")) {
                try { Integer.parseInt(words[2].substring(7)); return message; }
                catch (NumberFormatException invalid) { return null; }
            }
            if (message.equals("USB_CP_TBT_PROGRESS event=ready snapshot_ttl_ms=2000 replay_interval_ms=200")) return message;
            if (message.matches("USB_CP_TBT_PROGRESS event=(unavailable|snapshot_unavailable|snapshot_rejected|replay_skipped|replay_failed|worker_failed)")) return message;
            if (message.matches("USB_CP_TBT_PROGRESS event=snapshot_stale snapshot_age_ms=[0-9]{1,19}")) {
                StringBuilder out = new StringBuilder(marker).append(' ').append(event);
                return fields(out,words,2,words.length,"snapshot_age_ms","","","","") ? out.toString() : null;
            }
            if (!message.matches("USB_CP_TBT_PROGRESS event=(native_overlay|fine_progress_submitted|native_bar_restored) fine=-?[0-9]{1,10} native=-?[0-9]{1,10} snapshot_age_ms=[0-9]{1,19}")) return null;
            try {
                int fine=Integer.parseInt(words[2].substring(5));
                Integer.parseInt(words[3].substring(7));
                if (!"event=native_bar_restored".equals(event) && (fine<0 || fine>100)) return null;
            } catch (NumberFormatException invalid) { return null; }
            StringBuilder out = new StringBuilder(marker).append(' ').append(event);
            return fields(out,words,2,words.length,"fine native snapshot_age_ms","","","","") ? out.toString() : null;
        }
        if ("USB_CP_TBT_RENDER".equals(marker)) {
            if (!message.matches("USB_CP_TBT_RENDER source=(raw|display|none) metersMilli=(-1|[0-9]{1,19}) type=[0-9]{1,5} direction=[0-9]{1,3}([.][05])? bar=[0-9]{1,3} distanceMilli=[0-9]{1,19} unit=[0-4] valid=[01]")) return null;
            String direction = words[4].substring("direction=".length());
            if (Double.parseDouble(direction) >= 360) return null;
            StringBuilder out=new StringBuilder(marker).append(' ').append(event);
            return fields(out,words,2,words.length,"metersMilli type bar distanceMilli unit valid","","","",direction) ? out.toString() : null;
        }
        if ("USB_CP_LANE_RAW".equals(marker)) {
            if (!message.matches("USB_CP_LANE_RAW errors=[0-9]{1,10}")) return null;
            return integer(words[1].substring(7), false) == null ? null : message;
        }
        if ("USB_CP_TBT_AUX_FAILED".equals(marker)) {
            return message.matches("USB_CP_TBT_AUX_FAILED channel=(lane|pattern|queue|gate|callback|raw-normalize|lane-select|junction-select|submit|start)") ? message : null;
        }
        if ("USB_CP_RAW_LANES".equals(marker)) {
            if (!message.matches("USB_CP_RAW_LANES (ready|decoded|delivered|overflow|decode|decode-failed|delivery-failed|queue-failed|closed) count=[0-9]{1,10}")) return null;
            return integer(words[2].substring(6), false) == null ? null : message;
        }
        if ("USB_CP_TBT_LANE_INPUT".equals(marker)) {
            if (!message.matches("USB_CP_TBT_LANE_INPUT source=(raw|oem) component=-?[0-9]{1,10} index=-?[0-9]{1,10} lanes=[0-8] exact=[01]")) return null;
            for (int i=2; i<words.length; i++) if (integer(words[i].substring(words[i].indexOf('=')+1), false)==null) return null;
            return message;
        }
        if ("USB_CP_TBT_AUX_SELECT".equals(marker)) {
            if (!message.matches("USB_CP_TBT_AUX_SELECT component=-?[0-9]{1,10} maneuver=-?[0-9]{1,10} index=-?[0-9]{1,10} source=(raw|oem|none) exact=[01] guide=[0-9]{1,5}")) return null;
            for (int i=1; i<words.length; i++) if (i!=4 && integer(words[i].substring(words[i].indexOf('=')+1), false)==null) return null;
            return message;
        }
        if ("USB_CP_APPROACH_BAR".equals(marker)) {
            return message.matches("USB_CP_APPROACH_BAR event=(ready|unavailable|applied|restored|waiting_snapshot|camera_owned|expired|failed)") ? message : null;
        }
        if ("USB_CP_TBT_JUNCTION".equals(marker)) {
            if (message.matches("USB_CP_TBT_JUNCTION event=(ready|unavailable|write_failed)")) return message;
            if (!message.matches("USB_CP_TBT_JUNCTION event=(write|clear) guide=[0-9]{1,5} arrow=[0-9]{1,5} component=-?[0-9]{1,10} maneuver=-?[0-9]{1,10}")) return null;
            for (int i=2; i<words.length; i++) if (integer(words[i].substring(words[i].indexOf('=')+1), false)==null) return null;
            return message;
        }
        if ("USB_CP_TBT_DISTANCE".equals(marker)) {
            StringBuilder out=new StringBuilder(marker);
            return fields(out,words,1,words.length,"displayUnit","raw text usable","","","") ? out.toString() : null;
        }
        if ("USB_CP_TBT_DISPLAY".equals(marker)) {
            if (!"first_turn".equals(event)) return null;
            StringBuilder out=new StringBuilder(marker).append(" first_turn");
            return fields(out,words,2,words.length,"icon","","","","") ? out.toString() : null;
        }
        if ("USB_CP_NATIVE_TBT".equals(marker)) return nativeTbtRecord(message);
        if ("USB_CP_NATIVE_AR".equals(marker)) return nativeArRecord(message);
        if ("USB_CP_VIDEO".equals(marker)) return videoRecord(words);
        if ("USB_AA_EXTERNAL".equals(marker)) {
            return message.matches("USB_AA_EXTERNAL event=(wrong_uid|ready|hook_unavailable|unavailable|obsolete_transition|wireless_ap_changed|down|available)")
                    ? message : null;
        }
        if ("USB_CP_AA_TRACE".equals(marker)) {
            String n="[0-9]{1,19}", i="-?[0-9]{1,10}";
            String payload=message.substring(marker.length()+1);
            String turn="ev="+i+" side="+i;
            return payload.matches("status "+i)
                    || payload.matches("turn "+turn+" angle="+i)
                    || payload.matches("lanes count=[0-8]")
                    || payload.matches("quiet "+n+" ms, ended by (turn|distance|status|lanes|dump)")
                    || payload.equals("phone is talking again: guidance back")
                    || payload.matches("guide lost: nothing from the phone for "+n+" ms")
                    || payload.matches("quiet for "+n+" ms, the route is still live")
                    || payload.matches("flip "+n+": "+turn+" is back after another turn within 10000 ms")
                    || payload.matches("state "+turn+" m="+i+" rerouting=(true|false) focus=(car|phone) calls turn/dist/status/lanes/dump="+n+"/"+n+"/"+n+"/"+n+"/"+n+" quiet="+n+" maxGap="+n)
                    ? message : null;
        }
        if ("USB_CP_AA".equals(marker)) {
            if (message.matches("USB_CP_AA (stock_tbt=1|usb_guard_ready=[01]|native_applied=[01]|tbt_unavailable|release_failed=1|callback_failed=1)")) return message;
            if (message.matches("USB_CP_AA tbt_ready standard=[01] queue_limit=128 native_recipe_requested=1")) return message;
            if ("queue_reset".equals(event)) {
                StringBuilder out=new StringBuilder(marker).append(" queue_reset");
                return fields(out,words,2,words.length,"dropped","","","","") ? out.toString() : null;
            }
            return null;
        }
        if ("USB_CP_DEV_LOG".equals(marker)) {
            if (!"start".equals(event)) return null;
            StringBuilder out = new StringBuilder(marker).append(" start");
            return fields(out,words,2,words.length,"wall_ms elapsed_ms","","","","") ? out.toString() : null;
        }
        if ("USB_CP_POWER".equals(marker)) {
            return message.matches("USB_CP_POWER event=(queued|delivered|superseded|retry) state=(ON|OFF)( source=(DIPO|WELCOME))?")
                    ? message : null;
        }
        if ("USB_CP_ANIMATION_POWER".equals(marker))
            return message.matches("USB_CP_ANIMATION_POWER event=(ready|unavailable|welcome_start|goodbye_start|read_failed)") ? message : null;
        if ("USB_CP_LOCATION".equals(marker)) {
            if (message.matches("USB_CP_LOCATION (pulse_ready min_gap_ms=(0|250)|pulse_unavailable|pulse_failed)")) return message;
            if("pulse_window".equals(event))return pulseRecord(words);
            if(message.equals("USB_CP_LOCATION rmc_ready output_only=1 vehicle_kmh=1 preserve_fix=1"))return message;
            if("rmc_window".equals(event))return rmcRecord(words);
            if (!listed("observers_ready observers_unavailable request_started window stopped",event)) return null;
            StringBuilder out = new StringBuilder(marker).append(' ').append(event);
            return fields(out,words,2,words.length,LOCATION_FIELDS,"","","","") ? out.toString() : null;
        }
        if ("USB_CP_AUDIO_ROUTE".equals(marker)) {
            if (!listed("ready unavailable alt_switch music_retryable",event)) return null;
            StringBuilder out = new StringBuilder(marker).append(' ').append(event);
            String numbers = "alt_switch".equals(event) ? "generation from to route status"
                    : "music_retryable".equals(event) ? "generation" : "";
            return fields(out,words,2,words.length,numbers,"","","","") ? out.toString() : null;
        }
        if (!"USB_CP_AUDIO".equals(marker)) return null;
        StringBuilder out = new StringBuilder(marker).append(' ').append(event);
        if (listed("observers_ready observers_unavailable session_started window",event))
            return fields(out,words,2,words.length,AUDIO_FIELDS,"","","","") ? out.toString() : null;
        if (!"event".equals(event) || words.length < 5 || !words[2].startsWith("generation=") ||
                !words[3].startsWith("elapsed_ms=")) return null;
        if (!fields(out,words,2,4,"generation elapsed_ms","","","","")) return null;
        String kind = words[4], numbers = "", booleans = "", streams = "", types = "", directions = "";
        if ("native_start".equals(kind)) numbers = "stream type rate";
        else if ("native_stop".equals(kind)) numbers = "stream";
        else if ("stock_start".equals(kind) || "stock_stop".equals(kind)) {
            streams = "Main Alt MainHigh Invalid unknown";
            if ("stock_start".equals(kind)) {
                types = "Unknown Compatibility Default Media Telephony SpeechRecognition Alert unknown";
                numbers = "rate";
            }
            booleans = "exception";
        } else if ("duck".equals(kind) || "unduck".equals(kind)) booleans = "exception";
        else if (listed("micom_start micom_end micom_music",kind)) {
            numbers = "requested selected_stream"; booleans = "accepted exception";
        } else if ("micom_status".equals(kind)) numbers = "code";
        else if ("standard_volume_key".equals(kind)) directions = "up down";
        else return null;
        out.append(' ').append(kind);
        return fields(out,words,5,words.length,numbers,booleans,streams,types,directions) ? out.toString() : null;
    }
    private static final java.util.regex.Pattern LANE_FIELDS = java.util.regex.Pattern.compile(
            "([123]):\\[((?:-?[0-9]{1,11}(?:,-?[0-9]{1,11}){0,3})?)(,\\.\\.\\.)?\\]");
    private static String nativeArRecord(String message) {
        if (message.matches("USB_CP_NATIVE_AR event=(ready|unavailable)")) return message;
        String[] words = message.split(" ", -1);
        if (words.length != 3 || !words[1].startsWith("writer=") || !words[2].startsWith("values=")) return null;
        String writer = words[1].substring(7);
        int count = "trap".equals(writer) || "trap_new".equals(writer) ? 1
                : "clu14".equals(writer) ? 3 : "clu14_new".equals(writer) ? 5 : 0;
        if (count == 0) return null;
        String[] values = words[2].substring(7).split(",", -1);
        if (values.length != count) return null;
        for (String value : values) if (integer(value, false) == null) return null;
        return message;
    }
    private static String nativeTbtRecord(String message) {
        String[] words = message.split(" ");
        if (words.length != 3 || !words[1].startsWith("writer=") || !words[2].startsWith("values=")) return null;
        String writer = words[1].substring(7), types;
        if ("update_tbt_info".equals(writer)) types="IIFFIIIII";
        else if ("update_tbt_sub_info".equals(writer)) types="FIIIIIIFI";
        else if ("update_tbt_sub_info_b".equals(writer)) types="FIIIIIIFII";
        else if ("update_tbt_sub_info_2c".equals(writer)) types="IIIIIIIII";
        else if ("update_remain_dis".equals(writer)) types="IIII";
        else return null;
        String[] values=words[2].substring(7).split(",",-1);
        if (values.length!=types.length()) return null;
        for (int i=0;i<values.length;i++) {
            if (types.charAt(i)=='I') { if (integer(values[i],false)==null) return null; }
            else {
                if (!values[i].matches("-?[0-9]{1,10}(?:[.][0-9]{1,10})?(?:[Ee][+-]?[0-9]{1,3})?")) return null;
                try {
                    float value=Float.parseFloat(values[i]);
                    if (Float.isNaN(value) || Float.isInfinite(value)) return null;
                } catch (NumberFormatException invalid) { return null; }
            }
        }
        return message;
    }
    private static String laneRecord(String message) {
        String[] words = message.split(" +");
        if (words.length < 3 || !"USB_CP_TBT_LANES".equals(words[0]) ||
                !words[1].startsWith("input=") || !words[2].startsWith("output=")) return null;
        String input = integer(words[1].substring(6),false), output = integer(words[2].substring(7),false);
        if (input == null || output == null || Integer.parseInt(input) < 0 ||
                Integer.parseInt(output) < 0 || Integer.parseInt(output) > 8) return null;
        StringBuilder out = new StringBuilder("USB_CP_TBT_LANES input=").append(input).append(" output=").append(output);
        Set<String> lanes = new HashSet<String>();
        for (int i = 3; i < words.length; i++) {
            int eq = words[i].indexOf('=');
            if (eq < 0) continue;
            String lane = words[i].substring(0,eq), value = words[i].substring(eq+1);
            if (!lane.matches("lane[0-7]")) continue;
            if (!lanes.add(lane)) return null;
            if (value.isEmpty() || "unknown".equals(value)) {
                out.append(' ').append(lane).append(value.isEmpty() ? "=empty" : "=unknown"); continue;
            }
            java.util.regex.Matcher matcher = LANE_FIELDS.matcher(value);
            Set<String> ids = new HashSet<String>();
            int end = 0;
            while (matcher.find()) {
                if (matcher.start() != end || !ids.add(matcher.group(1))) return null;
                end = matcher.end();
                String field = "1".equals(matcher.group(1)) ? "status" : "2".equals(matcher.group(1)) ? "angles" : "highlight";
                out.append(' ').append(lane).append('_').append(field).append('=');
                String[] values = matcher.group(2).split(",");
                if (matcher.group(2).isEmpty()) out.append("empty");
                else for (int k = 0; k < values.length; k++) {
                    String number = integer(values[k],false);
                    if (number == null) return null;
                    if (k > 0) out.append(',');
                    out.append(number);
                }
                if (matcher.group(3) != null) out.append(' ').append(lane).append('_').append(field).append("_more=1");
            }
            if (end != value.length()) return null;
        }
        return out.toString();
    }
    private static final String POWER_REASONS="UNKNOWN RESTORED INITIAL_READING MICOM_OFF PREPARE_SYSTEM_OFF SYSTEM_OFF IGN_OFF_DRIVER_DOOR IGN_ON MICOM_ACC_ON BCM_ACC_ON BCM_OFF";
    private static String powerDiagnostic(String line) {
        String[] words=line.split(" +");if(words.length<2)return null;
        boolean input="AP_POWER_INPUT".equals(words[0]);
        if(!input && !"AP_POWER_DECISION".equals(words[0]))return null;
        String keys=input ? "elapsed_ms state reason decision_ns micom_raw getter_off preparing ignition ignition_live ignition_age_ms " +
                "acc_raw acc_live acc_age_ms driver_door driver_door_age_ms micom_event_raw polls poll_failures ignition_events acc_events door_events micom_events prepare_events off_events"
                : "paused source decision_ns event event_ns reader reader_state reader_ns reader_reason";
        Set<String> seen=new HashSet<String>();StringBuilder out=new StringBuilder(words[0]);
        for(int i=1;i<words.length;i++) {
            int eq=words[i].indexOf('=');if(eq<1)return null;
            String key=words[i].substring(0,eq),value=words[i].substring(eq+1),safe;
            if(!listed(keys,key) || !seen.add(key))return null;
            if(listed("state reader_state",key))safe=listed("UNKNOWN ON OFF",value)?value:null;
            else if(listed("reason reader_reason",key))safe=listed(POWER_REASONS,value)?value:null;
            else if("source".equals(key))safe=listed("BASELINE UNKNOWN EVENT READER INVALID_EVENT REMOVED_EVENT",value)?value:null;
            else if("event".equals(key))safe=listed("NONE OLD_BOOT VALID_ON VALID_OFF INVALID REMOVED",value)?value:null;
            else if("reader".equals(key))safe=listed("ABSENT INVALID STALE VALID SKIPPED",value)?value:null;
            else if(key.endsWith("_ns"))safe=value.matches("0|[1-9][0-9]{0,17}")?value:null;
            else if("elapsed_ms".equals(key))safe=ranged(value,0,Long.MAX_VALUE);
            else if(key.endsWith("_age_ms"))safe=ranged(value,-1,Long.MAX_VALUE);
            else if(listed("micom_raw acc_raw micom_event_raw",key))safe=integer(value,false);
            else if(listed("getter_off preparing ignition ignition_live acc_live driver_door",key))safe=ranged(value,-1,1);
            else if("paused".equals(key))safe=ranged(value,0,1);
            else safe=ranged(value,0,Integer.MAX_VALUE);
            if(safe==null)return null;out.append(' ').append(key).append('=').append(safe);
        }
        return out.toString();
    }
    private static String powerRecord(String line) {
        if(line.startsWith("AP_POWER_INPUT ") || line.startsWith("AP_POWER_DECISION "))return powerDiagnostic(line);
        if (line.equals("AP_USB_POWER unavailable=1") || line.matches("AP_USB_POWER phase=(ACTIVE|STOPPING) control=(ON|AUTO|UNKNOWN) runtime=(ACTIVE|SUSPENDED|SUSPENDING|RESUMING|UNSUPPORTED|ERROR|UNKNOWN) authorized=(0|1|UNKNOWN) wakeup=(ENABLED|DISABLED|UNKNOWN) vbus_handler=(FOUND|UNKNOWN) physical_switch=UNVERIFIED")) return line;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "^AP_POWER_OBSERVED state=(ON|OFF|UNKNOWN):(-?[0-9]{1,11}|unknown)$").matcher(line);
        if (match.matches()) {
            String raw = "unknown".equals(match.group(2)) ? "unknown" : integer(match.group(2),false);
            if (raw == null || ("UNKNOWN".equals(match.group(1)) && !"unknown".equals(raw))) return null;
            return "AP_POWER_OBSERVED state=" + match.group(1) + " raw_status=" + raw;
        }
        match = java.util.regex.Pattern.compile(
                "^AP_POWER_OBSERVED state=(ON|OFF|UNKNOWN):(-?[0-9]{1,11}|unknown):([A-Z_]+):(0|[1-9][0-9]{0,17})$").matcher(line);
        if (match.matches()) {
            String raw = "unknown".equals(match.group(2)) ? "unknown" : integer(match.group(2),false);
            if (raw == null || !listed(POWER_REASONS,match.group(3))) return null;
            return "AP_POWER_OBSERVED state=" + match.group(1) + " raw_status=" + raw +
                    " reason=" + match.group(3) + " decision_ns=" + match.group(4);
        }
        if (line.matches("AP_POWER_READER (micom|bcm)_listener=READY") ||
                line.matches("AP_POWER_READER (micom|bcm)_listener=UNAVAILABLE type=[A-Za-z][A-Za-z0-9_]{0,63}")) return line;
        return line.matches("AP_LINK_DOWN_FAILED iface=rtwlan[0-9]{1,4}") ? "AP_LINK_DOWN_FAILED" : null;
    }
    private static String usbIdentityRecord(String line) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile(
                "^wall_ms=([0-9]{1,15}) elapsed_ms=([0-9]{1,15}) CP_USB_ID (.*)$").matcher(line);
        if (!m.matches()) return null;
        String detail=m.group(3);
        if (detail.matches("event=(ready|seen) method=(none|ancestry|single_bound_usb) id=(none|[0-9a-f]{4}:[0-9a-f]{4})(?: dropped=[0-9]{1,19})?"))
            return m.group(1)+" "+m.group(2)+" usb "+detail;
        java.util.regex.Matcher failure=java.util.regex.Pattern.compile(
                "^reason=([a-z_]+) id=(none|[0-9a-f]{4}:[0-9a-f]{4})(?: dropped=([0-9]{1,19}))?$").matcher(detail);
        if (!failure.matches() || !listed("bad_iface iface_missing parent_outside_sys id_unsupported parent_without_usb no_parent_not_virtual ambiguous_proc_driver proc_driver_missing usb_bus_missing ambiguous_usb_driver ambiguous_usb usb_driver_unbound direct single_bound_usb",failure.group(1))) return null;
        String count=failure.group(3)==null?null:integer(failure.group(3),true);
        if (failure.group(3)!=null && count==null) return null;
        return m.group(1)+" "+m.group(2)+" usb reason="+failure.group(1)+" id="+failure.group(2)+(count==null?"":" dropped="+count);
    }
    private static String providerDetails(String prefix,String tail) {
        if (tail==null || tail.isEmpty()) return prefix;
        StringBuilder out=new StringBuilder(prefix);
        Set<String> seen=new HashSet<String>();
        for (String word:tail.split(" +")) {
            int eq=word.indexOf('='); if(eq<1)continue;
            String key=word.substring(0,eq),value=word.substring(eq+1),safe;
            if ("kind".equals(key)) {
                safe=listed("timeout unexpected_ap_configuration unsupported_interface unsupported_usb_identity usb_identity_changed_during_read process_ownership_mismatch interface_unavailable ap_identity_mismatch ap_restarted_while_reading ap_not_ready_or_unsupported_security ambiguous_address no_ap_address invalid_pid symlink_refused file_too_large configured_ssid_mismatch active_ssid_mismatch configured_passphrase_mismatch active_passphrase_mismatch active_psk_mismatch missing_active_credentials configuration_changed_during_read permission_denied not_permitted missing_endpoint connection_refused socket_not_created duplicate_record_key invalid_response io_or_runtime_error",value) ? value : "unknown";
            } else if (listed("ready timeout identity",key)) safe=listed("0 1",value)?value:null;
            else if (listed("failures age_ms dropped",key)) safe=integer(value,true);
            else continue;
            if (safe==null || !seen.add(key))return null;
            out.append(' ').append(key).append('=').append(safe);
        }
        return out.toString();
    }
    /** Manual connection records carry only a fixed phase and a bounded result/category. */
    private static String manualRecord(String message) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile(
                "^USB_CP_MANUAL event=(accepted|preparing_ap|preparing_iap|server_ready)$").matcher(message);
        if(m.matches())return "USB_CP_MANUAL event="+m.group(1);
        m=java.util.regex.Pattern.compile("^USB_CP_MANUAL event=finished status=(-?[0-9]{1,19})$").matcher(message);
        if(m.matches()) {
            String status=ranged(m.group(1),0,10);
            return status==null?null:"USB_CP_MANUAL event=finished status="+status;
        }
        m=java.util.regex.Pattern.compile("^USB_CP_MANUAL event=(server_failed|request_failed|gate_failed|dispatch_failed) " +
                "kind=(linkage|io|security|interrupted|runtime|other)$").matcher(message);
        if(m.matches())return "USB_CP_MANUAL event="+m.group(1)+" kind="+m.group(2);
        // Producers attach a package/class token. Deliberately discard it: only the
        // fixed bootstrap phase is useful in exported diagnostics.
        m=java.util.regex.Pattern.compile("^USB_CP_APP_BOOTSTRAP phase=entered(?: package=[A-Za-z0-9_.$]{1,255})?$").matcher(message);
        if(m.matches())return "USB_CP_APP_BOOTSTRAP phase=entered";
        m=java.util.regex.Pattern.compile("^USB_CP_APP_BOOTSTRAP phase=(oem_power_hook_failed|tbt_failed)(?: type=[A-Za-z][A-Za-z0-9_$]{0,63})?$").matcher(message);
        return m.matches()?"USB_CP_APP_BOOTSTRAP phase="+m.group(1):null;
    }
    static String sanitized(String stream, String line) {
        if ("power-input".equals(stream) || "power-decision".equals(stream)) {
            if (line == null || line.length() > 4096 || line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) return null;
            String marker="power-input".equals(stream)?"AP_POWER_INPUT":"AP_POWER_DECISION";
            return line.startsWith(marker+" ") ? powerDiagnostic(line) : null;
        }
        if (line.length() > 4096 || line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) return null;
        if ("ap".equals(stream)) {
            String prefix = "AP_EVENT state=";
            if (!line.startsWith(prefix)) return powerRecord(line);
            String phase = line.substring(prefix.length());
            return listed(AP_STATES, phase) ? phase : null;
        }
        if ("provider".equals(stream)) {
            java.util.regex.Matcher manual=java.util.regex.Pattern.compile(
                    "^wall_ms=([0-9]{1,15}) elapsed_ms=([0-9]{1,15}) CP_MANUAL_AP " +
                    "recovery=([0-8]|failed)(?: dropped=([0-9]{1,19}))?$").matcher(line);
            if(manual.matches()) {
                if(manual.group(4)!=null && ranged(manual.group(4),0,Long.MAX_VALUE)==null)return null;
                return manual.group(1)+" "+manual.group(2)+" manual_ap recovery="+manual.group(3);
            }
            String usb=usbIdentityRecord(line);
            if (usb!=null) return usb;
            java.util.regex.Matcher config = java.util.regex.Pattern.compile(
                    "^wall_ms=([0-9]{1,15}) elapsed_ms=([0-9]{1,15}) CP_CONFIG external_dongle=(ON|OFF|INVALID)$").matcher(line);
            if (config.matches()) return config.group(1)+" "+config.group(2)+" config external_dongle="+config.group(3);
            java.util.regex.Matcher power = PROVIDER_POWER.matcher(line);
            if (power.matches()) return power.group(1) + " " + power.group(2) +
                    " power event=" + power.group(3) + " state=" + power.group(4) +
                    (power.group(5)==null?"":" source="+power.group(5));
            java.util.regex.Matcher m = PROVIDER.matcher(line);
            if (!m.matches()) return null;
            boolean provider = "PROVIDER".equals(m.group(3));
            String event = m.group(4), phase = m.group(5);
            if (!listed(provider ? "started ready down heartbeat" : "retry unavailable recovered", event)) return null;
            if (phase != null && (provider || !listed(PHASES, phase))) return null;
            return providerDetails(m.group(1) + " " + m.group(2) + (provider ? " provider " : " observer ") +
                    event + (phase == null ? "" : " " + phase),m.group(6));
        }
        if ("boot".equals(stream)) {
            if (line.matches("AP_STARTUP version=1[.]0[.]([0-9]|1[0-5]) phase=(ENTRY|PRIVATE_LOG_READY|ANDROID_READY)")) return line;
            if (line.matches("AP_STARTUP_FAILURE reason=(RUNTIME_MISSING|RUNTIME_SOURCE|TARGET_MISMATCH|EXISTING_AP_CONFLICT|PREPARE_PATHS|VENDOR_ASSETS_INVALID|PID_WRITE|STOP_TIMEOUT)")) return line;
            if (line.matches("AP_DRIVER state=(loading|ready|failed) family=(88x2bu|8851bu)")) return line;
            return null;
        }
        if ("services".equals(stream))
            return line.matches("neev-usb-carplay (usb-ap|ap-provider|development-log) (waiting|running|exited|failed|stopped) [0-9]{1,10} [0-9]{1,4}") ? line : null;
        if ("modules".equals(stream)) {
            String[] parts=line.trim().split("\\s+");
            return parts.length>=3 && (parts[0].equals("88x2bu") || parts[0].equals("8851bu")) ? parts[0]+" LOADED" : null;
        }
        java.util.regex.Matcher m = PROJECTION.matcher(line);
        if (!m.matches()) return null;
        String message = m.group(3), token = message.split(" ", 2)[0];
        if("USB_CP_MANUAL".equals(token) || "USB_CP_APP_BOOTSTRAP".equals(token)) {
            String manual=manualRecord(message);
            return manual==null?null:m.group(1)+" "+manual;
        }
        String detail = "USB_CP_TBT_LANES".equals(token) ? laneRecord(message) : metricRecord(message);
        if (detail != null) return m.group(1) + " " + detail;
        if ("USB_CP_TBT_DISTANCE".equals(token) || "USB_CP_TBT_DISPLAY".equals(token)) return null;
        if (token.startsWith("USB_CP_") && listed(CP_CODES, token.substring(7))) return m.group(1) + " " + token;
        if (m.group(2) == null) return null;
        if (message.startsWith("CarPlay session created (usb=")) return m.group(1) + " SESSION_CREATED";
        if (message.startsWith("CarPlay session started transport=")) return m.group(1) + " SESSION_STARTED";
        if (message.startsWith("CarPlay session finalized type=")) return m.group(1) + " SESSION_FINALIZED";
        return null;
    }
    static final class Events {
        final ArrayDeque<String> lines = new ArrayDeque<String>();
        final int limit;
        String state = "OK";
        Events() { this(EVENT_LIMIT); }
        Events(int limit) { this.limit=limit; }
        void append(String event) {
            if (event == null) return;
            if (lines.size() == limit) lines.removeFirst();
            lines.addLast(event);
        }
    }
    private static boolean canonical(File file) throws IOException {
        return file.isAbsolute() && file.equals(file.getCanonicalFile());
    }
    private static Events fileEvents(String stream, File file) {
        boolean power="power-input".equals(stream) || "power-decision".equals(stream);
        Events events = new Events(power?1024:EVENT_LIMIT);
        try {
            if (!canonical(file)) { events.state = "UNSAFE_PATH"; return events; }
            if (!file.exists()) { events.state = "MISSING"; return events; }
            if (!file.isFile()) { events.state = "NOT_A_FILE"; return events; }
            RandomAccessFile in = new RandomAccessFile(file, "r");
            try {
                long size = in.length();
                int length = (int)Math.min(size, power?65536:FILE_WINDOW);
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
    private static Events moduleEvents(File file) {
        // procfs reports size zero: consume its bounded text stream, not RandomAccessFile.length().
        Events events=new Events();
        try {
            if(!canonical(file)) {events.state="UNSAFE_PATH";return events;}
            if(!file.isFile()) {events.state="MISSING";return events;}
            InputStream input=new FileInputStream(file);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try {
                byte[] buf=new byte[2048];int n,total=0;
                while((n=input.read(buf))!=-1) {
                    total+=n;if(total>65536) {events.state="READ_LIMIT";return events;}
                    bytes.write(buf,0,n);
                }
            } finally {input.close();}
            for(String line:bytes.toString("UTF-8").split("\n")) events.append(sanitized("modules",line));
        } catch(IOException failed) {events.state="READ_FAILED";}
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
    static void boot(File output, File supervisor, File services, File modules) throws IOException {
        if (!canonical(output) || !output.isDirectory() ||
                !output.getName().matches("NEEV_BUNDLE_RESULT_[0-9]{8}_[0-9]{6}_[0-9]+"))
            throw new IOException("invalid boot destination");
        Events startup = fileEvents("boot", supervisor);
        Events svc = fileEvents("services", services);
        Events driver = moduleEvents(modules);
        create(output,"boot-supervisor.txt",body(startup));
        create(output,"boot-services.txt",body(svc));
        create(output,"boot-drivers.txt",body(driver));
        create(output,"boot-export-status.txt","supervisor="+startup.state+" events="+startup.lines.size()+
                "\nservices="+svc.state+" events="+svc.lines.size()+"\nmodules="+driver.state+" events="+driver.lines.size()+"\n");
    }
    static int persistent(File output, File keeper) throws IOException {
        if (!canonical(output) || !output.isDirectory() ||
                !output.getName().matches("NEEV_BUNDLE_RESULT_[0-9]{8}_[0-9]{6}_[0-9]+"))
            throw new IOException("invalid persistent destination");
        // HMG-API 1.0.1's boot LogKeeper already retains 256 KiB + .1. Do not
        // start another logger or export other mods' lines / raw log contents.
        Events previous=fileEvents("carplay",new File(keeper.getPath()+".1"));
        Events current=fileEvents("carplay",keeper);
        create(output,"carplay-persistent-previous.txt",body(previous));
        create(output,"carplay-persistent-current.txt",body(current));
        create(output,"persistent-log-status.txt","previous="+previous.state+" events="+previous.lines.size()+
                "\ncurrent="+current.state+" events="+current.lines.size()+
                "\nsource=HMG_API_1_0_1_LOG_KEEPER\nretention=BOUNDED_ROTATING_NOT_COMPLETE_HISTORY\n");
        return "OK".equals(current.state) && ("OK".equals(previous.state)||"MISSING".equals(previous.state)) ? 0 : 7;
    }
    /** Export all four bounded development slots, not just the recent 256-event tail. */
    static int development(File output, File directory) throws IOException {
        if (!canonical(output) || !output.isDirectory() ||
                !output.getName().matches("NEEV_BUNDLE_RESULT_[0-9]{8}_[0-9]{6}_[0-9]+"))
            throw new IOException("invalid development destination");
        StringBuilder status = new StringBuilder("source=OWN_DEVELOPMENT_LOGGER\nretention=4_FILES_2_MIB_EACH\n" +
                "export=FINITE_VOCABULARY_WITH_ORIGINAL_LOGCAT_PID\nrotation_during_export=NOT_ATOMIC_SNAPSHOT\n");
        int result=0;
        for (int slot=3;slot>=0;slot--) {
            String suffix=slot==0?"":"."+slot, label=slot==0?"current":Integer.toString(slot);
            File source=new File(directory,"carplay.log"+suffix);
            File target=new File(output,"development-"+label+".txt");
            if (!canonical(target) || !target.createNewFile()) throw new IOException("development output exists");
            String state="OK"; int read=0, kept=0, dropped=0;
            Writer writer=new OutputStreamWriter(new FileOutputStream(target),"UTF-8");
            try {
                if (!canonical(directory) || !canonical(source)) state="UNSAFE";
                else if (!source.exists()) state="MISSING";
                else if (!source.isFile()) state="UNSAFE";
                else {
                    InputStream input=new FileInputStream(source);
                    try {
                        byte[] chunk=new byte[4096]; ByteArrayOutputStream line=new ByteArrayOutputStream();
                        boolean discard=false, limited=false; int count;
                        while ((count=input.read(chunk))!=-1) {
                            if (count>2*1024*1024-read) { state="READ_LIMIT";limited=true;break; }
                            read+=count;
                            for (int j=0;j<count;j++) {
                                int b=chunk[j]&255;
                                if (b=='\n') {
                                    String raw=discard?null:line.toString("UTF-8");
                                    String event=raw==null?null:sanitized("carplay",raw);
                                    java.util.regex.Matcher pid=raw==null?null:DEVELOPMENT_PID.matcher(raw);
                                    if (event!=null && pid.matches()) {
                                        writer.write("pid="+pid.group(1)+" "+event+"\n"); kept++;
                                    } else dropped++;
                                    line.reset();discard=false;
                                } else if (!discard) {
                                    if (line.size()>=4096) {discard=true;line.reset();}
                                    else line.write(b);
                                }
                            }
                        }
                        // A concurrently appended final partial line is not a complete event.
                        if (!limited && (line.size()>0 || discard)) dropped++;
                    } finally {input.close();}
                }
            } catch (IOException unavailable) {state="READ_ERROR";}
            finally {writer.close();}
            status.append("slot=").append(label).append(" state=").append(state)
                    .append(" bytes=").append(read).append(" kept=").append(kept)
                    .append(" dropped=").append(dropped).append('\n');
            if (!"OK".equals(state) && !(slot>0 && "MISSING".equals(state))) result=7;
        }
        create(output,"development-status.txt",status.toString());
        return result;
    }
    static int collect(File output, File apLog, File providerLog, File logcat) throws Exception {
        if (!canonical(output) || !output.isDirectory() ||
                !output.getName().matches("NEEV_BUNDLE_RESULT_[0-9]{8}_[0-9]{6}_[0-9]+"))
            throw new IOException("invalid history destination");
        Events ap = fileEvents("ap", apLog);
        Events rotated = fileEvents("provider", new File(providerLog.getPath() + ".1"));
        Events current = fileEvents("provider", providerLog);
        Events cp = logcat(logcat);
        StringBuilder powerStatus=new StringBuilder();
        boolean powerOk=true;
        // Fixed siblings of the private daemon log only; never scan arbitrary paths.
        for (String category : new String[]{"power-input","power-decision"}) {
            for (int slot=1;slot>=0;slot--) {
                String suffix=slot==0?"":".1", label=slot==0?"current":"1";
                Events events=fileEvents(category,new File(apLog.getParentFile(),category+".log"+suffix));
                create(output,category+"-"+label+".txt",body(events));
                powerStatus.append(category).append('_').append(label).append('=').append(events.state)
                        .append(" events=").append(events.lines.size()).append('\n');
                if (!"OK".equals(events.state) && !"MISSING".equals(events.state)) powerOk=false;
            }
        }
        Events provider = new Events();
        for (String line : rotated.lines) provider.append(line);
        for (String line : current.lines) provider.append(line);
        create(output, "ap-events.txt", body(ap));
        create(output, "provider-events.txt", body(provider));
        create(output, "carplay-events.txt", body(cp));
        create(output, "history-status.txt", "ap=" + ap.state + " events=" + ap.lines.size() +
                "\nprovider_rotated=" + rotated.state + " events=" + rotated.lines.size() +
                "\nprovider_current=" + current.state + " events=" + current.lines.size() +
                "\nprovider_exported=" + provider.lines.size() + "\ncarplay=" + cp.state + " events=" + cp.lines.size() + "\n"+powerStatus);
        boolean ok = "OK".equals(ap.state) && "OK".equals(current.state) && "OK".equals(cp.state) &&
                ("OK".equals(rotated.state) || "MISSING".equals(rotated.state)) && powerOk;
        return ok ? 0 : 7;
    }
}

/** Known firmware-code files only. No directory traversal, app settings or map/history export. */
final class FirmwareEvidence {
    static final String PRODUCT="NEEV.KOR.0000.V039.001.260902", PLATFORM="tcc803x";
    static final long MIB=1024L*1024L, TOTAL_LIMIT=96*MIB;
    static final class Artifact {
        final String name, format, expectedMd5;
        final long limit;
        final String[] candidates;
        Artifact(String name,String format,long limit,String md5,String... candidates) {
            this.name=name;this.format=format;this.limit=limit;this.expectedMd5=md5;this.candidates=candidates;
        }
    }
    // Native Navi loader uses these exact two code files in data/BIN before
    // /system/lib. This exception does not permit reading any other app data.
    static final Artifact[] ARTIFACTS={
        new Artifact("automotive.odex","ODEX",16*MIB,"c76b8209ef171739b177eb14e8df61dd",
                "/system/framework/automotive.odex"),
        new Artifact("automotive-service.odex","ODEX",24*MIB,"7f8058346d64d6664e69f209a553c931",
                "/system/framework/automotive-service.odex"),
        new Artifact("libExSLNavi.so","ELF",80*MIB,"",naviCandidates("libExSLNavi.so")),
        new Artifact("libExSLAndroidJNI.so","ELF",16*MIB,"",naviCandidates("libExSLAndroidJNI.so")),
        new Artifact("SystemUI.odex","ODEX",12*MIB,"",
                "/system/app/SystemUI.odex","/system/priv-app/SystemUI.odex"),
        new Artifact("HKMC_GPM.odex","ODEX",8*MIB,"c38a7fd61f90973625401501215375db",
                "/system/app/HKMC_GPM.odex"),
        new Artifact("framework.odex","ODEX",32*MIB,"ffe86fa1540c8dea3b5499b1191bb727",
                "/system/framework/framework.odex"),
        new Artifact("HKMC_Navi_ExSL.odex","ODEX",8*MIB,"8d6fa29fb222b1bb75f8fdbc3aab48eb",
                "/system/app/HKMC_Navi_ExSL.odex")
    };
    private static String[] naviCandidates(String name) {
        return new String[]{"/data/data/com.mnsoft.navi/BIN/"+name,
                "/storage/navi/navi/BIN/G40/"+name,"/storage/navi/navi/BIN/G45/"+name,
                "/storage/navi2/navi/BIN/G40/"+name,"/storage/navi2/navi/BIN/G45/"+name,
                "/system/lib/"+name};
    }
    static String property(String key) {
        try {return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,key);}
        catch(Exception unavailable){return "";}
    }
    private static boolean canonical(File f)throws IOException {return f.isAbsolute() && f.equals(f.getCanonicalFile());}
    private static int naviPid() {
        try {
            Object am=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            Object processes=Class.forName("android.app.IActivityManager").getMethod("getRunningAppProcesses").invoke(am);
            if(!(processes instanceof List))return -1;
            int selected=-1;
            for(Object info:(List<?>)processes){
                if(!"com.mnsoft.navi".equals(info.getClass().getField("processName").get(info)))continue;
                int pid=info.getClass().getField("pid").getInt(info);
                if(selected!=-1||pid<1||pid>4194304)return -1;
                selected=pid;
            }
            return selected;
        } catch(Exception unavailable){return -1;}
    }
    private static String bounded(File f,int cap)throws IOException {
        if(!canonical(f)||!f.isFile())throw new IOException("unsafe proc input");
        InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream();
        try {byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1){if(n>cap-out.size())throw new IOException("proc limit");out.write(b,0,n);}
        } finally {in.close();}
        return out.toString("UTF-8");
    }
    static Map<String,String> activeNative(File root,int pid) {
        Map<String,String> found=new HashMap<String,String>();
        if(pid<1||pid>4194304)return found;
        try {
            File proc=new File(root,"proc/"+pid);
            String cmd=bounded(new File(proc,"cmdline"),256);
            if(!cmd.startsWith("com.mnsoft.navi\0"))return found;
            String maps=bounded(new File(proc,"maps"),1024*1024);
            for(String name:new String[]{"libExSLNavi.so","libExSLAndroidJNI.so"}){
                Set<String> matches=new HashSet<String>();
                List<String> allowed=Arrays.asList(naviCandidates(name));
                for(String line:maps.split("\n")){
                    int space=line.lastIndexOf(' ');
                    if(space<0)continue;
                    String path=line.substring(space+1);
                    if(allowed.contains(path))matches.add(path);
                }
                if(matches.size()==1)found.put(name,matches.iterator().next());
            }
            // PID reuse during the read must not label an unrelated process active.
            if(!cmd.equals(bounded(new File(proc,"cmdline"),256)))found.clear();
        } catch(IOException unavailable){found.clear();}
        return found; // Only two allowlisted paths escape; never raw maps/cmdline.
    }
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();
    }
    private static boolean header(byte[] b,String format) {
        if("ELF".equals(format)) return b.length>=20 && b[0]==127 && b[1]=='E' && b[2]=='L' && b[3]=='F'
                && b[4]==1 && b[5]==1 && b[18]==40 && b[19]==0;
        if("APK".equals(format)) return b.length>=4 && b[0]=='P' && b[1]=='K' && b[2]==3 && b[3]==4;
        return b.length>=8 && b[0]=='d' && b[1]=='e' && b[2]=='y' && b[3]==10
                && b[4]=='0' && b[5]=='3' && (b[6]=='5'||b[6]=='6') && b[7]==0;
    }
    static final class Copy {
        String state="MISSING",source="none",sha="none",md5="none",selection="FIXED_FIRMWARE_PATH";
        long bytes;
    }
    static Copy copy(File root,File dest,Artifact spec,long budget) {
        return copy(root,dest,spec,budget,null);
    }
    static Copy copy(File root,File dest,Artifact spec,long budget,String activePath) {
        Copy result=new Copy();File source=null;
        try {
            List<String> candidates=new ArrayList<String>(Arrays.asList(spec.candidates));
            if("ELF".equals(spec.format))result.selection="FIXED_FALLBACK_UNCONFIRMED";
            if(activePath!=null&&candidates.remove(activePath)){
                candidates.add(0,activePath);
            } else activePath=null;
            for(String path:candidates) {
                File candidate=new File(root,path.substring(1));
                if(!canonical(candidate)){result.state="UNSAFE_PATH";result.source=path;return result;}
                if(candidate.exists()){source=candidate;result.source=path;
                    if(path.equals(activePath))result.selection="ACTIVE_PROCESS_MAP";break;}
            }
            if(source==null)return result;
            if(!source.isFile()){result.state="NOT_A_FILE";return result;}
            long size=source.length(),modified=source.lastModified();
            result.bytes=size;
            if(size<8 || size>spec.limit){result.state="SIZE_LIMIT";return result;}
            if(size>budget){result.state="TOTAL_LIMIT";return result;}
            if(!canonical(dest)||dest.exists()){result.state="OUTPUT_EXISTS";return result;}
            java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
            java.security.MessageDigest md5=java.security.MessageDigest.getInstance("MD5");
            File partial=new File(dest.getPath()+".partial");
            if(!canonical(partial)||!partial.createNewFile()){result.state="OUTPUT_EXISTS";return result;}
            InputStream in=null;
            boolean complete=false;
            try {
                in=new FileInputStream(source);
                byte[] head=new byte[20];int got=0,n;
                while(got<head.length&&(n=in.read(head,got,head.length-got))>0)got+=n;
                if(got>size){result.state="SOURCE_CHANGED";return result;}
                if(!header(Arrays.copyOf(head,got),spec.format)){result.state="FORMAT_MISMATCH";return result;}
                FileOutputStream out=new FileOutputStream(partial);
                try {
                    out.write(head,0,got);sha.update(head,0,got);md5.update(head,0,got);
                    long count=got;byte[] buffer=new byte[32768];
                    while((n=in.read(buffer))!=-1){
                        if(n>size-count){result.state="SOURCE_CHANGED";return result;}
                        out.write(buffer,0,n);sha.update(buffer,0,n);md5.update(buffer,0,n);count+=n;
                    }
                    if(count!=size||source.length()!=size||source.lastModified()!=modified||!canonical(source)){
                        result.state="SOURCE_CHANGED";return result;
                    }
                    out.getFD().sync();
                } finally {out.close();}
                if(!partial.renameTo(dest)){result.state="WRITE_FAILED";return result;}
                complete=true;result.sha=hex(sha.digest());result.md5=hex(md5.digest());result.state="COPIED";
            } finally {if(in!=null)in.close();if(!complete)partial.delete();}
        } catch(Exception failed){result.state="READ_OR_WRITE_FAILED";}
        return result;
    }
    static int collect(File output,File root,String product,String platform)throws IOException {
        if(!canonical(output)||!output.isDirectory()||!canonical(root)||
                !output.getName().matches("NEEV_BUNDLE_RESULT_[0-9]{8}_[0-9]{6}_[0-9]+"))
            throw new IOException("invalid firmware destination");
        File status=new File(output,"firmware-code-status.tsv"),code=new File(output,"firmware-code");
        if(!canonical(status)||!status.createNewFile())throw new IOException("firmware output exists");
        if(!PRODUCT.equals(product)||!PLATFORM.equals(platform)){
            Setup.write(status,"status\tTARGET_MISMATCH\n");return 7;
        }
        if(!canonical(code)||!code.mkdir())throw new IOException("firmware code destination exists");
        Map<String,String> active=activeNative(root,naviPid());
        StringBuilder report=new StringBuilder("name\tstatus\tsource\tselection\tbytes\tsha256\tmd5\treference_md5_match\n");
        long copied=0;boolean ok=true;
        for(Artifact spec:ARTIFACTS){
            Copy row=copy(root,new File(code,spec.name),spec,TOTAL_LIMIT-copied,active.get(spec.name));
            if("COPIED".equals(row.state))copied+=row.bytes;else ok=false;
            String match=spec.expectedMd5.isEmpty()?"UNKNOWN":!"COPIED".equals(row.state)?"UNAVAILABLE":
                    spec.expectedMd5.equals(row.md5)?"YES":"NO";
            report.append(spec.name).append('\t').append(row.state).append('\t').append(row.source)
                    .append('\t').append(row.selection).append('\t').append(row.bytes).append('\t').append(row.sha).append('\t').append(row.md5)
                    .append('\t').append(match).append('\n');
        }
        report.append("total_copied_bytes\t").append(copied).append("\nlimit_bytes\t").append(TOTAL_LIMIT).append('\n');
        Setup.write(status,report.toString());
        return ok?0:7;
    }
}
