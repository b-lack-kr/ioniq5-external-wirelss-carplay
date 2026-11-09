package hmgapi.mods.wireless.dipo;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceStatusListener;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import java.lang.reflect.Method;
import java.util.ArrayList;

import hmgapi.hook.Points;

/**
 * Wireless CarPlay's Bluetooth bridge, in DiPO (from the bridge build, com.ev6.cpwl).
 *
 * An iPhone pairs over Bluetooth; wireless CarPlay starts with iAP2 over Bluetooth
 * (RFCOMM/SPP), which the iAP2 server here can't do by itself. The bridge carries it:
 * SPP data from Broadcom's Bluetooth manager goes into the iAP2 server as transport data
 * (Iap2SvcTransportReadDone), and what the server sends back (notification 80) goes out
 * over SPP. The iAP2 server opens the session for BT_ATTACHED (HMG-API's hook there, part
 * "bridge"). Once the phone has joined the hotspot and the CarPlay session runs, iAP2
 * moves into the CarPlay session's own channel: bytes the phone sends there
 * (oniAPSendMessage) go to the server, and what the server sends for it
 * (notification 81) goes out through the CarPlay library.
 *
 * HMG-API binds the iAP2 server and hooks Broadcom's manager (a library), and only
 * observes DiPO's CarPlay callbacks; DiPO's own iAP2 listener is told to ignore what
 * belongs to the bridge's sessions (Dipo's hooks).
 *
 * persist.ev6.cpwl.enable=0 turns it off; persist.ev6.cpwl.handover=created moves iAP2
 * into the CarPlay channel as soon as the session is created (default: on its first data).
 */
final class Bridge {
    static final String ACTION_ATTACH = "com.ev6.cpwl.BT_ATTACHED", ACTION_DETACH = "com.ev6.cpwl.BT_DETACHED";
    static final String IAP2_PKG = "com.tcc.iap2server";
    static final int APPLE_VENDOR_ID = 76;
    /** iAP2 components: 1 USB, 4 Bluetooth CarPlay, 5 the CarPlay session's channel */
    static final int COMPONENT_USB = 1, COMPONENT_BT_CARPLAY = 4, COMPONENT_CARPLAY_CLIENT = 5;
    static final int LISTENER_EXTERNAL_BT_TRANSPORT = 1024, LISTENER_EXTERNAL_CARPLAY_TRANSPORT = 2048;
    static final int NOTI_IDENTIFICATION_ACCEPTED = 0, NOTI_TRANSPORT_IDENTIFIER = 24;
    static final int NOTI_BT_TRANSPORT_DATA = 80, NOTI_CARPLAY_CLIENT_TRANSPORT_DATA = 81, OBJ_BLOB = 11;
    static final int MAX_PENDING = 64;
    static final long SPP_CONNECT_DELAY_MS = 3000;
    static final int SPP_DISCONNECTED = 0, SPP_CONNECTED = 2, EVENT_BTCM_CONNECTED = 2;

    static java.util.concurrent.Callable<Boolean> usbApReady;
    static volatile String sAddr;
    static volatile HfDeviceBTCMManager sBtcm;
    static volatile Object sCp;
    static volatile boolean sCpClient, sCpConnected, sCpWireless, sSession, sUsb;
    static volatile IIap2Service sIap2;
    /** the phone's Bluetooth address from its last iAP2 session (DiPO's first-connect notice) */
    static volatile String sLastBtId;
    static ClassLoader cl;
    static Context context;
    private static int sCpFail, sCpSent;
    private static Handler sHandler, sCpTx;
    private static Method sNativeSend;
    private static final ArrayList<byte[]> sPendingIn = new ArrayList<byte[]>();
    private static BtListener sBt;
    private static TxListener sTx;
    // Invalidates delayed work from a phone or CarPlay session that has ended.
    private static volatile long sEpoch;
    private static volatile String sScheduledAddr;
    private static final ManualRetryState manual = new ManualRetryState();
    private static volatile String manualSppAddress;
    private static volatile long manualSppRequest;
    private static long manualAuthorizationUntil;
    private static volatile long btAttachStartedAt;
    private static final long STALE_BT_ATTACH_MS = 30000;
    // The KR head unit reports transport 0 even during a dongle CarPlay session.
    // Carry proof of our own BT attach, acknowledged by iAP2 for the same phone,
    // into a single session_created callback. A raw 0 by itself is still wired.
    private static volatile long sRequestedBtEpoch = -1, sConfirmedBtEpoch = -1, sCpBtEpoch = -1;

    static long rawLaneEpoch() { return sEpoch; }

