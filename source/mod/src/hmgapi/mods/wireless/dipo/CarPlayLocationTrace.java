package hmgapi.mods.wireless.dipo;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/** Passive stock location-path audit. No sensor, phone-request or timing overrides. */
public final class CarPlayLocationTrace implements Runnable {
    private static final String[] POINTS = { "dipo.location.start", "dipo.location.stop",
            "dipo.location.sentence", "dipo.location_speed.changed",
            "dipo.location_nmea.received", "dipo.iap2_proxy.send_location" };
    private final ModContext mod;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadLocal<Send> sending = new ThreadLocal<Send>();
    private LocationTraceWindow window;
    private int generation;
    private volatile boolean installed;

    private static final class Send {
        final long start = SystemClock.elapsedRealtime();
        final LocationTraceWindow window;
        Send(LocationTraceWindow window) { this.window = window; }
    }

    private CarPlayLocationTrace(ModContext mod) { this.mod = mod; }

    public static void install(ModContext mod, LoadedApp app) {
        try {
            // A missing optional observer must not prevent CarPlay from starting.
            for (String point : POINTS) Points.method(app.classLoader, point);
            new CarPlayLocationTrace(mod).hooks(app.classLoader);
            mod.log("USB_CP_LOCATION observers_ready aggregate_ms=10000 numeric_speed_only=1 no_coordinates=1");
        } catch (Throwable ignored) {
            mod.log("USB_CP_LOCATION observers_unavailable");
        }
    }

    private void hooks(ClassLoader cl) {
        mod.hook(cl, POINTS[0], new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                if (p.getThrowable() == null) start();
            }
        });
        mod.hook(cl, POINTS[1], new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                if (p.getThrowable() == null) stop();
            }
        });
        mod.hook(cl, POINTS[2], new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                int kind = sentence(p.args[0]);
                if (kind >= 0) event(kind);
            }
        });
        mod.hook(cl, POINTS[3], new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                if (p.getThrowable() == null) {
                    event(LocationTraceWindow.SPEED);
                    synchronized(CarPlayLocationTrace.this){
                        if(installed&&mod.enabled()&&window!=null)window.micomSpeed(p.args[0],SystemClock.elapsedRealtime());
                    }
                }
            }
        });
        mod.hook(cl, POINTS[4], new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                // Before stock code forwards this callback, for same-callback timing.
                int kind = sentence(p.args[1]);
                if (kind == LocationTraceWindow.GGA_OUT) event(LocationTraceWindow.GGA_IN);
                if (kind == LocationTraceWindow.RMC_OUT) {
                    event(LocationTraceWindow.RMC_IN);
                    synchronized(CarPlayLocationTrace.this){
                        if(installed&&mod.enabled()&&window!=null)window.rmcSpeed(p.args[1],SystemClock.elapsedRealtime());
                    }
                }
            }
        });
        mod.hook(cl, POINTS[5], new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                synchronized (CarPlayLocationTrace.this) {
                    sending.remove();
                    if (installed && mod.enabled() && window != null) sending.set(new Send(window));
                }
            }
            @Override protected void afterHookedMethod(Param p) {
                Send send = sending.get();
                sending.remove();
                if (send == null) return;
                synchronized (CarPlayLocationTrace.this) {
                    if (!installed || !mod.enabled() || window != send.window) return;
                    long now = SystemClock.elapsedRealtime();
                    window.sent(now - send.start);
                    window.event(p.getThrowable() != null ? LocationTraceWindow.SEND_ERROR
                            : Boolean.TRUE.equals(p.getResult()) ? LocationTraceWindow.SEND_OK
                            : LocationTraceWindow.SEND_FALSE, now);
                }
            }
        });
        // Do not report incomplete traces if installation fails part-way through.
        installed = true;
    }

    private static int sentence(Object value) {
        if (!(value instanceof String)) return -1;
        String text = (String) value;
        if (text.startsWith("$GPGGA,")) return LocationTraceWindow.GGA_OUT;
        if (text.startsWith("$GPRMC,")) return LocationTraceWindow.RMC_OUT;
        if (text.startsWith("$PASCD,")) return LocationTraceWindow.PASCD_OUT;
        return -1;
    }

    private synchronized void start() {
        if (!installed || !mod.enabled()) return;
        stop();
        window = new LocationTraceWindow(SystemClock.elapsedRealtime());
        generation++;
        mod.log("USB_CP_LOCATION request_started generation=" + generation);
        handler.postDelayed(this, 10000);
    }

    private synchronized void stop() {
        handler.removeCallbacks(this);
        if (window == null) return;
        if (mod.enabled()) report("stopped");
        window = null;
    }

    private synchronized void event(int kind) {
        if (installed && mod.enabled() && window != null) window.event(kind, SystemClock.elapsedRealtime());
    }

    private void report(String reason) {
        mod.log("USB_CP_LOCATION " + reason + " generation=" + generation + " "
                + window.summary(SystemClock.elapsedRealtime()));
    }

    @Override public synchronized void run() {
        if (window == null) return;
        if (!mod.enabled()) { stop(); return; }
        report("window");
        handler.postDelayed(this, 10000);
    }
}
