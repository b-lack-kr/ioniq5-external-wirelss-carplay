package hmgapi.mods.wireless.dipo;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * The Wireless mod in DiPO (the CarPlay app): its app part, linked against DiPO's
 * libraries (com.tcc.iap2, com.broadcom.bt). Wireless.appLoaded starts it.
 *
 * Turn-by-turn ("tbt"): DiPO's own route guidance manager only exists when the car's
 * stepofspec is 999 or more, and stays off (HMG-API never changes stepofspec). HMG-API
 * registers its own listener with the iAP2 server for route guidance (the server sends
 * those notifications to every listener that asks), and CpTurns sends them to the
 * cluster. DiPO still starts and stops the phone's route guidance itself when the
 * phone or the car takes turn-by-turn; a stop clears the HUD (hook on the iAP2 proxy).
 *
 * Native patches (in memory, the files stay stock): libCarPlayCore takes Wi-Fi sessions
 * ("bridge"); libCarPlayAudio offers AAC/Opus on Wi-Fi and libCarPlayUtils sends its
 * audio conversion to the mod's libleafiaaac.so ("audio").
 *
 * Bridge ("bridge"): wireless CarPlay's Bluetooth bridge (Bridge), plus what DiPO needs
 * for it: its own wireless CarPlay off (that's Hyundai's, not this one), its iAP2
 * listener ignoring the bridge's sessions, the reconnect crash fix and the first-connect
 * notice fix.
 */
public final class Dipo {
    private Dipo() {}

    static final String IAP2_ACTION = "com.remote.service.IAP2SERVICE";
    static final String CP_FIRST_CONNECTED = "com.android.app.PhoneProjection.CP_FIRST_CONNECTED";
    /** iAP2 listener type for route guidance (notifications 120, 121, 122) */
    static final int LISTENER_ROUTE_GUIDANCE = 4096;
    static final int RG_UPDATE = 120, RG_MANEUVER = 121, RG_LANES = 122;
    static final String[] RG_COMPONENTS = { "RouteGuidanceComponent0" };

    static ModContext mod;
    static LoadedApp app;
    static volatile IIap2Service iap2;
    static boolean tbt, bridge;
    static Handler turnsHandler;
    static CpTurns turns;
    static volatile RgListener rg;
    /** set while HMG-API itself calls the iAP2 server (its own route guidance restart) */
    static final ThreadLocal<Boolean> OWN = new ThreadLocal<Boolean>();
    /** onSessionDetails' device id while it runs, and whether its address lookup came back empty */
    static final ThreadLocal<String> SESSION = new ThreadLocal<String>();
    static final ThreadLocal<Boolean> NO_ADDRESS = new ThreadLocal<Boolean>();

    static void log(String m) {
        mod.log(m);
    }

    static void error(String m, Throwable t) {
        mod.error(m, t);
    }

    public static void startUsb(ModContext m, LoadedApp a, java.util.concurrent.Callable<Boolean> ready) {
        Bridge.usbApReady = ready;
        start(m, a);
        try { ManualConnectServer.start(); }
        catch (Throwable failure) { ManualDiagnostics.failure("server_failed", failure); }
    }
    public static void usbApChanged() { Bridge.usbApChanged(); }

