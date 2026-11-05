package kr.ioniq5.mutepause;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;

/** Observe existing KR keys/session hooks; preserve stock input and track our own pause. */
public final class MutePause extends ModEntry {
    static final String DIPO = "com.daudio.app.dipo";
    static final String ACTION = "kr.ioniq5.mutepause.MUTE_SHORT";
    private static final String REFRESH = "kr.ioniq5.mutepause.VOLUME_ADJUSTED";
    private static final String MANUAL = "kr.ioniq5.mutepause.MANUAL_MEDIA";
    private static final String EVENT_TIME = "releaseUptime";
    private boolean watching, receiving;
    private final KeyStroke key = new KeyStroke();

    @Override public void zygoteInit(final ModContext mod) {
        mod.hook(null, "framework.hkmc_loader.automotive_service", new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                if (Process.myUid() != 1000 || p.getResult() == null || !(p.args[0] instanceof Context)) return;
                installKeyObserver(mod, (Context)p.args[0]);
            }
        });
    }

    private synchronized void installKeyObserver(final ModContext mod, final Context context) {
        if (watching) return;
        final Handler main = new Handler(Looper.getMainLooper());
        final int knobUp = vendorKey("KEYCODE_VOLUME_UP_DUMMY");
        final int knobDown = vendorKey("KEYCODE_VOLUME_DOWN_DUMMY");
        final Runnable volumeAdjusted = new Runnable() {
            public void run() {
                if (mod.enabled()) send(context,mod,REFRESH,-1);
            }
        };
        mod.hook(null, "framework.window_manager_service.intercept_key", new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                if (!(p.args[0] instanceof KeyEvent)) return;
                KeyEvent e = (KeyEvent)p.args[0];
                int code = e.getKeyCode();
                if (e.getDeviceId()>0 && e.getAction()==KeyEvent.ACTION_DOWN && e.getRepeatCount()==0
                        && (code==KeyEvent.KEYCODE_MEDIA_PLAY || code==KeyEvent.KEYCODE_MEDIA_PAUSE
                        || code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code==KeyEvent.KEYCODE_MEDIA_STOP
                        || code==KeyEvent.KEYCODE_MEDIA_NEXT || code==KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
                    main.post(new Runnable(){public void run(){if(mod.enabled())send(context,mod,MANUAL,-1);}});
                    return;
                }
                // Adjusting the volume can unmute MICOM without another mute press.
                if (code > 0 && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                        || code == knobUp || code == knobDown)) {
                    if (e.getDeviceId()>0 && e.getAction()==KeyEvent.ACTION_DOWN) {
                        main.removeCallbacks(volumeAdjusted);
                        main.postDelayed(volumeAdjusted,100);
                    }
                    return;
                }
                if (code != KeyEvent.KEYCODE_MUTE || !key.accept(e.getAction(), e.getDeviceId(),
                        e.getDownTime(), e.getEventTime(), e.getRepeatCount(), e.getFlags())) return;
                final long up = e.getEventTime();
                // No Binder I/O on the window manager input path.
                main.post(new Runnable() { public void run() {
                    if (!mod.enabled()) return;
                    send(context,mod,ACTION,up);
                }});
            }
        });
        watching = true;
        mod.log("MUTE_PAUSE key observer ready");
    }

    private static int vendorKey(String name) {
        try { return KeyEvent.class.getField(name).getInt(null); }
        catch (Exception ignored) { return -1; }
    }

    private static void send(Context context, ModContext mod, String action, long time) {
        try {
            context.sendBroadcast(new Intent(action).setPackage(DIPO)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY).putExtra(EVENT_TIME,time));
        } catch (RuntimeException ex) { mod.error("mute key delivery failed",ex); }
    }

    @Override public synchronized void appLoaded(final ModContext mod, final LoadedApp app) {
        if (receiving || !DIPO.equals(app.packageName) || app.context == null) return;
        final Handler main = new Handler(Looper.getMainLooper());
        try {
            final Player player = new Player(app.classLoader);
            final PauseGate gate = new PauseGate(new PauseGate.Port() {
                public Object service() throws Exception { return player.service(); }
                public boolean muted(Object s) throws Exception { return player.muted(s); }
                public boolean safe(Object s) throws Exception { return mod.enabled() && player.safe(s); }
                public boolean playing(Object s) throws Exception { return player.playing(s); }
                public void pause(Object s) throws Exception { player.pause(s); mod.log("MUTE_PAUSE pause requested"); }
                public void play(Object s) throws Exception { player.play(s); mod.log("MUTE_PAUSE resume requested"); }
                public void later(Runnable r, long ms) { main.postDelayed(r,ms); }
                public void failed(Exception e) { mod.error("mute state unavailable",e); }
            });
            for (String point:new String[]{"dipo.carplay.session_created","dipo.carplay.session_started"}) {
                mod.hook(app.classLoader,point,new MethodHook(){
                    @Override protected void afterHookedMethod(Param p) {
                        // Revoke a previous session before its queued command can run.
                        main.postAtFrontOfQueue(new Runnable(){public void run(){gate.initialize();}});
                    }
                });
            }
            mod.hook(app.classLoader,"dipo.carplay.session_finalized",new MethodHook(){
                @Override protected void afterHookedMethod(Param p) {
                    main.postAtFrontOfQueue(new Runnable(){public void run(){gate.reset();}});
                }
            });
            IntentFilter actions=new IntentFilter(ACTION);
            actions.addAction(REFRESH);
            actions.addAction(MANUAL);
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!mod.enabled()) return;
                    if (ACTION.equals(intent.getAction())) {
                        gate.release(intent.getLongExtra(EVENT_TIME,-1L),SystemClock.uptimeMillis());
                    } else if (REFRESH.equals(intent.getAction())) {
                        gate.refresh();
                    } else if (MANUAL.equals(intent.getAction())) {
                        gate.manualAction();
                    }
                }
            },actions,"android.permission.INJECT_EVENTS",main);
            receiving = true;
            gate.initialize();
            mod.log("MUTE_PAUSE DiPO receiver ready");
        } catch (Exception ex) { mod.error("mute pause unavailable for DiPO",ex); }
    }
}
