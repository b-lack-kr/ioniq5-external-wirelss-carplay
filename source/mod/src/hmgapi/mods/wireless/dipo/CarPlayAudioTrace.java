package hmgapi.mods.wireless.dipo;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/** Observe stock stream selection and MICOM requests, never change routing or gain. */
public final class CarPlayAudioTrace implements Runnable {
    private static final String[] POINTS = {
            "dipo.carplay.session_started", "dipo.carplay.session_finalized",
            "dipo.carplay.audio_stream_start", "dipo.carplay.audio_stream_stop",
            "dipo.session_listener.audio_stream_start", "dipo.session_listener.audio_stream_stop",
            "dipo.session_listener.duck", "dipo.session_listener.unduck",
            "framework.micom_audio.start_audio", "framework.micom_audio.end_audio",
            "framework.micom_audio.start_dipo_music", "framework.micom_audio.current_stream",
            "dipo.micom_listener.status_changed", "dipo.key_listener.all_keys" };
    private final ModContext mod;
    private final ClassLoader cl;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioTraceWindow window;
    private int generation;
    private volatile boolean installed;
    private int nativeStream = -1, nativeType = -1, micomStream = -1;

    private CarPlayAudioTrace(ModContext mod, ClassLoader cl) { this.mod = mod; this.cl = cl; }

    public static void install(ModContext mod, LoadedApp app) {
        try {
            for (String point : POINTS) Points.method(app.classLoader, point);
            new CarPlayAudioTrace(mod, app.classLoader).hooks();
            mod.log("USB_CP_AUDIO observers_ready detail_limit_per_session=24 aggregate_ms=10000");
        } catch (Throwable ignored) { mod.log("USB_CP_AUDIO observers_unavailable"); }
    }

    private void hooks() {
        mod.hook(cl, POINTS[0], new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                if (p.getThrowable() == null) start(number(p.args[0]));
            }
        });
        mod.hook(cl, POINTS[1], new MethodHook() {
            @Override protected void afterHookedMethod(Param p) { stop(); }
        });
        mod.hook(cl, POINTS[2], new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                synchronized (CarPlayAudioTrace.this) {
                    nativeStream = number(p.args[0]); nativeType = number(p.args[1]);
                    event(AudioTraceWindow.NATIVE_START, "native_start stream=" + nativeStream
                            + " type=" + nativeType + " rate=" + number(p.args[2]));
                }
            }
        });
        mod.hook(cl, POINTS[3], new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                event(AudioTraceWindow.NATIVE_STOP, "native_stop stream=" + number(p.args[0]));
            }
        });
        mod.hook(cl, POINTS[4], new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                event(p.getThrowable() == null ? AudioTraceWindow.STOCK_START : AudioTraceWindow.FAILURE,
                        "stock_start stream=" + enumName(p.args[0]) + " type=" + enumName(p.args[1])
                                + " rate=" + number(p.args[2]) + " exception=" + (p.getThrowable() != null));
            }
        });
        mod.hook(cl, POINTS[5], new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                event(p.getThrowable() == null ? AudioTraceWindow.STOCK_STOP : AudioTraceWindow.FAILURE,
                        "stock_stop stream=" + enumName(p.args[0]) + " exception=" + (p.getThrowable() != null));
            }
        });
        for (int i = 6; i <= 7; i++) {
            final boolean duck = i == 6;
            mod.hook(cl, POINTS[i], new MethodHook() {
                @Override protected void afterHookedMethod(Param p) {
                    event(p.getThrowable() != null ? AudioTraceWindow.FAILURE
                                    : duck ? AudioTraceWindow.DUCK : AudioTraceWindow.UNDUCK,
                            (duck ? "duck" : "unduck") + " exception=" + (p.getThrowable() != null));
                }
            });
        }
        for (int i = 8; i <= 10; i++) {
            final int kind = i == 8 ? AudioTraceWindow.MICOM_START
                    : i == 9 ? AudioTraceWindow.MICOM_END : AudioTraceWindow.MICOM_MUSIC;
            final String name = i == 8 ? "micom_start" : i == 9 ? "micom_end" : "micom_music";
            mod.hook(cl, POINTS[i], new MethodHook() {
                @Override protected void afterHookedMethod(Param p) {
                    int type = number(p.args[0]);
                    if (kind != AudioTraceWindow.MICOM_MUSIC && type != 13 && type != 19) return;
                    AudioTraceWindow observed;
                    synchronized (CarPlayAudioTrace.this) {
                        if (!installed || window == null || !mod.enabled()) return;
                        observed = window;
                    }
                    int selected;
                    try { selected = number(Points.call(cl, POINTS[11], p.thisObject)); }
                    catch (Throwable ignored) { selected = -1; }
                    synchronized (CarPlayAudioTrace.this) {
                        if (!installed || window != observed || !mod.enabled()) return;
                        micomStream = selected;
                        boolean ok = p.getThrowable() == null && Boolean.TRUE.equals(p.getResult());
                        event(kind, name + " requested=" + type + " accepted=" + ok
                                + " selected_stream=" + micomStream + " exception=" + (p.getThrowable() != null));
                        if (!ok) window.event(AudioTraceWindow.FAILURE);
                    }
                }
            });
        }
        mod.hook(cl, POINTS[12], new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                // Status structure is deliberately not inspected or serialized.
                event(AudioTraceWindow.MICOM_STATUS, "micom_status code=" + number(p.args[0]));
            }
        });
        mod.hook(cl, POINTS[13], new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                int action = number(p.args[0]), key = number(p.args[3]);
                if (action == 0 && (key == 24 || key == 25)) {
                    event(AudioTraceWindow.VOLUME_KEY, "standard_volume_key direction=" + (key == 24 ? "up" : "down"));
                }
            }
        });
        installed = true;
    }

    private static int number(Object value) { return value instanceof Number ? ((Number) value).intValue() : -1; }
    private static String enumName(Object value) { return value instanceof Enum ? ((Enum<?>) value).name() : "unknown"; }

    private synchronized void start(int transport) {
        if (!installed || !mod.enabled()) return;
        stop();
        window = new AudioTraceWindow(SystemClock.elapsedRealtime());
        nativeStream = nativeType = micomStream = -1;
        generation++;
        mod.log("USB_CP_AUDIO session_started generation=" + generation + " transport=" + transport);
        handler.postDelayed(this, 10000);
    }

    private synchronized void event(int kind, String detail) {
        if (!installed || window == null || !mod.enabled()) return;
        if (window.event(kind)) mod.log("USB_CP_AUDIO event generation=" + generation
                + " elapsed_ms=" + SystemClock.elapsedRealtime() + " " + detail);
    }

    private void report() {
        String summary = window.summary(SystemClock.elapsedRealtime());
        if (summary != null) mod.log("USB_CP_AUDIO window generation=" + generation + " " + summary
                + " last_native_stream=" + nativeStream + " last_native_type=" + nativeType
                + " last_micom_stream=" + micomStream);
    }

    private synchronized void stop() {
        handler.removeCallbacks(this);
        if (window != null && mod.enabled()) report();
        window = null;
    }

    @Override public synchronized void run() {
        if (window == null) return;
        if (!mod.enabled()) { stop(); return; }
        report();
        handler.postDelayed(this, 10000);
    }
}