    static synchronized long invalidatePending() {
        try { CpRawLanes.stop(); } catch (Throwable ignored) {}
        sScheduledAddr = null;
        sRequestedBtEpoch = sConfirmedBtEpoch = sCpBtEpoch = -1;
        return ++sEpoch;
    }

    static BluetoothDevice currentHfpBonded(String addr) {
        try {
            HfDeviceBTCMManager m = sBtcm;
            BluetoothDevice d = m == null ? null : m.IbtcmGetHfpConnectedDevice();
            return d != null && addr != null && addr.equals(d.getAddress())
                && d.getBondState() == BluetoothDevice.BOND_BONDED ? d : null;
        } catch (Throwable t) { return null; }
    }

    static BluetoothDevice currentBondedPhone(String addr) {
        BluetoothDevice d = currentHfpBonded(addr);
        return isApple(d) ? d : null;
    }

    static void onBondChanged(final BluetoothDevice device, final int state) {
        if (device == null) return;
        final String addr = device.getAddress();
        handler().post(new Runnable() { public void run() {
            if (state == BluetoothDevice.BOND_NONE) {
                if (addr.equals(sAddr)) resetPhone("unbonded");
                else if (addr.equals(sScheduledAddr)) invalidatePending();
                if (addr.equals(sLastBtId)) sLastBtId = null;
            } else if (state == BluetoothDevice.BOND_BONDED && currentHfpBonded(addr) != null) {
                scheduleSppConnect(addr);
            }
        }});
    }

    static void resetPhone(String reason) {
        CarPlayMedia.reset();
        boolean attached = sSession || sCpClient;
        invalidatePending();
        sSession = false; sCpClient = false; sCpConnected = false; sCpWireless = false;
        btAttachStartedAt = 0;
        sAddr = null; sCp = null; sLastBtId = null;
        synchronized (sPendingIn) { sPendingIn.clear(); }
        if (attached) sendDetach();
        log("USB_CP_LIFECYCLE reset=" + reason);
    }

    private Bridge() {}

    static void log(String m) {
        Dipo.log("bridge: " + m);
    }

    static void error(String m, Throwable t) {
        Dipo.error("bridge: " + m, t);
    }

    /** DiPO registered its listener with Broadcom's manager (the manager is connected): ours goes next to it */
    static void onBtcmReady(HfDeviceBTCMManager m) {
        if (m == null) return;
        try {
            sBtcm = m;
            synchronized (Bridge.class) {
                if (sBt == null) sBt = new BtListener();
            }
            m.registerHfDeviceStatusListener(sBt);
            log("BTCM listener registered");
            BluetoothDevice d = m.IbtcmGetHfpConnectedDevice();
            if (d != null) scheduleSppConnect(d.getAddress());
        } catch (Throwable t) {
            error("onBtcmReady", t);
        }
    }

    static boolean isOurs(Object listener) {
        return listener != null && listener == sBt;
    }

