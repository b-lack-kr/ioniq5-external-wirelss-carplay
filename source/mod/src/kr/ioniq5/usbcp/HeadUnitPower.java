// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import android.os.Looper;
import android.os.Handler;
import android.os.SystemClock;
import com.hkmc.power.MicomPowerStatusListener;
import com.hkmc.bcm.BCMPowerListener;
import com.hkmc.bcm.BCMDoorListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** Read OEM logical power/ignition/door events. Never write MICOM, BCM or USB power. */
public final class HeadUnitPower {
    private static final File STATE = new File("/data/local/tmp/neev_usbcp/power-state");
    private static final File NEXT = new File("/data/local/tmp/neev_usbcp/power-state.new");
    private static final RetainedPower policy = new RetainedPower();
    private static final PowerInputTrace trace = new PowerInputTrace();
    private static final PowerTraceFile traceFile = new PowerTraceFile(
        new File("/data/local/tmp/neev_usbcp/power-input.log"));
    private static Object manager, bcm, context;
    private static Method off, status, preparing;
    private static Handler handler;
    private static String boot, raw = "unknown", previous = "";
    private static long durableDecision = -1;
    private static boolean registering;

    // Retained for the focused reflection/ABI test. The policy below does not
    // treat this false getter alone as a new ON event after an OFF latch.
    static String sample(Object source, Method offMethod, Method statusMethod) throws Exception {
        Object value = offMethod.invoke(source);
        if (!(value instanceof Boolean)) throw new IllegalStateException();
        String r = "unknown";
        try { Object v = statusMethod.invoke(source); if (v instanceof Integer) r = v.toString(); }
        catch (Exception ignored) { }
        return (((Boolean)value).booleanValue() ? "OFF" : "ON") + " " + r;
    }
    private static long now() { return SystemClock.elapsedRealtimeNanos(); }
    private static String line(File f) throws Exception {
        if (!f.getCanonicalFile().equals(f.getAbsoluteFile()) || !f.isFile() || f.length() > 512)
            throw new IllegalStateException();
        BufferedReader r = new BufferedReader(new FileReader(f));
        try { String s = r.readLine(); if (s == null || r.readLine() != null) throw new IllegalStateException(); return s; }
        finally { r.close(); }
    }
    private static void restore() {
        try {
            String[] fields = line(STATE).split(" ");
            if (fields.length != 6 || !boot.equals(fields[3]) || !fields[4].matches("0|[1-9][0-9]{0,17}")) return;
            long time = Long.parseLong(fields[4]);
            if (time > now()) return;
            policy.restore(fields[1], time);
        } catch (Exception ignored) { }
    }
    private static void changed(Runnable observation) {
        handler.post(new Runnable() { public void run() {
            observation.run(); publishOrExit();
        }});
    }
    private static void connect() throws Exception {
        if (context == null) {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object activity = thread.getMethod("systemMain").invoke(null);
            context = thread.getMethod("getSystemContext").invoke(activity);
        }
        Class<?> type = Class.forName("com.hkmc.power.MicomPowerManager");
        Object next = type.getConstructor(Class.forName("android.content.Context")).newInstance(context);
        off = type.getMethod("isPowerOff"); status = type.getMethod("getPowerStatus");
        preparing = type.getMethod("isPreparingSystemOff");
        if (off.getReturnType() != boolean.class || status.getReturnType() != int.class ||
                preparing.getReturnType() != boolean.class) throw new IllegalStateException();
        manager = next;
        try {
            type.getMethod("registerPowerStatusListener", MicomPowerStatusListener.class).invoke(manager,
                new MicomPowerStatusListener() {
                    public void onPowerStatusChanged(final int value) {
                        // The private MicomPowerService replays current state
                        // when this listener registers, via a queued Handler.
                        // A simple registering flag cannot catch that replay.
                        // Polling separately handles initial OFF; this first
                        // callback must never authorize ON after a restart.
                        final long time = now();
                        changed(new Runnable() { public void run() {
                            raw = Integer.toString(value); trace.micom(value); policy.micomCallback(value, time);
                        }});
                    }
                    public void onPrepareSystemOff() {
                        final long time = now();
                        changed(new Runnable() { public void run() { trace.systemOff(true); policy.systemOff(true, time); }});
                    }
                    public void onSystemOff() {
                        final long time = now();
                        changed(new Runnable() { public void run() { trace.systemOff(false); policy.systemOff(false, time); }});
                    }
                });
            System.out.println("AP_POWER_READER micom_listener=READY");
        } catch (Throwable unavailable) {
            System.out.println("AP_POWER_READER micom_listener=UNAVAILABLE type=" + unavailable.getClass().getSimpleName());
        }
        // Normal listeners only: no SystemOffResponder (which would add a
        // shutdown acknowledgement obligation). OEM classes live in automotive.jar.
        try {
            Class<?> typeBcm = Class.forName("com.hkmc.bcm.BCMManager");
            bcm = typeBcm.getConstructor(Class.forName("android.content.Context")).newInstance(context);
            registering = true;
            typeBcm.getMethod("addPowerListener", BCMPowerListener.class).invoke(bcm, new BCMPowerListener() {
                public void onACCChanged(final int value) {
                    final boolean live = !registering; final long time = now();
                    changed(new Runnable() { public void run() { trace.acc(value, live, time); policy.bcmAcc(value, live, time); }});
                }
                public void onIgnitionChanged(final boolean on) {
                    final boolean live = !registering; final long time = now();
                    changed(new Runnable() { public void run() { trace.ignition(on, live, time); policy.ignition(on, live, time); }});
                }
            });
            typeBcm.getMethod("addDoorListener", BCMDoorListener.class).invoke(bcm, new BCMDoorListener() {
                public void onDoorStateChanged(final int which, final int value) {
                    final long time = now();
                    changed(new Runnable() { public void run() { trace.door(which, value, time); policy.door(which, value, time); }});
                }
                public void onDoorStateChanged(final int which, final boolean opened) {
                    final long time = now();
                    changed(new Runnable() { public void run() { trace.door(which, opened ? 1 : 0, time); policy.door(which, opened ? 1 : 0, time); }});
                }
            });
            System.out.println("AP_POWER_READER bcm_listener=READY");
        } catch (Throwable unavailable) {
            System.out.println("AP_POWER_READER bcm_listener=UNAVAILABLE type=" + unavailable.getClass().getSimpleName());
        } finally { registering = false; }
    }
    private static void publish() throws Exception {
        for (File f : new File[]{STATE, NEXT})
            if (!f.getCanonicalFile().equals(f.getAbsoluteFile()) || (f.exists() && !f.isFile()))
                throw new IllegalStateException();
        // Six fields. Heartbeat time is separate from the stable decision time:
        // refreshing a sample must not let an old ON override a newer OFF.
        String value = (SystemClock.elapsedRealtime()/1000) + " " + policy.state + " " + raw + " " +
            boot + " " + policy.decisionNanos + " " + policy.reason + "\n";
        FileOutputStream out = new FileOutputStream(NEXT);
        try {
            out.write(value.getBytes("US-ASCII"));
            if (durableDecision != policy.decisionNanos) out.getFD().sync();
        } finally { out.close(); }
        if (!NEXT.renameTo(STATE)) throw new IllegalStateException();
        durableDecision = policy.decisionNanos;
        String change = policy.state + ":" + raw + ":" + policy.reason + ":" + policy.decisionNanos;
        if (!previous.equals(change)) {
            System.out.println("AP_POWER_OBSERVED state=" + change); previous = change;
        }
        String input=trace.summary(now(),policy.state,policy.reason,policy.decisionNanos);
        if(input!=null)traceFile.append(input);
    }
    private static void publishOrExit() {
        try { publish(); } catch (Exception unwritable) { System.exit(3); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0 || android.os.Process.myUid() != 0 ||
                !Provider.PRODUCT.equals(Provider.property("ro.build.product")) ||
                !"tcc803x".equals(Provider.property("ro.board.platform"))) return;
        boot = line(new File("/proc/sys/kernel/random/boot_id"));
        if (!boot.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return;
        restore();
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                try {
                    if (manager == null) connect();
                    int value = ((Integer)status.invoke(manager)).intValue();
                    boolean isOff = ((Boolean)off.invoke(manager)).booleanValue();
                    boolean prep = ((Boolean)preparing.invoke(manager)).booleanValue();
                    raw = Integer.toString(value); trace.poll(value,isOff,prep); policy.micom(value, isOff, prep, false, now());
                } catch (Throwable unavailable) {
                    // Loss of a getter/binder does not synthesize ON, erase OFF,
                    // or repeatedly register additional listeners.
                    raw = "unknown";
                    trace.pollFailed();
                }
                publishOrExit(); handler.postDelayed(this, 2000);
            }
        });
        Looper.loop();
    }
}
