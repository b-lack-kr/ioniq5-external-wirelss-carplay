// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp.setup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

public final class HistoryTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError("history check"); }
    static File file(File parent, String name, String content) throws Exception {
        File f = new File(parent, name);
        Files.write(f.toPath(), content.getBytes("UTF-8"));
        return f;
    }
    static String read(File parent, String name) throws Exception {
        return new String(Files.readAllBytes(new File(parent, name).toPath()), "UTF-8");
    }
    static File result(File root, int n) {
        File out = new File(root, "NEEV_BUNDLE_RESULT_20261105_002959_" + n);
        check(out.mkdir());
        return out;
    }
    static final String STAMP = "11-05 00:29:59.321 ";
    static final String HEADER = STAMP + "I/HMG-API (  101): [neev-usb-carplay] ";
    static void sanitized(String expected, String message) {
        String actual = History.sanitized("carplay", HEADER + message);
        if (expected == null) check(actual == null);
        else if (!(STAMP + expected).equals(actual)) throw new AssertionError("metric export: " + actual);
    }
    static String repeated(String number,int count) {
        StringBuilder out=new StringBuilder();for(int i=0;i<count;i++){if(i>0)out.append(',');out.append(number);}return out.toString();
    }
    static String[] v15Records() {
        String frame="241,3,"+repeated("0",79);
        return new String[]{
            "USB_CP_ANIMATION_POWER event=goodbye_start",
            "USB_CP_ANIMATION_POWER event=welcome_start",
            "USB_CP_POWER event=queued state=OFF source=WELCOME",
            "USB_CP_POWER event=delivered state=ON source=DIPO",
            "USB_CP_TBT_DIAG kind=route component=7 maneuver=2 fields=2097151 clear=7 raw=-1 text=0 textlen=0 unit=0 lane=1 records=3 state=1 phase=0 phasePresent=1 dropped=2147483647",
            "USB_CP_TBT_DIAG kind=maneuver component=7 maneuver=2 type=1 junction=3 exit=90 side=1 lane=0 distance=73 unit=0 angles=-2147483648,2147483647 dropped=0",
            "USB_CP_TBT_DIAG kind=lane_input component=7 index=1 source=1 exact=1 count=3 indexes=0,1,2 states=0,0,2 highlights=1000,1000,90 angleCounts=1,1,1 angles=0,0,90 frame="+frame+" dropped=0",
            "USB_CP_TBT_DIAG kind=lane_select component=7 maneuver=2 index=1 source=1 exact=1 laneAge=9223372036854775807 templatePolicy=0 guide=65535 arrow=0 frame="+frame+" dropped=0",
            "USB_CP_TBT_DIAG kind=distance_wait component=7 maneuver=2 fields=8 clear=1 raw=-1 text=0 unit=0 state=1 dropped=0",
            "USB_CP_TBT_DIAG kind=lane_write returned=1 frame="+frame+" dropped=0",
            "USB_CP_TBT_DIAG kind=pattern_write returned=1 frame=65535,0,0,0,0 dropped=0",
            "USB_CP_LOCATION observers_ready aggregate_ms=10000 numeric_speed_only=1 no_coordinates=1",
            "USB_CP_LOCATION window generation=3 micom_kmh=510 micom_observed_age_ms=0 rmc_kmh_milli=511000 rmc_fix_valid=1 rmc_speed_age_ms=-1 rmc_speed_invalid=2147483647",
            "USB_CP_LOCATION pulse_window window_ms=9223372036854775807 change_callbacks=2 invoke_ok=1 invoke_error=0 format_invalid=0 gate_allow=3 gate_disabled=0 gate_usb=0 gate_not_wireless=0 gate_inactive=1 gate_not_requested=0 gate_unreadable=0",
            "USB_CP_LOCATION rmc_ready output_only=1 vehicle_kmh=1 preserve_fix=1",
            "USB_CP_LOCATION rmc_window window_ms=10000 seen=20 replaced=19 unchanged=1 skip_gate=0 skip_source=0 skip_invalid=0 skip_no_fix=0 original_kmh_milli=511000 output_kmh_milli=510000 micom_kmh=510 micom_age_ms=0",
            "USB_CP_VIDEO observers_ready aggregate_ms=10000 detail_limit_per_window=12 no_frame_counter=1 control_is_video=0 inflight_limit=32 main_probe_pending_limit=1",
            "USB_CP_VIDEO event kind=iap_control generation=1 elapsed_ms=123 code=0",
            "USB_CP_VIDEO window generation=1 iap_control=2 surface_valid_now=1 last_event_age_ms=0 decode_age_ms=-1 screen_event_age_ms=1 control_age_ms=2 control_bytes=100 hook_path_max_ms=3 hook_path_kind=12 inflight_tracked=32 inflight_oldest_ms=-1 inflight_kind_mask=8191 inflight_overflow=0 worker_late_ms=1 main_pending_age_ms=10 main_ack_age_ms=-1 main_lag_max_ms=1 main_post_failed=0",
            "USB_CP_CAMERA event=ready legacy_fields=17 modern_fields=21",
            "USB_CP_CAMERA writer=trap source=navi owner=1 returned=1 elapsed_ms=123 values="+repeated("0",17)+" dropped=0",
            "USB_CP_CAMERA writer=trap_new source=navi owner=2 returned=0 elapsed_ms=124 values=-2147483648,"+repeated("2147483647",20)+" dropped=2147483647"
        };
    }
    static final String POWER_INPUT="AP_POWER_INPUT elapsed_ms=1000 state=OFF reason=IGN_OFF_DRIVER_DOOR decision_ns=999999999999999999 micom_raw=-2147483648 getter_off=0 preparing=0 ignition=0 ignition_live=1 ignition_age_ms=0 acc_raw=2147483647 acc_live=1 acc_age_ms=1 driver_door=1 driver_door_age_ms=0 micom_event_raw=6 polls=2 poll_failures=0 ignition_events=1 acc_events=0 door_events=1 micom_events=0 prepare_events=0 off_events=0";
    static final String POWER_DECISION="AP_POWER_DECISION paused=1 source=READER decision_ns=123 event=VALID_ON event_ns=122 reader=VALID reader_state=OFF reader_ns=123 reader_reason=IGN_OFF_DRIVER_DOOR";
    static void v15Diagnostics() {
        for(String record:v15Records())sanitized(record,record);
        for(String event:new String[]{"ready","unavailable","welcome_start","goodbye_start","read_failed"}) {
            String record="USB_CP_ANIMATION_POWER event="+event;sanitized(record,record);
        }
        for(String record:new String[]{"USB_CP_ANIMATION_POWER event=private","USB_CP_ANIMATION_POWER event=goodbye_start phone=PRIVATE",
            "USB_CP_POWER event=queued state=OFF source=PRIVATE","USB_CP_POWER event=queued state=OFF source=WELCOME source=DIPO"})sanitized(null,record);
        for(String src:new String[]{"DIPO","WELCOME"}) {
            String record="wall_ms=123 elapsed_ms=456 CP_POWER event=accepted state=OFF source="+src;
            check(History.sanitized("provider",record).equals("123 456 power event=accepted state=OFF source="+src));
        }
        check(History.sanitized("provider","wall_ms=123 elapsed_ms=456 CP_POWER event=accepted state=OFF source=PRIVATE")==null);
        check(History.sanitized("provider","wall_ms=123 elapsed_ms=456 CP_POWER event=accepted state=OFF source=WELCOME phone=PRIVATE")==null);
        String camera="USB_CP_CAMERA writer=trap source=navi owner=1 returned=1 elapsed_ms=123 values="+repeated("0",17)+" dropped=0";
        for(String bad:new String[]{camera+" phone=PRIVATE",camera.replace("source=navi","source=private"),camera.replace("owner=1","owner=4"),
                camera.replace("returned=1","returned=2"),camera.replace("elapsed_ms=123","elapsed_ms=-1"),camera.replace("dropped=0","dropped=2147483648"),
                camera.replace("values="+repeated("0",17),"values="+repeated("0",18)),camera.replace("values=0,","values=2147483648,"),
                camera.replace("values=0,","values=PRIVATE,"),camera+" owner=1"})sanitized(null,bad);
        String maximum="USB_CP_TBT_DIAG kind=maneuver angles="+repeated("-2147483648",128);
        sanitized(maximum,maximum);
        sanitized(null,maximum+",0");
        sanitized(null,"USB_CP_TBT_DIAG kind=lane_write frame="+repeated("0",82));
        sanitized(null,"USB_CP_TBT_DIAG kind=pattern_write frame="+repeated("0",6));
        for(String bad:new String[]{"component=2147483648","component=-2147483649","angles=0,","angles=PRIVATE","angles=0,,1","angles=0,1.5",
                "component=1 component=2","kind=route","road=PRIVATE","latitude=37.123","SSID=Hyundai_123456","phone=123456789","PRIVATE",
                "fields=2097152","clear=8","text=2","textlen=257","dropped=-1","dropped=2147483648","laneAge=9223372036854775808"}) {
            String kind=bad.startsWith("angles=")?"maneuver":bad.startsWith("laneAge=")?"lane_select":"route";
            sanitized(null,"USB_CP_TBT_DIAG kind="+kind+" "+bad);
        }
        for(String bad:new String[]{"frame=-1","frame=256","returned=2"})sanitized(null,"USB_CP_TBT_DIAG kind=lane_write "+bad);
        for(String bad:new String[]{"indexes="+repeated("0",9),"states=3","angleCounts=17","count=9","source=2","exact=2"})
            sanitized(null,"USB_CP_TBT_DIAG kind=lane_input "+bad);
        for(String bad:new String[]{"gate_allow=-1","invoke_ok=2147483648","window_ms=9223372036854775808","invoke_ok=1 invoke_ok=2","SSID=PRIVATE","bad=123","gate_allow=PRIVATE"})
            sanitized(null,"USB_CP_LOCATION pulse_window "+bad);
        for(String bad:new String[]{"seen=-1","replaced=2147483648","window_ms=9223372036854775808","seen=1 seen=2","SSID=PRIVATE","NMEA=PRIVATE","original_kmh_milli=PRIVATE",
                "original_kmh_milli=-2","output_kmh_milli=511001","micom_kmh=511","micom_age_ms=-2"})sanitized(null,"USB_CP_LOCATION rmc_window "+bad);
        sanitized(null,"USB_CP_LOCATION rmc_ready output_only=1 vehicle_kmh=1 preserve_fix=1 NMEA=PRIVATE");
        for(String bad:new String[]{"micom_kmh=511","rmc_kmh_milli=511001","rmc_fix_valid=2","rmc_speed_invalid=-1","rmc_speed_invalid=2147483648","rmc_speed_age_ms=-2","micom_kmh=PRIVATE"})
            sanitized(null,"USB_CP_LOCATION window "+bad);
        for(String bad:new String[]{"surface_valid_now=2","hook_path_kind=13","inflight_kind_mask=8192","inflight_tracked=33","control_bytes=-1","main_ack_age_ms=-2","main_post_failed=PRIVATE","control_bytes=1 control_bytes=2"})
            sanitized(null,"USB_CP_VIDEO window "+bad);
        sanitized("USB_CP_LOCATION window micom_kmh=10","USB_CP_LOCATION window micom_kmh=10 latitude=PRIVATE longitude=PRIVATE NMEA=PRIVATE");
        sanitized("USB_CP_VIDEO window control_bytes=10","USB_CP_VIDEO window control_bytes=10 URL=PRIVATE Surface=PRIVATE");
        for(String line:new String[]{POWER_INPUT,POWER_DECISION}) {
            check(line.equals(History.sanitized("ap",line)));
            check(History.sanitized("ap",line+" SSID=PRIVATE")==null);
            check(History.sanitized("ap",line+" decision_ns=1")==null);
        }
        for(String bad:new String[]{"state=PRIVATE","reason=PRIVATE","decision_ns=1000000000000000000","decision_ns=01","decision_ns=-1","micom_raw=2147483648","polls=-1","ignition=2","driver_door_age_ms=-2","polls=PRIVATE","latitude=37.0","phone=123"})
            check(History.sanitized("ap","AP_POWER_INPUT "+bad)==null);
        for(String bad:new String[]{"paused=2","source=PRIVATE","event=PRIVATE","reader=PRIVATE","reader_reason=PRIVATE","reader_state=PRIVATE","reader_ns=1000000000000000000","reader_ns=01","reader_ns=-1"})
            check(History.sanitized("ap","AP_POWER_DECISION "+bad)==null);
        for(int version=0;version<=15;version++)for(String phase:new String[]{"ENTRY","PRIVATE_LOG_READY","ANDROID_READY"}) {
            String line="AP_STARTUP version=1.0."+version+" phase="+phase;check(line.equals(History.sanitized("boot",line)));
        }
        for(String version:new String[]{"1.0.16","1.0.100","1.0.01","1.1.0","2.0.0","PRIVATE"})
            check(History.sanitized("boot","AP_STARTUP version="+version+" phase=ENTRY")==null);
        check(History.sanitized("boot","AP_STARTUP version=1.0.15 phase=ENTRY SSID=PRIVATE")==null);
        StringBuilder huge=new StringBuilder("USB_CP_TBT_DIAG kind=route component=");while(huge.length()<4097)huge.append('1');sanitized(null,huge.toString());
    }
    static void diagnosticRecords() {
        for (String channel : new String[]{"lane","pattern","queue","gate","callback","raw-normalize","lane-select","junction-select","submit","start"}) {
            String record = "USB_CP_TBT_AUX_FAILED channel=" + channel;
            sanitized(record, record);
            sanitized(null, record + " road=PRIVATE");
        }
        sanitized(null, "USB_CP_TBT_AUX_FAILED channel=PRIVATE");
        for (String event : new String[]{"ready","decoded","delivered","overflow","decode","decode-failed","delivery-failed","queue-failed","closed"}) {
            String record = "USB_CP_RAW_LANES " + event + " count=2147483647";
            sanitized(record, record);
            sanitized(null, record + " road=PRIVATE");
            sanitized(null, record.replace("2147483647", "2147483648"));
        }
        sanitized(null, "USB_CP_RAW_LANES PRIVATE count=1");
        sanitized(null, "USB_CP_RAW_LANES ready count=-1");
        for (String source : new String[]{"raw","oem"}) {
            String record = "USB_CP_TBT_LANE_INPUT source="+source+" component=7 index=-1 lanes=8 exact=0";
            sanitized(record,record); sanitized(null,record+" SSID=PRIVATE");
            sanitized(null,record.replace("component=7","component=2147483648"));
            sanitized(null,record.replace("lanes=8","lanes=9"));
        }
        for (String source : new String[]{"raw","oem","none"}) {
            String record = "USB_CP_TBT_AUX_SELECT component=7 maneuver=1 index=-1 source="+source+" exact=1 guide=65535";
            sanitized(record,record); sanitized(null,record+" road=PRIVATE");
            sanitized(null,record.replace("index=-1","index=-2147483649"));
            sanitized(null,record.replace("guide=65535","guide=PRIVATE"));
        }
        sanitized("USB_CP_TBT_PROGRESS event=native_progress native=100",
                "USB_CP_TBT_PROGRESS event=native_progress native=100");
        sanitized(null,"USB_CP_TBT_PROGRESS event=native_progress native=100 road=PRIVATE");
        sanitized(null,"USB_CP_TBT_PROGRESS event=native_progress native=2147483648");
        for (String event:new String[]{"native_overlay","fine_progress_submitted","native_bar_restored"}) {
            String frame="USB_CP_TBT_PROGRESS event="+event+" fine=0 native=255 snapshot_age_ms=200";
            sanitized(frame,frame);
            sanitized(null,frame+" road=PRIVATE");
            sanitized(null,frame.replace("native=255","native=2147483648"));
            sanitized(null,frame.replace("snapshot_age_ms=200","snapshot_age_ms=9223372036854775808"));
        }
        sanitized("USB_CP_TBT_PROGRESS event=native_bar_restored fine=-1 native=0 snapshot_age_ms=0",
                "USB_CP_TBT_PROGRESS event=native_bar_restored fine=-1 native=0 snapshot_age_ms=0");
        sanitized(null,"USB_CP_TBT_PROGRESS event=native_overlay fine=101 native=0 snapshot_age_ms=0");
        for(String event:new String[]{"snapshot_unavailable","snapshot_stale snapshot_age_ms=2001","unavailable",
                "ready snapshot_ttl_ms=2000 replay_interval_ms=200"}) {
            sanitized("USB_CP_TBT_PROGRESS event="+event,"USB_CP_TBT_PROGRESS event="+event);
            sanitized(null,"USB_CP_TBT_PROGRESS event="+event+" SSID=PRIVATE");
        }
        for(String event:new String[]{"PUBLISH_RETRY","PUBLISH_RECOVERED","READ_RETRY","READ_RECOVERED"})
            sanitized("USB_CP_TBT_IPC_"+event,"USB_CP_TBT_IPC_"+event);
        for(String source:new String[]{"raw","display","none"}) {
            String render="USB_CP_TBT_RENDER source="+source+" metersMilli=90000 type=13 direction=90 bar=10 distanceMilli=90000 unit=0 valid=1";
            sanitized(render,render);
            for (String angle:new String[]{"90.0","22.5","337.5"}) {
                String fractional=render.replace("direction=90","direction="+angle);
                sanitized(fractional,fractional);
            }
            for (String angle:new String[]{"360","-1","NaN","22.75","Infinity"})
                sanitized(null,render.replace("direction=90","direction="+angle));
            sanitized(null,render+" road=PRIVATE");
            sanitized(null,render.replace("metersMilli=90000","metersMilli=9223372036854775808"));
            sanitized(null,render.replace("bar=10","bar=10 bar=20"));
            sanitized(null,render.replace("distanceMilli=90000","distanceMilli=NaN"));
            sanitized(null,render.replace("unit=0","unit=8"));
            sanitized(null,render.replace("source="+source,"source=PRIVATE"));
        }
        sanitized("USB_CP_LANE_RAW errors=3","USB_CP_LANE_RAW errors=3");
        sanitized(null,"USB_CP_LANE_RAW errors=2147483648");
        sanitized(null,"USB_CP_LANE_RAW errors=3 road=PRIVATE");
        for (String event:new String[]{"ready","unavailable","applied","restored","waiting_snapshot","camera_owned","expired","failed"}) {
            String approach="USB_CP_APPROACH_BAR event="+event;
            sanitized(approach,approach);
            sanitized(null,approach+" road=PRIVATE");
        }
        sanitized(null,"USB_CP_APPROACH_BAR event=UNKNOWN");
        for (String event:new String[]{"ready","unavailable","write_failed"}) {
            String junction="USB_CP_TBT_JUNCTION event="+event;
            sanitized(junction,junction);
            sanitized(null,junction+" road=PRIVATE");
        }
        for (String event:new String[]{"write","clear"}) {
            String junction="USB_CP_TBT_JUNCTION event="+event+" guide=19 arrow=20 component=7 maneuver=2";
            sanitized(junction,junction);
            sanitized(null,junction+" road=PRIVATE");
            sanitized(null,junction.replace("component=7","component=2147483648"));
        }
        sanitized("USB_CP_TBT_DISTANCE raw=false text=true displayUnit=1 usable=true",
                "USB_CP_TBT_DISTANCE raw=false text=true displayUnit=1 usable=true road=PRIVATE");
        sanitized(null,"USB_CP_TBT_DISTANCE raw=PRIVATE text=true");
        sanitized(null,"USB_CP_TBT_DISTANCE raw=true raw=false");
        sanitized("USB_CP_TBT_DISPLAY first_turn icon=13","USB_CP_TBT_DISPLAY first_turn icon=13 road=PRIVATE");
        sanitized(null,"USB_CP_TBT_DISPLAY private icon=13");
        String usbHeader="wall_ms=123 elapsed_ms=456 CP_USB_ID ";
        for(String method:new String[]{"none","ancestry","single_bound_usb"}) {
            String details="event=ready method="+method+" id=2357:0138";
            check(("123 456 usb "+details).equals(History.sanitized("provider",usbHeader+details)));
            check(History.sanitized("provider",usbHeader+details+" serial=PRIVATE")==null);
        }
        check("123 456 usb reason=iface_missing id=none dropped=2".equals(History.sanitized("provider",usbHeader+"reason=iface_missing id=none dropped=2")));
        for(String details:new String[]{"reason=PRIVATE id=none","event=ready method=PRIVATE id=2357:0138",
                "event=ready method=ancestry id=00:11:22:33:44:55","event=seen id=private"})
            check(History.sanitized("provider",usbHeader+details)==null);
        String observer="wall_ms=123 elapsed_ms=456 CP_OBSERVER event=unavailable phase=usb_identity ";
        check("123 456 observer unavailable usb_identity kind=unsupported_usb_identity ready=0 timeout=0 identity=1 failures=2".equals(
                History.sanitized("provider",observer+"kind=unsupported_usb_identity ready=0 timeout=0 identity=1 failures=2 SSID=PRIVATE")));
        check("123 456 observer unavailable usb_identity kind=unknown".equals(
                History.sanitized("provider",observer+"kind=SECRET SSID=PRIVATE")));
        for(String bad:new String[]{"ready=2","timeout=PRIVATE","identity=0 identity=1","failures=9223372036854775808","kind=timeout kind=timeout"})
            check(History.sanitized("provider",observer+bad)==null);
        for (String event : new String[]{"pulse_ready min_gap_ms=0","pulse_ready min_gap_ms=250","pulse_unavailable","pulse_failed"})
            sanitized("USB_CP_LOCATION "+event,"USB_CP_LOCATION "+event);
        sanitized(null,"USB_CP_LOCATION pulse_ready min_gap_ms=PRIVATE");
        sanitized(null,"USB_CP_LOCATION pulse_failed SSID=PRIVATE");
        String[] nativeFrames={
            "writer=update_tbt_info values=13,0,-90.0,4.57E2,0,1,0,100,0",
            "writer=update_tbt_sub_info values=1.0,1,2,3,4,5,6,1.0E-3,8",
            "writer=update_tbt_sub_info_b values=1.0,1,2,3,4,5,6,-1.0E-3,8,9",
            "writer=update_tbt_sub_info_2c values=0,1,2,3,4,5,6,7,8",
            "writer=update_remain_dis values=0,1234,2,3"};
        for(String frame:nativeFrames) {
            sanitized("USB_CP_NATIVE_TBT "+frame,"USB_CP_NATIVE_TBT "+frame);
            sanitized(null,"USB_CP_NATIVE_TBT "+frame+" road=PRIVATE");
        }
        String phaseFrame = "USB_CP_TBT_PHASE route=1 phase=3 present=1 component=7 maneuver=2 meters=45 dropped=0";
        sanitized(phaseFrame, phaseFrame);
        sanitized(null, phaseFrame + " road=PRIVATE");
        sanitized(null, phaseFrame.replace("phase=3", "phase=2147483648"));
        sanitized(null, phaseFrame.replace("present=1", "present=2"));
        sanitized(null, phaseFrame.replace("dropped=0", "dropped=-1"));
        sanitized(null, phaseFrame.replace("meters=45", "meters=PRIVATE"));
        sanitized(null, phaseFrame.replace("component=7", "component=7 component=8"));
        sanitized(null, phaseFrame + "\nSSID=PRIVATE");
        String absentPhase = "USB_CP_TBT_PHASE route=-1 phase=1 present=0 component=7 maneuver=-1 meters=-1 dropped=12";
        sanitized(absentPhase, absentPhase);
        for (String frame : new String[]{"event=ready", "event=unavailable", "writer=trap values=0",
                "writer=trap_new values=-1", "writer=clu14 values=1,-2,65535",
                "writer=clu14_new values=-2147483648,2147483647,0,-1,15"}) {
            sanitized("USB_CP_NATIVE_AR " + frame, "USB_CP_NATIVE_AR " + frame);
            sanitized(null, "USB_CP_NATIVE_AR " + frame + " road=PRIVATE");
        }
        for (String frame : new String[]{"event=private", "writer=private values=0",
                "writer=trap values=0,1", "writer=clu14 values=1,2", "writer=clu14_new values=1,2,3,4,5,6",
                "writer=clu14_new values=1,2,3,4,", "writer=trap values=2147483648", "writer=trap values=-2147483649",
                "writer=trap values=PRIVATE", "writer=trap values=1.5", "writer=trap values=1e3",
                "writer=trap values=NaN", "writer=trap values=0 ", "writer=trap values=0\nPRIVATE"})
            sanitized(null, "USB_CP_NATIVE_AR " + frame);
        for(String bad:new String[]{"NaN","Infinity","1e999","PRIVATE","37.1,127.2"})
            sanitized(null,"USB_CP_NATIVE_TBT writer=update_tbt_info values=13,0,"+bad+",1.0,0,1,0,100,0");
        sanitized(null,"USB_CP_NATIVE_TBT writer=private values=0,1,2,3");
        sanitized(null,"USB_CP_NATIVE_TBT writer=update_remain_dis values=0,2147483648,2,3");
        sanitized(null,"USB_CP_NATIVE_TBT writer=update_remain_dis values=0,1,2,3,4");
        for(String phase:new String[]{"ACTIVE","STOPPING"}) {
            String power="AP_USB_POWER phase="+phase+" control=AUTO runtime=ACTIVE authorized=1 wakeup=DISABLED vbus_handler=FOUND physical_switch=UNVERIFIED";
            check(power.equals(History.sanitized("ap",power)));
            check(History.sanitized("ap",power+" serial=PRIVATE")==null);
            check(History.sanitized("ap",power.replace("AUTO","PRIVATE"))==null);
        }
        check("AP_USB_POWER unavailable=1".equals(History.sanitized("ap","AP_USB_POWER unavailable=1")));
        check(History.sanitized("ap","AP_USB_POWER unavailable=PRIVATE")==null);
        for (String event : "wrong_uid ready hook_unavailable unavailable obsolete_transition wireless_ap_changed down available".split(" "))
            sanitized("USB_AA_EXTERNAL event="+event,"USB_AA_EXTERNAL event="+event);
        sanitized(null,"USB_AA_EXTERNAL event=available ssid=PRIVATE");
        sanitized(null,"USB_AA_EXTERNAL event=PRIVATE");
        sanitized("USB_CP_AA stock_tbt=1","USB_CP_AA stock_tbt=1");
        sanitized("USB_CP_AA tbt_ready standard=0 queue_limit=128 native_recipe_requested=1",
                "USB_CP_AA tbt_ready standard=0 queue_limit=128 native_recipe_requested=1");
        sanitized("USB_CP_AA queue_reset dropped=128","USB_CP_AA queue_reset dropped=128 device=PRIVATE");
        sanitized(null,"USB_CP_AA tbt_ready standard=PRIVATE queue_limit=128 native_recipe_requested=1");
        sanitized(null,"USB_CP_AA queue_reset dropped=1 dropped=2");
        for(String record:new String[]{"status 1","turn ev=4 side=1 angle=90","lanes count=8",
                "quiet 1000 ms, ended by distance","phone is talking again: guidance back",
                "guide lost: nothing from the phone for 120000 ms","quiet for 30000 ms, the route is still live",
                "flip 2: ev=4 side=2 is back after another turn within 10000 ms",
                "state ev=4 side=2 m=-1 rerouting=false focus=phone calls turn/dist/status/lanes/dump=2/3/4/5/6 quiet=100 maxGap=200"}) {
            String line="USB_CP_AA_TRACE "+record;
            sanitized(line,line);
            sanitized(null,line+" road=PRIVATE");
        }
        sanitized(null,"USB_CP_AA_TRACE lanes count=9");
        sanitized(null,"USB_CP_AA_TRACE turn ev=4 side=PRIVATE angle=90");
        sanitized("USB_CP_VIDEO observers_ready aggregate_ms=10000 detail_limit_per_window=12 no_frame_counter=1",
                "USB_CP_VIDEO observers_ready aggregate_ms=10000 detail_limit_per_window=12 no_frame_counter=1");
        sanitized("USB_CP_VIDEO event kind=show_display generation=2 code=0 surface_valid=1",
                "USB_CP_VIDEO event generation=2 kind=show_display code=0 surface_valid=1 Surface=PRIVATE");
        sanitized("USB_CP_VIDEO window generation=2 screen_timeout=3 resumed=1 last_decode=-1 width=1280",
                "USB_CP_VIDEO window generation=2 screen_timeout=3 resumed=1 last_decode=-1 width=1280 url=PRIVATE");
        sanitized(null,"USB_CP_VIDEO event kind=private generation=2");
        sanitized(null,"USB_CP_VIDEO event kind=show_display kind=hide_display generation=2");
        sanitized(null,"USB_CP_VIDEO event kind=show_display width=PRIVATE");
        sanitized(null,"USB_CP_VIDEO window width=1 width=2");
        sanitized("USB_CP_VIDEO recovery_ready cooldown_ms=2000 periodic=0 native_serial=1",
                "USB_CP_VIDEO recovery_ready cooldown_ms=2000 periodic=0 native_serial=1 private=PRIVATE");
        sanitized("USB_CP_VIDEO recovery_request generation=2 elapsed_ms=5000 reason=1 code=3",
                "USB_CP_VIDEO recovery_request generation=2 elapsed_ms=5000 reason=1 code=3 phone=PRIVATE");
        sanitized("USB_CP_VIDEO recovery_result generation=2 elapsed_ms=5010 duration_ms=10 native_result=0 exception=0 current=1",
                "USB_CP_VIDEO recovery_result generation=2 elapsed_ms=5010 duration_ms=10 native_result=0 exception=0 current=1 url=PRIVATE");
        sanitized(null,"USB_CP_VIDEO recovery_result native_result=0 native_result=1");
        sanitized(null,"USB_CP_VIDEO recovery_request reason=PRIVATE");
        for(String record:new String[]{"rebind_ready cooldown_ms=10000 grace_ms=250 stop_window_ms=2000 periodic=0",
                "rebind_request generation=1 elapsed_ms=50887 epoch=3",
                "rebind_result generation=1 elapsed_ms=50895 duration_ms=8 hide_result=0 show_result=0 exception=0 current=1"}) {
            sanitized("USB_CP_VIDEO "+record,"USB_CP_VIDEO "+record+" surface=PRIVATE");
        }
        sanitized(null,"USB_CP_VIDEO rebind_result show_result=PRIVATE");
        sanitized(null,"USB_CP_VIDEO rebind_result hide_result=0 hide_result=1");

        for (String mode : new String[]{"ON","OFF","INVALID"}) {
            check(("123 456 config external_dongle="+mode).equals(History.sanitized("provider",
                    "wall_ms=123 elapsed_ms=456 CP_CONFIG external_dongle="+mode)));
        }
        check(History.sanitized("provider","wall_ms=123 elapsed_ms=456 CP_CONFIG external_dongle=PRIVATE")==null);
        check("EXTERNAL_DONGLE_OFF".equals(History.sanitized("ap","AP_EVENT state=EXTERNAL_DONGLE_OFF")));
        check("EXTERNAL_CONFIG_INVALID".equals(History.sanitized("ap","AP_EVENT state=EXTERNAL_CONFIG_INVALID")));
        sanitized("USB_CP_DEV_LOG start wall_ms=123 elapsed_ms=456", "USB_CP_DEV_LOG start wall_ms=123 elapsed_ms=456 boot=PRIVATE");
        for (String event : new String[]{"queued", "delivered", "superseded", "retry"}) {
            for (String state : new String[]{"ON", "OFF"}) {
                String record = "USB_CP_POWER event=" + event + " state=" + state;
                sanitized(record, record);
            }
        }
        sanitized(null, "USB_CP_POWER event=delivered state=UNKNOWN");
        sanitized(null, "USB_CP_POWER event=private state=OFF");
        sanitized(null, "USB_CP_POWER event=delivered state=OFF boot=PRIVATE");
        for (String event : new String[]{"restored", "accepted", "duplicate", "rejected", "failed"}) {
            for (String state : new String[]{"ON", "OFF", "UNKNOWN"}) {
                String record = "wall_ms=123 elapsed_ms=456 CP_POWER event=" + event + " state=" + state;
                check(("123 456 power event=" + event + " state=" + state).equals(History.sanitized("provider", record)));
                check(("123 456 power event=" + event + " state=" + state).equals(History.sanitized("provider", record + " dropped=2")));
            }
        }
        check(History.sanitized("provider", "wall_ms=123 elapsed_ms=456 CP_POWER event=accepted state=OFF boot=PRIVATE") == null);
        check(History.sanitized("provider", "wall_ms=123 elapsed_ms=456 CP_POWER event=private state=OFF") == null);
        sanitized("USB_CP_LOCATION observers_ready aggregate_ms=10000 no_sensor_values=1",
                "USB_CP_LOCATION observers_ready aggregate_ms=10000 no_sensor_values=1 SSID=Hyundai_123456");
        sanitized("USB_CP_LOCATION request_started generation=2", "USB_CP_LOCATION request_started generation=2 VIN=PRIVATE");
        sanitized("USB_CP_LOCATION window generation=2 window_ms=10000 speed_in=50 speed_in_max_gap_ms=200 gga_in=10 " +
                "gga_in_max_gap_ms=1000 rmc_in=10 rmc_in_max_gap_ms=1000 gga_out=10 gga_out_max_gap_ms=1000 " +
                "rmc_out=10 rmc_out_max_gap_ms=1000 pascd_out=10 pascd_out_max_gap_ms=1000 send_ok=30 send_false=0 " +
                "send_error=0 speed_callback_age_ms=20 gga_callback_age_ms=50 rmc_callback_age_ms=50 " +
                "max_speed_age_at_pascd_ms=180 max_gga_age_at_send_ms=4 max_rmc_age_at_send_ms=4 max_binder_ms=2",
                "USB_CP_LOCATION window generation=2 window_ms=10000 speed_in=50 speed_in_max_gap_ms=200 gga_in=10 " +
                "gga_in_max_gap_ms=1000 rmc_in=10 rmc_in_max_gap_ms=1000 gga_out=10 gga_out_max_gap_ms=1000 " +
                "rmc_out=10 rmc_out_max_gap_ms=1000 pascd_out=10 pascd_out_max_gap_ms=1000 send_ok=30 send_false=0 " +
                "send_error=0 speed_callback_age_ms=20 gga_callback_age_ms=50 rmc_callback_age_ms=50 " +
                "max_speed_age_at_pascd_ms=180 max_gga_age_at_send_ms=4 max_rmc_age_at_send_ms=4 max_binder_ms=2 " +
                "latitude=37.123456 longitude=127.123456 speed=80.5 nmea=$GPGGA,PRIVATE vehicle_id=123456789");
        sanitized("USB_CP_LOCATION stopped generation=2 max_binder_ms=-1", "USB_CP_LOCATION stopped generation=2 max_binder_ms=-1");
        sanitized("USB_CP_AUDIO observers_ready detail_limit_per_session=24 aggregate_ms=10000",
                "USB_CP_AUDIO observers_ready detail_limit_per_session=24 aggregate_ms=10000");
        sanitized("USB_CP_AUDIO observers_unavailable", "USB_CP_AUDIO observers_unavailable reason=PRIVATE");
        sanitized("USB_CP_AUDIO_ROUTE ready", "USB_CP_AUDIO_ROUTE ready SSID=PRIVATE");
        sanitized("USB_CP_AUDIO_ROUTE unavailable", "USB_CP_AUDIO_ROUTE unavailable reason=PRIVATE");
        sanitized("USB_CP_AUDIO_ROUTE alt_switch generation=2 from=19 to=13 route=13 status=1",
                "USB_CP_AUDIO_ROUTE alt_switch generation=2 from=19 to=13 route=13 status=1 title=PRIVATE");
        sanitized("USB_CP_AUDIO_ROUTE music_retryable generation=2",
                "USB_CP_AUDIO_ROUTE music_retryable generation=2 VIN=PRIVATE");
        sanitized(null, "USB_CP_AUDIO_ROUTE alt_switch generation=2 from=PRIVATE");
        sanitized(null, "USB_CP_AUDIO_ROUTE alt_switch status=1 status=2");
        sanitized(null, "USB_CP_AUDIO_ROUTE private_event generation=1");
        sanitized("USB_CP_AUDIO session_started generation=3 transport=1", "USB_CP_AUDIO session_started generation=3 transport=1");
        sanitized("USB_CP_AUDIO event generation=3 elapsed_ms=1234 native_start stream=100 type=1 rate=48000",
                "USB_CP_AUDIO event generation=3 elapsed_ms=1234 native_start stream=100 type=1 rate=48000 title=PRIVATE");
        sanitized("USB_CP_AUDIO event generation=3 elapsed_ms=1234 native_stop stream=100",
                "USB_CP_AUDIO event generation=3 elapsed_ms=1234 native_stop stream=100");
        sanitized("USB_CP_AUDIO event generation=3 elapsed_ms=1234 stock_start stream=Main type=Media rate=48000 exception=false",
                "USB_CP_AUDIO event generation=3 elapsed_ms=1234 stock_start stream=Main type=Media rate=48000 exception=false artist=PRIVATE");
        sanitized("USB_CP_AUDIO event generation=3 elapsed_ms=1234 stock_stop stream=Main exception=false",
                "USB_CP_AUDIO event generation=3 elapsed_ms=1234 stock_stop stream=Main exception=false");
        for (String kind : new String[]{"duck","unduck"})
            sanitized("USB_CP_AUDIO event generation=3 elapsed_ms=1234 " + kind + " exception=false",
                    "USB_CP_AUDIO event generation=3 elapsed_ms=1234 " + kind + " exception=false");
        for (String kind : new String[]{"micom_start","micom_end","micom_music"})
            sanitized("USB_CP_AUDIO event generation=3 elapsed_ms=1234 " + kind + " requested=13 accepted=false selected_stream=-1 exception=true",
                    "USB_CP_AUDIO event generation=3 elapsed_ms=1234 " + kind + " requested=13 accepted=false selected_stream=-1 exception=true");
        sanitized("USB_CP_AUDIO event generation=3 elapsed_ms=1234 micom_status code=4",
                "USB_CP_AUDIO event generation=3 elapsed_ms=1234 micom_status code=4");
        sanitized("USB_CP_AUDIO event generation=3 elapsed_ms=1234 standard_volume_key direction=up",
                "USB_CP_AUDIO event generation=3 elapsed_ms=1234 standard_volume_key direction=up");
        String audio = "USB_CP_AUDIO window generation=3 window_ms=10000 native_start=1 native_stop=0 stock_start=1 stock_stop=0 " +
                "duck=2 unduck=2 micom_start=1 micom_end=0 micom_music=1 micom_status=4 volume_key=1 failure=0 " +
                "detail_suppressed=5 last_native_stream=100 last_native_type=1 last_micom_stream=13";
        sanitized(audio,audio + " title=PRIVATE MAC=00:11:22:33:44:55");
        sanitized("USB_CP_TBT_LANES input=3 output=3 lane0_status=2 lane0_angles=90,180 lane0_highlight=0 lane1=unknown lane2=empty",
                "USB_CP_TBT_LANES input=3 output=3 lane0=1:[2]2:[90,180]3:[0] lane1=unknown lane2= SSID=Hyundai_123456");
        sanitized("USB_CP_TBT_LANES input=1 output=1 lane0_angles=0,45,90,180 lane0_angles_more=1 lane0_highlight=empty",
                "USB_CP_TBT_LANES input=1 output=1 lane0=2:[0,45,90,180,...]3:[]");
        sanitized("USB_CP_TBT_LANES input=0 output=0", "USB_CP_TBT_LANES input=0 output=0");
        for (String bad : new String[]{"NaN","Infinity","1e4","37.12345","9223372036854775808","1PRIVATE","1\tSSID=private"})
            sanitized(null,"USB_CP_LOCATION window generation=1 window_ms=" + bad);
        sanitized(null,"USB_CP_LOCATION window generation=1 generation=2");
        sanitized(null,"USB_CP_AUDIO event generation=1 elapsed_ms=2 stock_start stream=PRIVATE type=Media");
        sanitized(null,"USB_CP_AUDIO event generation=1 elapsed_ms=2 stock_start stream=Main type=PRIVATE");
        sanitized(null,"USB_CP_AUDIO event generation=1 elapsed_ms=2 duck exception=PRIVATE");
        sanitized(null,"USB_CP_AUDIO event generation=1 elapsed_ms=2 standard_volume_key direction=PRIVATE");
        sanitized(null,"USB_CP_TBT_LANES input=1 output=1 lane0=1:[PRIVATE]");
        sanitized(null,"USB_CP_TBT_LANES input=1 output=1 lane0=2:[2147483648]");
        sanitized(null,"USB_CP_TBT_LANES input=1 output=1 lane0=2:[0]PRIVATE");
        sanitized(null,"USB_CP_TBT_LANES input=1 output=1 lane0=2:[0]2:[90]");
        sanitized(null,"USB_CP_TBT_LANES input=1 output=1 lane0=2:[0] lane0=2:[90]");
        sanitized(null,"USB_CP_LOCATION window generation=1\nSSID=PRIVATE");
        check(History.sanitized("ap","AP_EVENT state=HU_POWER_OFF").equals("HU_POWER_OFF"));
        check(History.sanitized("ap","AP_POWER_OBSERVED state=OFF:6").equals("AP_POWER_OBSERVED state=OFF raw_status=6"));
        check(History.sanitized("ap","AP_POWER_OBSERVED state=ON:unknown").equals("AP_POWER_OBSERVED state=ON raw_status=unknown"));
        check(History.sanitized("ap","AP_POWER_OBSERVED state=UNKNOWN:unknown").equals("AP_POWER_OBSERVED state=UNKNOWN raw_status=unknown"));
        check(History.sanitized("ap","AP_POWER_OBSERVED state=OFF:6:IGN_OFF_DRIVER_DOOR:123456789012").equals("AP_POWER_OBSERVED state=OFF raw_status=6 reason=IGN_OFF_DRIVER_DOOR decision_ns=123456789012"));
        check(History.sanitized("ap","AP_POWER_OBSERVED state=ON:0:INITIAL_READING:0").equals("AP_POWER_OBSERVED state=ON raw_status=0 reason=INITIAL_READING decision_ns=0"));
        check(History.sanitized("ap","AP_POWER_OBSERVED state=OFF:unknown:RESTORED:123").equals("AP_POWER_OBSERVED state=OFF raw_status=unknown reason=RESTORED decision_ns=123"));
        check(History.sanitized("ap","AP_POWER_READER bcm_listener=READY").equals("AP_POWER_READER bcm_listener=READY"));
        check(History.sanitized("ap","AP_POWER_READER micom_listener=UNAVAILABLE type=NoSuchMethodException").equals("AP_POWER_READER micom_listener=UNAVAILABLE type=NoSuchMethodException"));
        for (String bad : new String[]{"AP_POWER_OBSERVED state=OFF:6:PRIVATE:12", "AP_POWER_OBSERVED state=OFF:6:SYSTEM_OFF:123 extra=PRIVATE", "AP_POWER_OBSERVED state=OFF:6:SYSTEM_OFF:999999999999999999999", "AP_POWER_READER bcm_listener=READY SSID=PRIVATE", "AP_POWER_READER micom_listener=UNAVAILABLE type=PRIVATE.payload"})
            check(History.sanitized("ap",bad)==null);
        check(History.sanitized("ap","AP_LINK_DOWN_FAILED iface=rtwlan0").equals("AP_LINK_DOWN_FAILED"));
        for (String bad : new String[]{"AP_POWER_OBSERVED state=PRIVATE:6", "AP_POWER_OBSERVED state=ON:PRIVATE",
                "AP_POWER_OBSERVED state=OFF:6 SSID=PRIVATE", "AP_POWER_OBSERVED state=UNKNOWN:1",
                "AP_LINK_DOWN_FAILED iface=PRIVATE", "AP_LINK_DOWN_FAILED iface=rtwlan0 PRIVATE"})
            check(History.sanitized("ap",bad) == null);
        check(History.sanitized("carplay",HEADER.replace("neev-usb-carplay","other") + audio) == null);
    }
    static void manualDiagnostics() {
        for(String event:new String[]{"accepted","preparing_ap","preparing_iap","server_ready"}) {
            String line="USB_CP_MANUAL event="+event;sanitized(line,line);
            sanitized(null,line+" phone=PRIVATE");
        }
        for(int status=0;status<=10;status++) {
            String line="USB_CP_MANUAL event=finished status="+status;sanitized(line,line);
        }
        sanitized("USB_CP_MANUAL event=finished status=1","USB_CP_MANUAL event=finished status=01");
        for(String stage:new String[]{"server_failed","request_failed","gate_failed","dispatch_failed"})
            for(String kind:new String[]{"linkage","io","security","interrupted","runtime","other"}) {
                String line="USB_CP_MANUAL event="+stage+" kind="+kind;sanitized(line,line);
                sanitized(null,line+" exception=PRIVATE");
            }
        sanitized("USB_CP_APP_BOOTSTRAP phase=entered","USB_CP_APP_BOOTSTRAP phase=entered package=private.package.name");
        sanitized("USB_CP_APP_BOOTSTRAP phase=entered","USB_CP_APP_BOOTSTRAP phase=entered");
        for(String phase:new String[]{"oem_power_hook_failed","tbt_failed"}) {
            String line="USB_CP_APP_BOOTSTRAP phase="+phase;sanitized(line,line);
            sanitized(line,line+" type=PrivateException");
        }
        for(String bad:new String[]{"USB_CP_MANUAL event=PRIVATE","USB_CP_MANUAL event=finished",
                "USB_CP_MANUAL event=finished status=-1","USB_CP_MANUAL event=finished status=11",
                "USB_CP_MANUAL event=finished status=9223372036854775808","USB_CP_MANUAL event=finished status=1 status=2",
                "USB_CP_MANUAL event=server_failed kind=PrivateException","USB_CP_MANUAL event=accepted kind=io",
                "USB_CP_APP_BOOTSTRAP phase=PRIVATE","USB_CP_APP_BOOTSTRAP phase=entered package=private.name MAC=PRIVATE",
                "USB_CP_APP_BOOTSTRAP phase=tbt_failed type=PrivateException message=PRIVATE",
                "USB_CP_APP_BOOTSTRAP phase=tbt_failed package=private.name",
                "USB_CP_APP_BOOTSTRAP phase=entered package=private.name\nSECRET"})sanitized(null,bad);
        for(String recovery:new String[]{"0","1","2","3","4","5","6","7","8","failed"}) {
            String line="wall_ms=123 elapsed_ms=456 CP_MANUAL_AP recovery="+recovery;
            check(("123 456 manual_ap recovery="+recovery).equals(History.sanitized("provider",line)));
            check(("123 456 manual_ap recovery="+recovery).equals(History.sanitized("provider",line+" dropped=2")));
            check(History.sanitized("provider",line+" phone=PRIVATE")==null);
        }
        for(String bad:new String[]{"-1","9","01","PRIVATE","1 recovery=2","1 dropped=-1","1 dropped=9223372036854775808"})
            check(History.sanitized("provider","wall_ms=123 elapsed_ms=456 CP_MANUAL_AP recovery="+bad)==null);
        check("WAIT_POWER_READER".equals(History.sanitized("ap","AP_EVENT state=WAIT_POWER_READER")));
        check(History.sanitized("ap","AP_EVENT state=WAIT_POWER_READER SSID=PRIVATE")==null);
    }
    public static void main(String[] args) throws Exception {
        manualDiagnostics();
        v15Diagnostics();
        diagnosticRecords();
        File root = new File(args[0]).getCanonicalFile();
        File before = file(root, "provider.log.1",
                "wall_ms=101 elapsed_ms=200 CP_PROVIDER version=1.0.0 event=started\n" +
                "wall_ms=102 elapsed_ms=300 CP_OBSERVER event=unavailable phase=net_identity kind=private\n");
        File provider = file(root, "provider.log",
                "wall_ms=103 elapsed_ms=12 CP_PROVIDER event=ready age_ms=900\n" +
                "wall_ms=104 elapsed_ms=13 CP_USB_ID event=seen id=private\n" +
                "wall_ms=105 elapsed_ms=14 CP_OBSERVER event=retry phase=private\n" +
                "wall_ms=106 elapsed_ms=15 CP_OBSERVER event=recovered failures=3\n");
        StringBuilder records = new StringBuilder();
        for (int i=0; i<350; i++) records.append("AP_EVENT state=WAIT_DONGLE\n");
        File ap = file(root, "daemon_stdout.log", records + "AP_EVENT state=SERVICES_RUNNING\n" +
                "AP_EVENT state=SECRET\nAP_EVENT state=WAIT_DONGLE PRIVATE\nAP_EVENT state=STARTING");
        File input = file(root, "logcat-input",
                HEADER + "USB_CP_CREDENTIALS_SENT frequency=5180 PRIVATE\n" +
                HEADER + "bridge: CarPlay session started transport=1 PRIVATE\n" +
                HEADER + "bridge: CarPlay session created (usb=0 btAddr=00:11:22:33:44:55)\n" +
                HEADER + "bridge: CarPlay session finalized type=1 PRIVATE\n" +
                HEADER + "bridge: metadata PRIVATE USB_CP_MEDIA\n" +
                HEADER + "Title: CarPlay session started transport=5 PRIVATE\n" +
                HEADER + "USB_CP_SECRETCODE PRIVATE\n" +
                HEADER.replace("neev-usb-carplay","other") + "USB_CP_IAP_ATTACH PRIVATE\n");
        File logcat = file(root, "logcat",
                "#!/bin/sh\n[ \"$*\" = '-d -v time -s HMG-API:I *:S' ] || exit 9\n" +
                "exec /bin/cat \"\u0024{0%/*}/logcat-input\"\n");
        check(logcat.setExecutable(true));
        File out = result(root,1);
        check(History.collect(out,ap,provider,logcat) == 0);
        check(read(out,"ap-events.txt").split("\n").length == 256);
        check(read(out,"ap-events.txt").endsWith("SERVICES_RUNNING\n"));
        check(read(out,"provider-events.txt").equals(
                "101 200 provider started\n102 300 observer unavailable net_identity kind=unknown\n103 12 provider ready age_ms=900\n106 15 observer recovered failures=3\n"));
        check(read(out,"carplay-events.txt").equals(STAMP + "USB_CP_CREDENTIALS_SENT\n" + STAMP +
                "SESSION_STARTED\n" + STAMP + "SESSION_CREATED\n" + STAMP + "SESSION_FINALIZED\n"));
        for (File f : out.listFiles()) {
            String text = read(out,f.getName());
            for (String secret : Arrays.asList("PRIVATE","SECRET","00:11:22:", "btAddr", "metadata", "5180", "CAPTIVE"))
                check(!text.contains(secret));
        }
        try { History.collect(out,ap,provider,logcat); throw new AssertionError("overwrite"); } catch (IOException expected) {}
        check(before.delete());
        check(History.collect(result(root,2),ap,provider,logcat) == 0);
        File unsafe = new File(root,"unsafe");
        Files.createSymbolicLink(unsafe.toPath(),ap.toPath());
        File missingOut = result(root,3);
        check(History.collect(missingOut,unsafe,new File(root,"absent"),new File(root,"missing-logcat")) == 7);
        check(read(missingOut,"history-status.txt").contains("ap=UNSAFE_PATH"));
        check(read(missingOut,"history-status.txt").contains("carplay=MISSING"));
        check(read(missingOut,"history-status.txt").contains("provider_current=MISSING"));
        check(read(missingOut,"carplay-events.txt").isEmpty());
        File failLogcat = file(root,"fail-logcat","#!/bin/sh\nexit 3\n");
        check(failLogcat.setExecutable(true));
        File failOut = result(root,4);
        check(History.collect(failOut,ap,provider,failLogcat)==7 && read(failOut,"history-status.txt").contains("carplay=DUMP_FAILED"));
        File slow = file(root,"slow-logcat","#!/bin/sh\nexec /bin/sleep 15\n");
        check(slow.setExecutable(true));
        File timeoutOut = result(root,5);
        long started=System.nanoTime();
        check(History.collect(timeoutOut,ap,provider,slow)==7 && read(timeoutOut,"history-status.txt").contains("carplay=TIMEOUT"));
        check((System.nanoTime()-started)/1000000 < 7200);
        File supervisor=file(root,"usb-ap.log","AP_STARTUP version=1.0.1 phase=ENTRY\n"+
                "AP_STARTUP_FAILURE reason=PREPARE_PATHS\nAP_DRIVER state=ready family=88x2bu\n"+
                "AP_DRIVER state=ready family=88x2bu SECRET\ncredential=PRIVATE\n");
        File services=file(root,"services.status","neev-usb-carplay usb-ap running 123 0\n"+
                "neev-usb-carplay ap-provider waiting 0 0\nother secret PRIVATE\n");
        File modules=file(root,"modules","88x2bu 1234 0 - Live 0xffffffff\nother PRIVATE 0\n");
        File bootOut=result(root,6);
        History.boot(bootOut,supervisor,services,modules);
        check(read(bootOut,"boot-supervisor.txt").contains("reason=PREPARE_PATHS"));
        check(History.sanitized("boot","AP_STARTUP version=1.0.2 phase=ENTRY")!=null);
        check(History.sanitized("boot","AP_STARTUP version=1.0.3 phase=ENTRY")!=null);
        check(History.sanitized("boot","AP_STARTUP version=1.0.4 phase=ENTRY")!=null);
        check(History.sanitized("boot","AP_STARTUP version=1.0.5 phase=ENTRY")!=null);
        check(History.sanitized("boot","AP_STARTUP version=1.0.6 phase=ENTRY")!=null);
        check(History.sanitized("boot","AP_STARTUP version=1.0.7 phase=ENTRY")!=null);
        check(History.sanitized("boot","AP_STARTUP version=1.0.8 phase=ENTRY")!=null);
        check(History.sanitized("boot","AP_STARTUP version=1.0.14 phase=ENTRY")!=null);
        check(read(bootOut,"boot-services.txt").split("\n").length==2);
        check(read(bootOut,"boot-drivers.txt").equals("88x2bu LOADED\n"));
        for(File f:bootOut.listFiles())check(!read(bootOut,f.getName()).contains("PRIVATE")&&!read(bootOut,f.getName()).contains("SECRET"));
        // Verify the public USB collection path preserves metrics, not only tokens.
        StringBuilder metricInput = new StringBuilder();
        for (int i=0;i<350;i++) metricInput.append(HEADER).append("USB_CP_LOCATION window generation=1 window_ms=10000 send_ok=")
                .append(i).append(" latitude=37.123456 SSID=Hyundai_123456 password=PRIVATE\n");
        metricInput.append(HEADER).append("USB_CP_AUDIO event generation=2 elapsed_ms=1234 stock_start stream=Main type=Media rate=48000 exception=false title=PRIVATE\n")
                .append(HEADER).append("USB_CP_TBT_LANES input=1 output=1 lane0=1:[2]3:[0] vehicle_id=123456789\n");
        Files.write(input.toPath(),metricInput.toString().getBytes("UTF-8"));
        Files.write(ap.toPath(),("AP_POWER_OBSERVED state=OFF:6\nAP_EVENT state=HU_POWER_OFF\n" +
                "AP_POWER_OBSERVED state=ON:0\nAP_LINK_DOWN_FAILED iface=rtwlan0\n").getBytes("UTF-8"));
        File metricsOut=result(root,7);
        check(History.collect(metricsOut,ap,provider,logcat)==0);
        String exported=read(metricsOut,"carplay-events.txt");
        check(exported.split("\n").length==256);
        check(exported.contains("send_ok=349") && exported.contains("rate=48000") && exported.contains("lane0_highlight=0"));
        check(!exported.contains("send_ok=0\n") && !exported.contains("latitude") && !exported.contains("37.123456") &&
                !exported.contains("Hyundai_") && !exported.contains("password") && !exported.contains("PRIVATE") && !exported.contains("123456789"));
        check(read(metricsOut,"ap-events.txt").equals("AP_POWER_OBSERVED state=OFF raw_status=6\nHU_POWER_OFF\n" +
                "AP_POWER_OBSERVED state=ON raw_status=0\nAP_LINK_DOWN_FAILED\n"));
        // The built-in HMG keeper survives a reboot while volatile logcat can be empty.
        File keeper=file(root,"hmg-log.log",HEADER+"USB_CP_LOCATION window generation=4 send_ok=22 latitude=PRIVATE\n");
        file(root,"hmg-log.log.1",HEADER+"USB_CP_AUDIO event generation=3 elapsed_ms=100 micom_start requested=13 accepted=true selected_stream=13 exception=false phone=PRIVATE\n"+
                HEADER.replace("neev-usb-carplay","other")+"USB_CP_MEDIA PRIVATE\n");
        File persistentOut=result(root,8);
        check(History.persistent(persistentOut,keeper)==0);
        check(read(persistentOut,"carplay-persistent-current.txt").contains("send_ok=22"));
        check(read(persistentOut,"carplay-persistent-previous.txt").contains("selected_stream=13"));
        for(File f:persistentOut.listFiles())check(!read(persistentOut,f.getName()).contains("PRIVATE"));
        check(read(persistentOut,"persistent-log-status.txt").contains("BOUNDED_ROTATING_NOT_COMPLETE_HISTORY"));
        try{History.persistent(persistentOut,keeper);throw new AssertionError("persistent overwrite");}catch(IOException expected){}
        check(History.persistent(result(root,9),new File(root,"missing-keeper"))==7);
        File keeperLink=new File(root,"keeper-link");Files.createSymbolicLink(keeperLink.toPath(),keeper.toPath());
        File unsafeKeeperOut=result(root,10);
        check(History.persistent(unsafeKeeperOut,keeperLink)==7);
        check(read(unsafeKeeperOut,"persistent-log-status.txt").contains("UNSAFE_PATH"));
        File development=new File(root,"development");check(development.mkdir());
        StringBuilder longHistory=new StringBuilder();
        longHistory.append(HEADER).append("USB_CP_DEV_LOG start wall_ms=123 elapsed_ms=456\n");
        for(int i=0;i<1000;i++)longHistory.append(HEADER).append("USB_CP_VIDEO window generation=1 elapsed_ms=")
                .append(i).append(" show_display=1 resumed=1 SSID=PRIVATE\n");
        longHistory.append(HEADER.replace("neev-usb-carplay","other")).append("USB_CP_VIDEO observers_unavailable PRIVATE\n");
        longHistory.append(HEADER).append("unknown PRIVATE\n");
        file(development,"carplay.log",longHistory.toString());
        file(development,"carplay.log.1",HEADER+"USB_CP_POWER event=delivered state=OFF\n");
        File devOut=result(root,11);check(History.development(devOut,development)==0);
        String dev=read(devOut,"development-current.txt");
        check(dev.split("\n").length==1001 && dev.contains("elapsed_ms=0 ") && dev.contains("elapsed_ms=999 "));
        check(dev.startsWith("pid=101 ") && dev.contains("USB_CP_DEV_LOG start wall_ms=123 elapsed_ms=456"));
        check(read(devOut,"development-1.txt").contains("state=OFF"));
        for(File f:devOut.listFiles())check(!read(devOut,f.getName()).contains("PRIVATE"));
        try{History.development(devOut,development);throw new AssertionError("development overwrite");}catch(IOException expected){}
        check(History.development(result(root,12),new File(root,"missing-development"))==7);
        File devLink=new File(root,"development-link");Files.createSymbolicLink(devLink.toPath(),development.toPath());
        File unsafeDevOut=result(root,13);check(History.development(unsafeDevOut,devLink)==7);
        check(read(unsafeDevOut,"development-status.txt").contains("UNSAFE"));
        StringBuilder tooLarge=new StringBuilder();
        while(tooLarge.length()<2*1024*1024+4096)tooLarge.append(HEADER).append("USB_CP_VIDEO observers_unavailable\n");
        file(development,"carplay.log.3",tooLarge.toString());
        File limited=result(root,14);check(History.development(limited,development)==7);
        check(read(limited,"development-status.txt").contains("slot=3 state=READ_LIMIT"));
        check(new File(limited,"development-3.txt").length()<3*1024*1024);
        StringBuilder v15Input=new StringBuilder();StringBuilder v15Expected=new StringBuilder();
        for(String record:v15Records()){v15Input.append(HEADER).append(record).append('\n');v15Expected.append(STAMP).append(record).append('\n');}
        v15Input.append(HEADER).append("USB_CP_TBT_DIAG kind=route road=PRIVATE\n");
        Files.write(input.toPath(),v15Input.toString().getBytes("UTF-8"));
        Files.write(ap.toPath(),(POWER_INPUT+"\n"+POWER_DECISION+"\n"+POWER_INPUT+" SSID=PRIVATE\n").getBytes("UTF-8"));
        file(root,"power-input.log",POWER_INPUT+"\n"+POWER_DECISION+"\n"+POWER_INPUT+" SSID=PRIVATE\n");
        file(root,"power-input.log.1",POWER_INPUT+"\n");
        file(root,"power-decision.log",POWER_DECISION+"\n"+POWER_INPUT+"\n");
        file(root,"power-decision.log.1",POWER_DECISION+"\n");
        File v15Out=result(root,15);check(History.collect(v15Out,ap,provider,logcat)==0);
        check(read(v15Out,"carplay-events.txt").equals(v15Expected.toString()));
        check(read(v15Out,"ap-events.txt").equals(POWER_INPUT+"\n"+POWER_DECISION+"\n"));
        for(String slot:new String[]{"current","1"}) {
            check(read(v15Out,"power-input-"+slot+".txt").equals(POWER_INPUT+"\n"));
            check(read(v15Out,"power-decision-"+slot+".txt").equals(POWER_DECISION+"\n"));
        }
        check(new File(root,"power-input.log.1").delete());
        Files.createSymbolicLink(new File(root,"power-input.log.1").toPath(),ap.toPath());
        File unsafePower=result(root,16);check(History.collect(unsafePower,ap,provider,logcat)==7);
        check(read(unsafePower,"history-status.txt").contains("power-input_1=UNSAFE_PATH"));
        check(read(unsafePower,"power-input-1.txt").isEmpty());
        System.out.println("HistoryTest PASS: source and prefix ownership, strict categories, caps, rotated files, redaction, no overwrite, missing, failure and timeout; v1.0.15 numeric diagnostics roundtrip");
    }
}