    /** the iAP2 server is bound: register for Bluetooth and CarPlay-channel transport data */
    static void onIap2Bound(IIap2Service s) {
        if (s == null) return;
        try {
            sIap2 = s;
            synchronized (Bridge.class) {
                if (sTx == null) sTx = new TxListener();
            }
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_EXTERNAL_BT_TRANSPORT | LISTENER_EXTERNAL_CARPLAY_TRANSPORT);
            info.onSetListenerName("EV6_CPWL_BT");
            log("Iap2SvcInit(EV6_CPWL_BT, 0xc00) = " + s.Iap2SvcInit(sTx, info));
        } catch (Throwable t) {
            error("onIap2Bound", t);
        }
    }

    static boolean enabled() {
        return Dipo.mod.enabled() && !"0".equals(prop("persist.ev6.cpwl.enable", "1"));
    }

    /** a system property (android.os.SystemProperties isn't in the public SDK) */
    static String prop(String key, String fallback) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("hmgapi-cpwl");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

    /** 1 Apple, 0 not identified yet, -1 explicitly another vendor. */
    static int appleIdentity(BluetoothDevice d) {
        if (d == null) return 0;
        try {
            Object value = BluetoothDevice.class.getMethod("getVendor").invoke(d);
            if (value instanceof Integer) {
                int vendor = (Integer) value;
                if (vendor == APPLE_VENDOR_ID) return 1;
                if (vendor > 0 && vendor != 0xffff) return -1;
            }
        } catch (Throwable ignored) { /* old builds expose no vendor id */ }
        try {
            String name = d.getName();
            if (name != null) {
                String lower = name.toLowerCase(java.util.Locale.US);
                if (lower.contains("iphone") || lower.contains("ipad")) return 1;
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    static boolean isApple(BluetoothDevice d) { return appleIdentity(d) == 1; }

    static void scheduleSppConnect(final String addr) { scheduleSppConnect(addr, 0); }

    static synchronized void scheduleSppConnect(final String addr, final int attempt) {
        if (addr == null || !enabled() || sUsb || sCpWireless || sCpClient || sSession) return;
        if (addr.equals(sScheduledAddr)) return;
        final long epoch = sEpoch;
        final long manualRequest = manual.pending(addr, SystemClock.elapsedRealtime()) ? manual.generation() : -1;
        sScheduledAddr = addr;
        handler().postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (Bridge.class) {
                    if (epoch != sEpoch || !addr.equals(sScheduledAddr)) return;
                    sScheduledAddr = null;
                }
                final Runnable attemptWork = new Runnable() { public void run() { attemptSppConnect(addr, attempt, epoch); }};
                // A request can adopt the existing automatic scheduled attempt.
                // A queued manual attempt cannot become automatic after expiry.
                boolean pending = manual.pending(addr, SystemClock.elapsedRealtime());
                if (manualRequest >= 0 && (!pending || manualRequest != manual.generation())) return;
                if (pending) ManualConnectServer.guard(addr, epoch, manual.generation(), attemptWork);
                else attemptWork.run();
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    /** Existing bounded SPP flow, shared by automatic and explicitly authorized retries. */
    static void attemptSppConnect(final String addr, final int attempt, final long epoch) {
        try {
            if (epoch != sEpoch) return;
            HfDeviceBTCMManager m = sBtcm;
            if (m == null || sUsb || sSession || sCpWireless || sCpClient || !enabled()) return;
            BluetoothDevice phone = currentHfpBonded(addr);
            if (phone == null) {
                if (attempt < 4) scheduleSppConnect(addr, attempt + 1);
                else { log("USB_CP_RETRY_EXHAUSTED phone_not_current_or_bonded"); manualFailure(addr, ManualWire.PHONE_REQUIRED); }
                return;
            }
            int identity = appleIdentity(phone);
            if (identity < 0) { log("USB_CP_RETRY_SKIPPED other_vendor"); manualFailure(addr, ManualWire.NOT_APPLE); return; }
            if (identity == 0 || !apReady()) {
                if (attempt < 4) scheduleSppConnect(addr, attempt + 1);
                else { log("USB_CP_RETRY_EXHAUSTED identity_or_ap_not_ready"); manualFailure(addr, ManualWire.AP_UNAVAILABLE); }
                return;
            }
            manualSppAddress = manual.pending(addr, SystemClock.elapsedRealtime()) ? addr : null;
            manualSppRequest = manual.generation();
            boolean sppConnected = m.IbtcmIsSppConnectedDevice(addr);
            if (manual.pending(addr, SystemClock.elapsedRealtime()) &&
                    SystemClock.elapsedRealtime() > manualAuthorizationUntil) {
                manualFailure(addr, ManualWire.TIMED_OUT); return;
            }
            if (sppConnected) onSppUp(addr);
            else if (!m.IbtcmSppConnect(addr)) {
                if (attempt < 4) scheduleSppConnect(addr, attempt + 1);
                else { log("USB_CP_RETRY_EXHAUSTED spp_request_rejected"); manualFailure(addr, ManualWire.FAILED); }
            }
        } catch (Throwable t) {
            manualFailure(addr, ManualWire.FAILED);
            error("sppConnect", t);
        }
    }

    /** Called only by our handler after the authenticated server's read-only gate. */
    static ManualWire.Result manualControl(String address, int action, int gate) {
        return manualControl(address, action, gate, SystemClock.elapsedRealtime() + 350);
    }

    static ManualWire.Result manualControl(String address, int action, int gate, long deadline) {
        long now = SystemClock.elapsedRealtime();
        BluetoothDevice phone = currentHfpBonded(address);
        if (SystemClock.elapsedRealtime() > deadline) return new ManualWire.Result(ManualWire.TIMED_OUT, 0);
        boolean active = sCp != null || sCpWireless || sCpClient;
        boolean target = address.equals(sAddr);
        int flags = (phone != null ? ManualWire.CURRENT_HFP : 0)
            | (active ? ManualWire.ACTIVE_CP : 0) | (active && target ? ManualWire.ACTIVE_TARGET : 0)
            | (manual.pending(address, now) ? ManualWire.PENDING_TARGET : 0);
        // Provider and this process observe AP state asynchronously.
        if (gate == ManualWire.READY && !apReady()) gate = ManualWire.AP_UNAVAILABLE;
        if (gate == ManualWire.AP_UNAVAILABLE) {
            // Let the stock phone button establish HFP first; an unavailable AP
            // must not hide the ordinary registered-phone selection flow.
            if (phone == null && !active && !sSession && !sUsb && enabled())
                return new ManualWire.Result(ManualWire.PHONE_REQUIRED, flags);
            // Only a click may begin preparation. A query can only observe that
            // same pending request; it cannot start services or reset a session.
            if (manual.pending(address, now)) return new ManualWire.Result(ManualWire.ACCEPTED, flags);
            if (action == ManualWire.RETRY && enabled() && !sUsb && !active && !sSession
                    && phone != null && isApple(phone) && manual.begin(address, now)) {
                final String selected=address;final long epoch=sEpoch, request=manual.generation();
                ManualConnectServer.guard(selected,epoch,request,new Runnable(){public void run(){scheduleSppConnect(selected);}});
                log("USB_CP_MANUAL event=preparing_ap");
                return new ManualWire.Result(ManualWire.ACCEPTED, flags|ManualWire.PENDING_TARGET);
            }
        }
        if (gate != ManualWire.READY) return new ManualWire.Result(gate, flags);
        if (!enabled()) return new ManualWire.Result(ManualWire.DISABLED, flags);
        if (!apReady()) return new ManualWire.Result(ManualWire.AP_UNAVAILABLE, flags);
        if (sUsb) return new ManualWire.Result(ManualWire.BUSY, flags);
        // Actual wireless transport/session proof, not merely an accepted retry.
        if (active && target && sCp != null && sCpWireless) {
            manualFailure(address, ManualWire.CONNECTED);
            return new ManualWire.Result(ManualWire.CONNECTED, flags & ~ManualWire.PENDING_TARGET);
        }
        // A half-open BT/iAP attachment is not a live CarPlay session. Only a
        // new explicit click for the same bonded HFP phone may retire it after
        // the initial handshake window. Passive queries and other phones do not.
        boolean staleAttach = sSession && !active && !sCpConnected && target && phone != null
            && btAttachStartedAt > 0 && now >= btAttachStartedAt
            && now - btAttachStartedAt >= STALE_BT_ATTACH_MS;
        if (staleAttach && action == ManualWire.QUERY)
            return new ManualWire.Result(ManualWire.READY, flags);
        if (staleAttach && action == ManualWire.RETRY && sIap2 != null
                && !manual.pending(address, now)) {
            if (SystemClock.elapsedRealtime() > deadline)
                return new ManualWire.Result(ManualWire.TIMED_OUT, flags);
            resetPhone("manual_stale_bt_attach");
            manualSppAddress = null;
        }
        if (active || sSession) {
            int status = target && manual.pending(address, now) ? ManualWire.ACCEPTED : ManualWire.BUSY;
            return new ManualWire.Result(status, flags);
        }
        if (phone == null) return new ManualWire.Result(ManualWire.PHONE_REQUIRED, flags);
        if (!isApple(phone)) return new ManualWire.Result(ManualWire.NOT_APPLE, flags);
        if (action == ManualWire.QUERY) {
            int last = manual.status(address, now);
            // Above checks describe current power/AP/phone authority. A previous
            // OFF or unavailable result must not impersonate a still-closed gate
            // after it has recovered and prevent a new explicit user retry.
            if (last == ManualWire.CONNECTED) last = ManualWire.READY;
            else if (last != ManualWire.READY && last != ManualWire.ACCEPTED && last != ManualWire.TIMED_OUT)
                last = ManualWire.FAILED;
            return new ManualWire.Result(last, flags);
        }
        if (manual.pending(address, now)) return new ManualWire.Result(ManualWire.ACCEPTED, flags);
        if (sIap2 == null) {
            if(!manual.begin(address,now))return new ManualWire.Result(ManualWire.BUSY,flags);
            final String selected=address;final long epoch=sEpoch, request=manual.generation();
            ManualConnectServer.guard(selected,epoch,request,new Runnable(){public void run(){scheduleSppConnect(selected);}});
            log("USB_CP_MANUAL event=preparing_iap");
            return new ManualWire.Result(ManualWire.ACCEPTED,flags|ManualWire.PENDING_TARGET);
        }
        if (SystemClock.elapsedRealtime() > deadline) return new ManualWire.Result(ManualWire.TIMED_OUT, flags);
        if (!manual.begin(address, now)) return new ManualWire.Result(ManualWire.BUSY, flags);
        log("USB_CP_MANUAL event=accepted");
        scheduleSppConnect(address);
        return new ManualWire.Result(ManualWire.ACCEPTED, flags | ManualWire.PENDING_TARGET);
    }

    static void manualFailure(String address, int status) {
        if (manual.pending(address, SystemClock.elapsedRealtime())) {
            manual.finish(address, status);
            log("USB_CP_MANUAL event=finished status=" + status);
        }
    }

    static void manualAttempt(String address, long epoch, long request, int gate, long observed, Runnable work) {
        long now = SystemClock.elapsedRealtime();
        if (request != manual.generation() || !manual.pending(address, now)) return;
        // Duplicate SPP callbacks may have queued two gate checks. The first
        // successful attach advances the epoch; the second is harmless progress,
        // not a failure of the same selected phone's in-flight session.
        if (address.equals(sAddr) && (sSession || sCpClient || sCpWireless || sCp != null)) return;
        if (epoch != sEpoch) { manualFailure(address, ManualWire.BUSY); return; }
        if (now < observed || now - observed > 350) { manualFailure(address, ManualWire.TIMED_OUT); return; }
        if (gate == ManualWire.AP_UNAVAILABLE || (gate == ManualWire.READY && (!apReady() || sIap2 == null))) {
            if(!enabled() || sUsb) { manualFailure(address, ManualWire.BUSY); return; }
            final String selected=address;final long token=epoch, generation=request;final Runnable next=work;
            handler().postDelayed(new Runnable(){public void run(){
                if(manual.pending(selected,SystemClock.elapsedRealtime())&&manual.generation()==generation)
                    ManualConnectServer.guard(selected,token,generation,next);
            }},1000);
            return;
        }
        if (gate != ManualWire.READY) { manualFailure(address, gate); return; }
        if (!enabled() || sUsb || sSession || sCpWireless || sCpClient || sCp != null) {
            manualFailure(address, ManualWire.BUSY); return;
        }
        if (!apReady()) { manualFailure(address, ManualWire.AP_UNAVAILABLE); return; }
        if (currentBondedPhone(address) == null) { manualFailure(address, ManualWire.PHONE_REQUIRED); return; }
        if (SystemClock.elapsedRealtime() - observed > 350) { manualFailure(address, ManualWire.TIMED_OUT); return; }
        manualAuthorizationUntil = observed + 350;
        try { work.run(); } finally { manualAuthorizationUntil = 0; }
    }

    static void onSppUp(final String addr) {
        if (sSession || sCpClient || sCpWireless) return;
        // Native SPP callbacks can arrive after a request timed out or after OFF.
        // Only a same-request, freshly authorized callback may continue a manual attempt.
        if (addr != null && addr.equals(manualSppAddress)) {
            if (!manual.pending(addr, SystemClock.elapsedRealtime()) || manualSppRequest != manual.generation()) return;
            ManualConnectServer.guard(addr, sEpoch, manualSppRequest, new Runnable() {
                public void run() { onSppUpAuthorized(addr); }
            });
        } else onSppUpAuthorized(addr);
    }

    static void onSppUpAuthorized(final String addr) {
        if (addr == null || sSession || currentBondedPhone(addr) == null) return;
        if (sCpClient || sCpWireless || !enabled() || sUsb || !apReady()) return;
        sAddr = addr;
        final long epoch = invalidatePending();
        sSession = true;
        btAttachStartedAt = SystemClock.elapsedRealtime();
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) {
                        log("no context, cannot start session");
                        sSession = false;
                        return;
                    }
                    if (epoch != sEpoch || !addr.equals(sAddr)) return;
                    if (sUsb || !apReady() || !enabled() || currentBondedPhone(addr) == null) {
                        resetPhone("attach_phone_unavailable"); return;
                    }
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_BT_CARPLAY);
                    i.putExtra("serial", addr);
                    sRequestedBtEpoch = epoch;
                    c.sendBroadcast(i);
                    log("USB_CP_LIFECYCLE attach_requested=1");
                } catch (Throwable t) {
                    sSession = false;
                    error("onSppUp", t);
                }
            }
        });
    }

    static void onUsbGone() {
        try {
            HfDeviceBTCMManager m = sBtcm;
            BluetoothDevice d = m == null ? null : m.IbtcmGetHfpConnectedDevice();
            if (d != null && d.getBondState() == BluetoothDevice.BOND_BONDED) scheduleSppConnect(d.getAddress());
        } catch (Throwable t) { error("onUsbGone", t); }
    }

    static void onCpSessionCreated(Object cp) {
        if (cp == null) return;
        boolean newObject = cp != sCp;
        if (sCp != null && cp != sCp) {
            // A new object can arrive before the previous object's finalize callback.
            // Its own first iAP2 packet must request a fresh component-5 channel.
            boolean hadClient = sCpClient;
            sCpClient = false;
            sCpConnected = false;
            sCpWireless = false;
            synchronized (sPendingIn) { sPendingIn.clear(); }
            if (hadClient) sendDetach();
        }
        if (newObject) {
            try { CpRawLanes.start(); } catch (Throwable ignored) {}
            CarPlayMedia.reset();
            long epoch = sEpoch;
            sCpBtEpoch = sSession && !sUsb && sAddr != null && sConfirmedBtEpoch == epoch
                && enabled() && apReady() ? epoch : -1;
            // A replacement session needs a fresh BT acknowledgement of its own.
            sConfirmedBtEpoch = -1;
            sRequestedBtEpoch = -1;
        }
        sCp = cp;
        log("CarPlay session created (usb=" + sUsb + " btAddr=" + sAddr + ")");
        if ("created".equals(prop("persist.ev6.cpwl.handover", "data")) && !sUsb && sAddr != null) {
            startCpClient("session created");
        }
    }

    static void onCpSessionStarted(Object cp, int transport) {
        if (cp == null || cp != sCp) { log("USB_CP_STALE_CALLBACK session_started"); return; }
        boolean dongleWireless = transport == 0 && sCpBtEpoch == sEpoch && sAddr != null
            && (sSession || sCpClient) && !sUsb && enabled() && apReady();
        sCpWireless = !sUsb && (transport == 1 || dongleWireless);
        if (dongleWireless) log("USB_CP_TRANSPORT_INFERRED raw=0 effective=wireless source=bt4_usb_ap");
        log("CarPlay session started transport=" + transport + " wireless=" + sCpWireless);
        CarPlayMedia.ready(false);
    }

    /** iAP2 bytes from the phone over the CarPlay channel: to the iAP2 server (buffered until its session is up) */
    static void onCpIapIn(Object cp, byte[] data, int len) {
        try {
            if (cp == null || cp != sCp) { log("USB_CP_STALE_CALLBACK iap_data"); return; }
            if (data == null || len <= 0 || sUsb) return;
            if (len >= data.length) len = data.length;
            byte[] b = new byte[len];
            System.arraycopy(data, 0, b, 0, len);
            if (!sCpClient) startCpClient("iAP2 data from CarPlay channel");
            IIap2Service s = sIap2;
            if (sCpConnected && s != null) {
                flushPending(s);
                s.Iap2SvcTransportReadDone(b, len);
            } else {
                synchronized (sPendingIn) {
                    if (sPendingIn.size() < MAX_PENDING) sPendingIn.add(b);
                }
            }
            // Optional observation follows original transport delivery. Parsing and
            // lane rendering are confined to separate bounded workers.
            try { CpRawLanes.observe(cp,b,len); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            error("onCpIapIn", t);
        }
    }

    static void onCpSessionFinalized(Object cp, int type) {
        try {
            if (cp == null || cp != sCp) { log("USB_CP_STALE_CALLBACK session_finalized"); return; }
            log("CarPlay session finalized type=" + type + " cpClient=" + sCpClient);
            boolean client = sCpClient, wireless = sCpWireless, session = sSession;
            invalidatePending();
            sCp = null;
            CarPlayMedia.reset();
            sAddr = null;
            sSession = false;
            sCpWireless = false;
            sCpClient = false;
            sCpConnected = false;
            synchronized (sPendingIn) {
                sPendingIn.clear();
            }
            if (client || session) sendDetach();
            if (client || wireless || session) onUsbGone();
        } catch (Throwable t) {
            error("onCpSessionFinalized", t);
        }
    }

    /** DiPO's iAP2 listener: ignore the Bluetooth and CarPlay-channel sessions ending while wireless CarPlay is up */
    static boolean dipoIgnoreDisconnect(int component) {
        boolean ignore = (sCpClient || sCpWireless) && (component == COMPONENT_BT_CARPLAY || component == COMPONENT_CARPLAY_CLIENT);
        if (ignore) log("DiPO: ignoring iAP2 disconnect comp=" + component + " (wireless CarPlay up)");
        return ignore;
    }

    /** DiPO's iAP2 listener: the CarPlay-channel session's identification isn't a new phone */
    static boolean dipoDropNoti(int what) {
        boolean drop = sCpClient && (what == NOTI_IDENTIFICATION_ACCEPTED || what == NOTI_TRANSPORT_IDENTIFIER);
        if (drop) log("DiPO: dropping iAP2 noti " + what + " from the CarPlay-channel session");
        return drop;
    }

    static synchronized void startCpClient(final String why) {
        if (sCpClient) return;
        sCpClient = true;
        sCpConnected = false;
        final String addr = sAddr;
        final Object cp = sCp;
        final long epoch = sEpoch;
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (epoch != sEpoch || sUsb || cp == null || cp != sCp || !sCpClient) return;
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_CARPLAY_CLIENT);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    log("iAP2 handover to CarPlay channel requested (" + why + ")");
                } catch (Throwable t) {
                    error("startCpClient", t);
                }
            }
        });
    }

    static void onCpClientConnected() {
        sCpConnected = true;
        IIap2Service s = sIap2;
        if (s != null) flushPending(s);
        CarPlayMedia.ready(false);
    }

    private static void flushPending(IIap2Service s) {
        ArrayList<byte[]> all;
        synchronized (sPendingIn) {
            if (sPendingIn.isEmpty()) return;
            all = new ArrayList<byte[]>(sPendingIn);
            sPendingIn.clear();
        }
        for (byte[] b : all) {
            try {
                s.Iap2SvcTransportReadDone(b, b.length);
            } catch (Throwable t) {
                error("flushPending", t);
            }
        }
        log("flushed " + all.size() + " buffered CarPlay-channel packets");
    }

    private static synchronized Handler cpTx() {
        if (sCpTx == null) {
            HandlerThread t = new HandlerThread("hmgapi-cp-tx");
            t.start();
            sCpTx = new Handler(t.getLooper());
        }
        return sCpTx;
    }

    private static Method nativeSend() {
        if (sNativeSend == null) sNativeSend = Points.method(cl, "dipo.carplay.native_iap_send");
        return sNativeSend;
    }

    /** iAP2 bytes from the server for the CarPlay channel: out through the CarPlay library */
    static void writeCp(byte[] data) {
        final Object cp = sCp;
        if (cp == null || data == null || data.length == 0 || !sCpClient) return;
        final byte[] b = data.clone();
        final long epoch = sEpoch;
        cpTx().post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (epoch != sEpoch || cp != sCp || !sCpClient) return;
                    Object r = nativeSend().invoke(cp, b, b.length);
                    int rc = r instanceof Integer ? (Integer) r : -1;
                    if (rc == 0) {
                        sCpSent++;
                        if (sCpSent <= 3 || sCpSent % 200 == 0) log("CarPlay-channel iAP2 sent " + b.length + " bytes (#" + sCpSent + ")");
                    } else {
                        sCpFail++;
                        if (sCpFail <= 5 || sCpFail % 50 == 0) log("CarPlay-channel iAP2 send failed rc=" + rc + " len=" + b.length);
                    }
                } catch (Throwable t) {
                    error("writeCp native", t);
                }
            }
        });
    }

    static void onSppDown(String addr) {
        if (addr == null || !addr.equals(sAddr)) return;
        // First Wi-Fi iAP2 data can arrive before session_started. Preserve that
        // handover too; explicit unbonding and session_finalized clear it.
        if (sCpWireless || (sCpClient && sCp != null)) {
            sSession = false;
            startCpClient("bluetooth dropped during wireless CarPlay");
            return;
        }
        resetPhone("spp_disconnected");
        onUsbGone();
    }

    static void sendDetach() {
        final long epoch = sEpoch;
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    if (sUsb || epoch != sEpoch) return;
                    Intent i = new Intent(ACTION_DETACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    c.sendBroadcast(i);
                    log("BT_DETACHED sent");
                } catch (Throwable t) {
                    error("sendDetach", t);
                }
            }
        });
    }

    /** SPP data from the phone: to the iAP2 server */
    static void onSppData(BluetoothDevice d, byte[] data, int len) {
        try {
            String addr = sAddr;
            IIap2Service s = sIap2;
            if (addr == null || s == null || d == null || data == null || len <= 0 || !sSession || !addr.equals(d.getAddress())) return;
            if (len >= data.length) len = data.length;
            if (len != data.length) {
                byte[] b = new byte[len];
                System.arraycopy(data, 0, b, 0, len);
                data = b;
            }
            s.Iap2SvcTransportReadDone(data, len);
        } catch (Throwable t) {
            error("onSppData", t);
        }
    }

    /** iAP2 bytes from the server for the Bluetooth session: out over SPP */
    static void writeSpp(byte[] data) {
        try {
            String addr = sAddr;
            HfDeviceBTCMManager m = sBtcm;
            if (addr == null || m == null || data == null || data.length == 0 || !sSession) return;
            if (!m.IbtcmSppOutPutStreamWrite(addr, data)) log("SPP write failed, " + data.length + " bytes");
        } catch (Throwable t) {
            error("writeSpp", t);
        }
    }

    static boolean apReady() {
        try { return usbApReady != null && Boolean.TRUE.equals(usbApReady.call()); }
        catch (Exception e) { return false; }
    }
    static void usbApChanged() {
        handler().post(new Runnable() { public void run() {
            try {
                if (!apReady()) {
                    // AP unplug terminates a pending BT setup. Active Wi-Fi transport notices link loss itself.
                    if (!sCpWireless && !sCpClient && sSession) resetPhone("ap_unavailable");
                    return;
                }
                HfDeviceBTCMManager m=sBtcm;
                if(m==null || !enabled() || sUsb || sCpWireless)return;
                BluetoothDevice d=m.IbtcmGetHfpConnectedDevice();
                if (d != null) scheduleSppConnect(d.getAddress());
            } catch(Throwable e) { log("USB_CP_RETRY_FAILED"); }
        }});
    }

    /** Broadcom's status callbacks: an iPhone connects (start SPP), SPP up/down, SPP data */
    static final class BtListener extends HfDeviceStatusListener {
        @Override
        public void onBTCMDeviceConnectionEventChanged(BluetoothDevice d, int event, boolean[] profiles) {
            if (event != EVENT_BTCM_CONNECTED) return;
            try {
                if (d != null) {
                    log("Bluetooth phone connected: " + d.getAddress());
                    scheduleSppConnect(d.getAddress());
                }
            } catch (Throwable t) {
                error("connEvent", t);
            }
        }

        @Override
        public void onBTCMSppStatusChanged(BluetoothDevice d, int status) {
            if (d == null) return;
            try {
                String addr = d.getAddress();
                log("SPP status " + status + " for " + addr);
                if (status == SPP_CONNECTED) onSppUp(addr);
                else if (status == SPP_DISCONNECTED) onSppDown(addr);
            } catch (Throwable t) {
                error("sppStatus", t);
            }
        }

        @Override
        public void onBTCMSppInPutStreamChanged(BluetoothDevice d, byte[] buffer, int length) {
            onSppData(d, buffer, length);
        }
    }

    /** the iAP2 server's side: USB and CarPlay-channel sessions, and transport data to send */
    static final class TxListener extends IIap2Listener.Stub {
        @Override
        public void connected(int component, String serial) {
            log("iAP2 connected comp=" + component);
            // what DiPO keeps as the current device (setCurrentIapDeviceName): a Bluetooth address is the notice's key
            if ((component == COMPONENT_USB || component == COMPONENT_BT_CARPLAY) && serial != null && serial.contains(":")) sLastBtId = serial;
            if (component == COMPONENT_USB) {
                sUsb = true;
                CarPlayMedia.reset();
                invalidatePending();
                sSession = false;
                sCpClient = false;
                sCpConnected = false;
                sCpWireless = false;
                sAddr = null;
                synchronized (sPendingIn) { sPendingIn.clear(); }
            }
            else if (component == COMPONENT_BT_CARPLAY) {
                long epoch = sEpoch;
                if (sUsb || !sSession || serial == null || !serial.equalsIgnoreCase(sAddr)) {
                    sConfirmedBtEpoch = sCpBtEpoch = -1;
                } else if (sRequestedBtEpoch == epoch) {
                    sConfirmedBtEpoch = epoch;
                    log("USB_CP_BT_BOOTSTRAP confirmed");
                }
            }
            else if (component == COMPONENT_CARPLAY_CLIENT) onCpClientConnected();
        }

        @Override
        public void disconnected(int component) {
            log("iAP2 disconnected comp=" + component);
            if (component == COMPONENT_USB) {
                sUsb = false;
                onUsbGone();
            } else if (component == COMPONENT_BT_CARPLAY) {
                sSession = false;
                sRequestedBtEpoch = sConfirmedBtEpoch = sCpBtEpoch = -1;
            } else if (component == COMPONENT_CARPLAY_CLIENT) {
                sCpConnected = false;
                CarPlayMedia.reset();
            }
        }

        @Override
        public void notify(int what, int a1, int a2, Iap2NotiMsg msg) {
            if ((what != NOTI_BT_TRANSPORT_DATA && what != NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) || msg == null) return;
            try {
                Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
                if (g == null) return;
                for (int k = 0; k < g.getItemCnt(); k++) {
                    Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                    if (it == null || it.getItemParamType() != OBJ_BLOB) continue;
                    if (what == NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) writeCp(it.getItemByteArray());
                    else writeSpp(it.getItemByteArray());
                }
            } catch (Throwable t) {
                error("notify", t);
            }
        }
    }
}