    /** Wireless.appLoaded, in DiPO's process */
    public static void start(ModContext m, LoadedApp a) {
        mod = m;
        app = a;
        CarPlayLocationTrace.install(m, a);
        CarPlayAudioTrace.install(m, a);
        CarPlayVideoTrace.install(m, a);
        CarPlayVideoRecovery.install(m, a);
        tbt = true; // Basic route guidance is part of the package, independent of AR.
        bridge = true; // Required transport; obsolete stored toggles must not disable it.
        // the CarPlay libraries, patched in memory once DiPO loads them (files stay stock)
        if (bridge) m.nativePatch(a, "libCarPlayCore.so");   // AirPlay sessions over Wi-Fi
        { // Required wireless interface/audio patches.
            m.nativePatch(a, "libCarPlayCoreUtils.so"); // recognize the Realtek USB interface as Wi-Fi
            m.nativePatch(a, "libCarPlayAudio.so");   // offer AAC and Opus on Wi-Fi
            m.nativePatch(a, "libCarPlayUtils.so");   // its AudioConverter calls go to libleafiaaac.so
        }
        if (tbt) {
            try { turnByTurn(); }
            catch (Throwable failure) {
                tbt = false;
                log("USB_CP_APP_BOOTSTRAP phase=tbt_failed type="+failure.getClass().getSimpleName());
            }
        }
        if (bridge) bridge();
        // Resolve CarPlay transport before supplemental-location lifecycle hooks.
        if (bridge) CarPlayVehicleSpeed.install(m, a);
        // Bridge classifies a session before the routing hooks see its callbacks.
        if (bridge) CarPlayAudioRouting.install(m, a);
        if (!tbt && !bridge) return;
        Intent i = new Intent(IAP2_ACTION);
        boolean bound = a.context.bindService(i, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder b) {
                IIap2Service s = IIap2Service.Stub.asInterface(b);
                iap2 = s;
                if (tbt) registerRouteGuidance(s);
                if (bridge) Bridge.onIap2Bound(s);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                iap2 = null;
                CarPlayMedia.reset();
                rg = null;
                if (tbt) post(new Runnable() { public void run() { turns.disconnected(); } });
                log("DiPO: iAP2 server gone");
            }
        }, Context.BIND_AUTO_CREATE);
        log("DiPO: iAP2 server " + (bound ? "binding" : "can't be bound"));
    }

    private static void turnByTurn() {
        if (Car.stepOfSpec() >= 999) {
            log("CarPlay: stepofspec is 999 or more, DiPO's own turn-by-turn runs (HMG-API's stays off)");
            tbt = false;
            return;
        }
        Cluster c = Cluster.of(app.context);
        if (c == null) {
            tbt = false;
            return;
        }
        HandlerThread t = new HandlerThread("hmgapi-cp-tbt");
        t.start();
        turnsHandler = new Handler(t.getLooper());
        turns = new CpTurns(app.context, c, new Runnable() {
            @Override
            public void run() {
                RgListener listener = rg;
                if (listener != null) listener.restart();
            }
        }, new CpTurns.TripScheduler() {
            public long now() { return android.os.SystemClock.elapsedRealtime(); }
            public void later(Runnable work, long delay) { turnsHandler.postDelayed(work, delay); }
            public void cancel(Runnable work) { turnsHandler.removeCallbacks(work); }
            public Object owner() {
                RgListener listener = rg;
                if (listener == null || listener.component == 0 || !mod.enabled() || iap2 != listener.service
                        || !Car.carPlayGuiding(app.context)) return null;
                return listener.tripGeneration;
            }
        }, mod.settingBoolean("ar_view", false),
                NativePatternWriter.create(app.context), NativeLaneWriter.create(app.context));
        mod.hook(app.classLoader, "dipo.iap2_proxy.stop_route_guidance", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                RgListener listener = rg;
                // Stock DiPO and this listener may hold distinct proxies for the same remote binder.
                if (OWN.get() == null && listener != null && p.thisObject instanceof IIap2Service
                        && ((IIap2Service) p.thisObject).asBinder() == listener.service.asBinder()) listener.end(false);
            }
        });
        log("CarPlay: turn-by-turn to the cluster (HMG-API's route guidance listener)");
    }

    static void post(Runnable r) {
        final Runnable run = r;
        turnsHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    run.run();
                } catch (Throwable x) {
                    error("CarPlay turn-by-turn", x);
                }
            }
        });
    }

    static void registerRouteGuidance(IIap2Service s) {
        final RgListener listener = new RgListener(s);
        // Iap2SvcInit may call connected()/notify() synchronously during registration.
        rg = listener;
        try {
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_ROUTE_GUIDANCE);
            info.onSetListenerName("HMGAPI_RG");
            boolean ok = s.Iap2SvcInit(listener, info);
            if (!ok) rg = null;
            log("USB_CP_TBT_LISTENER registered=" + ok);
        } catch (Throwable t) {
            rg = null;
            error("CarPlay: route guidance listener", t);
        }
    }

    static final class RgListener extends IIap2Listener.Stub {
        final IIap2Service service;
        private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong();
        private volatile int component;
        private volatile Object tripGeneration = new Object();
        // All state below is accessed on the TBT handler only.
        private boolean requested, receivedRoute, receivedManeuver;

        RgListener(IIap2Service service) { this.service = service; }

        private boolean current(long ticket) {
            return rg == this && iap2 == service && epoch.get() == ticket && mod.enabled();
        }

        private void request(long ticket) {
            if (!current(ticket) || requested || !Car.carPlayGuiding(app.context)) return;
            try {
                requested = service.Iap2SvcStartRouteGuidanceUpdate(RG_COMPONENTS, true, true);
                log("USB_CP_TBT_REQUEST accepted=" + requested);
            } catch (Exception e) { error("CarPlay: route request", e); }
        }

        // The current app changed. Use the same handler and cancel the delayed START if
        // the session changes or the stock navigation takes the turn-by-turn display.
        void restart() {
            final long ticket = epoch.get();
            if (!current(ticket) || !Car.carPlayGuiding(app.context)) return;
            OWN.set(Boolean.TRUE);
            try {
                if (!service.Iap2SvcStopRouteGuidanceUpdate(RG_COMPONENTS)) return;
                requested = false;
                turnsHandler.postDelayed(new Runnable() {
                    public void run() { request(ticket); }
                }, 200);
            } catch (Exception e) { error("CarPlay: route refresh", e); }
            finally { OWN.remove(); }
        }

        void end(boolean disconnected) {
            if (rg != this) return;
            // A disconnected session, like native takeover, no longer owns optional output.
            // Revoke before the TBT handler runs so delayed clears cannot erase a new owner.
            if (turns != null) turns.revokeAuxiliary();
            if (disconnected) component = 0;
            tripGeneration = new Object();
            final long ticket = epoch.incrementAndGet();
            post(new Runnable() { public void run() {
                if (rg != RgListener.this || epoch.get() != ticket) return;
                requested = receivedRoute = receivedManeuver = false;
                // A stock takeover can precede this queued stop without another
                // phone notification. Do not let a stale primary/trip gate clear it.
                // Never reopen the auxiliary gate revoked above during a stop.
                if (!disconnected && !Car.carPlayGuiding(app.context)) turns.setOutputAllowed(false);
                turns.disconnected();
            }});
        }

        // Stock DiPO issues START when the phone takes navigation. If its route is
        // already active when iAP2 moves from Bluetooth (4) to Wi-Fi (5), re-request
        // on that transport. Identification acceptance can retry a not-yet-ready server.
        private void wirelessReady() {
            final long ticket = epoch.get();
            post(new Runnable() { public void run() {
                if (component == Bridge.COMPONENT_CARPLAY_CLIENT) request(ticket);
            }});
        }

        @Override public void connected(int c, String serial) {
            if (rg != this || (c != Bridge.COMPONENT_USB && c != Bridge.COMPONENT_BT_CARPLAY
                    && c != Bridge.COMPONENT_CARPLAY_CLIENT)) return;
            component = c;
            if (c == Bridge.COMPONENT_CARPLAY_CLIENT) wirelessReady();
        }

        @Override public void disconnected(int c) {
            if (c == component && c != 0) end(true);
        }

        @Override public void notify(final int what, int a1, int a2, final Iap2NotiMsg msg) {
            if (what == Bridge.NOTI_IDENTIFICATION_ACCEPTED && component == Bridge.COMPONENT_CARPLAY_CLIENT) {
                wirelessReady();
                return;
            }
            if ((what != RG_UPDATE && what != RG_MANEUVER && what != RG_LANES) || msg == null) return;
            final long ticket = epoch.get();
            post(new Runnable() { public void run() {
                if (!current(ticket) || component == 0) return;
                turns.setOutputAllowed(Car.carPlayGuiding(app.context));
                if (what == RG_UPDATE) {
                    if (!receivedRoute) log("USB_CP_TBT_RECEIVED route");
                    receivedRoute = true;
                    turns.routeGuidance(msg);
                } else if (what == RG_MANEUVER) {
                    if (!receivedManeuver) log("USB_CP_TBT_RECEIVED maneuver");
                    receivedManeuver = true;
                    turns.maneuver(msg);
                } else turns.lanes(msg);
            }});
        }
    }

    private static void bridge() {
        final ClassLoader cl = app.classLoader;
        Bridge.cl = cl;
        Bridge.context = app.context;
        app.context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
                android.bluetooth.BluetoothDevice device = (android.bluetooth.BluetoothDevice) intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                Bridge.onBondChanged(device, intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1));
            }
        }, new IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        // Broadcom's manager: DiPO registers its listener once the service is connected; the bridge's follows
        mod.hook(cl, "dipo.btcm.register_listener", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (!Bridge.isOurs(p.args[0])) Bridge.onBtcmReady((HfDeviceBTCMManager) p.thisObject);
            }
        });
        // DiPO's CarPlay callbacks (observed)
        mod.hook(cl, "dipo.carplay.session_created", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpSessionCreated(p.thisObject);
            }
        });
        mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpSessionStarted(p.thisObject, (Integer) p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                RgListener listener = rg;
                if (listener != null && p.thisObject != null && p.thisObject == Bridge.sCp) listener.end(true);
                Bridge.onCpSessionFinalized(p.thisObject, (Integer) p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.iap_message", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpIapIn(p.thisObject, (byte[]) p.args[0], (Integer) p.args[1]);
            }
        });
        // DiPO's own wireless CarPlay (Hyundai's) stays off: the feature file is there for the other apps
        mod.hook(cl, "dipo.utils.is_support_wireless_carplay", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                p.setResult(false);
            }
        });
        // DiPO's iAP2 listener: a Bluetooth CarPlay session (4) is a phone like USB (1); the
        // CarPlay-channel session (5) only turns its notifications on; the bridge's sessions
        // ending, and the channel session's identification, aren't DiPO's business
        mod.hook(cl, "dipo.iap2_listener.connected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int c = (Integer) p.args[0];
                if (c == Bridge.COMPONENT_CARPLAY_CLIENT) {
                    Object manager = Points.get(cl, "dipo.iap2_client.manager", Points.get(cl, "dipo.iap2_listener.owner", p.thisObject));
                    Points.call(cl, "dipo.iap2.set_notify_on", null, manager, true);
                    CarPlayMedia.stockListenerReady();
                    p.setResult(null);
                } else if (c == Bridge.COMPONENT_BT_CARPLAY) {
                    p.args[0] = Bridge.COMPONENT_USB;
                }
            }
        });
        mod.hook(cl, "dipo.iap2_listener.disconnected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Bridge.dipoIgnoreDisconnect((Integer) p.args[0])) p.setResult(null);
            }
        });
        mod.hook(cl, "dipo.iap2_listener.notify", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int what = (Integer) p.args[0];
                if (what == Bridge.NOTI_IDENTIFICATION_ACCEPTED) CarPlayMedia.ready(true);
                else if (p.args[3] != null) CarPlayMedia.receipt(what);
                if (Bridge.dipoDropNoti(what)) p.setResult(null);
            }
        });
        reconnectFix(cl);
        firstConnectNotice();
        log("bridge: wireless CarPlay Bluetooth bridge in DiPO");
    }

    /**
     * DiPO crash on a wireless reconnect (stock bug): an iAP2 drop clears the phone's iAP2
     * Bluetooth address, and a wireless session can restart before iAP2 is back;
     * onSessionDetails upper-cases that address unchecked. As the bridge build: when it's
     * missing there, the session's own device id stands in, or (no id either) the stored
     * CarPlay address is kept. Only inside onSessionDetails; every other caller checks.
     */
    private static void reconnectFix(final ClassLoader cl) {
        mod.hook(cl, "dipo.session_listener.session_details", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                // Empty string means an active details callback with no ID. Null is
                // reserved for calls outside that callback, so missing IDs still
                // receive the null-address crash guard and preserve stored BT ID.
                SESSION.set(p.args[0] == null ? "" : (String) p.args[0]);
            }

            @Override
            protected void afterHookedMethod(Param p) {
                SESSION.remove();
                NO_ADDRESS.remove();
            }
        });
        mod.hook(cl, "dipo.iap2.bt_address", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                String id = SESSION.get();
                if (id == null || p.getResult() != null) return;
                p.setResult(id);
                if (id.isEmpty()) NO_ADDRESS.set(Boolean.TRUE);
                log("bridge: no iAP2 Bluetooth address on session details, using the session's (" + (id.isEmpty() ? "none, keeping the stored one" : id) + ")");
            }
        });
        mod.hook(cl, "dipo.bt.set_carplay_bt_address", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (NO_ADDRESS.get() != null) p.setResult(null);
            }
        });
    }

    /**
     * CarPlay's first-connect notice: DiPO looks it up by the phone's Bluetooth address
     * (before iAP2 identification), but saves it under the iPhone's serial, so on a
     * wireless connect it came up every time. As the bridge build: also saved under the
     * Bluetooth address of the last iAP2 session.
     */
    private static void firstConnectNotice() {
        app.context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String id = Bridge.sLastBtId;
                if (id == null) return;
                try {
                    SharedPreferences sp = c.getSharedPreferences("pref_dipo", 0);
                    if (!sp.edit().putBoolean(id, true).commit()) log("bridge: first-connect notice not saved for " + id);
                } catch (Throwable t) {
                    error("bridge: first-connect notice", t);
                }
            }
        }, new IntentFilter(CP_FIRST_CONNECTED));
    }
}
