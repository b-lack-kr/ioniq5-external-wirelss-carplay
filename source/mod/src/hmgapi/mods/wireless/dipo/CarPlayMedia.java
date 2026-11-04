package hmgapi.mods.wireless.dipo;

import com.tcc.iap2.IIap2Service;

/** Ask the iPhone for Now Playing on the active wireless iAP2 channel.
 * Stock DiPO already registers for media, playback and artwork, but its own
 * setStartiAP2Service(transport) explicitly skips Start for wireless sessions. */
final class CarPlayMedia implements Runnable {
    private static final int MAX_ATTEMPTS = 8;
    private static CarPlayMedia current;
    private static Object listeningSession;

    private final Object session;
    private final IIap2Service server;
    private int attempts;
    private boolean identificationSeen;
    private boolean queued;
    private boolean started;
    private int received;

    private CarPlayMedia(Object session, IIap2Service server) {
        this.session = session;
        this.server = server;
    }

    private static boolean active() {
        return Bridge.sCp != null && listeningSession == Bridge.sCp && Bridge.sCpWireless && Bridge.sCpClient && Bridge.sCpConnected
                && !Bridge.sUsb && Dipo.iap2 != null && Dipo.iap2 == Bridge.sIap2 && Bridge.enabled();
    }

    static synchronized void reset() {
        current = null;
        listeningSession = null;
    }

    // Stock and bridge binder listeners can see connected(5) in either order.
    static synchronized void stockListenerReady() {
        if (!Bridge.sCpClient || Bridge.sUsb || Bridge.sCp == null) return;
        listeningSession = Bridge.sCp;
        ready(false);
    }

    static synchronized void ready(boolean identified) {
        if (!active()) return;
        if (current == null || current.session != Bridge.sCp || current.server != Dipo.iap2) {
            current = new CarPlayMedia(Bridge.sCp, Dipo.iap2);
        }
        if (identified && !current.identificationSeen) {
            current.identificationSeen = true;
            current.attempts = 0; // The phone may finish identification after the attach retry window.
        }
        if (!current.started && !current.queued && current.attempts < MAX_ATTEMPTS) {
            current.queued = true;
            Bridge.handler().post(current);
        }
    }

    private boolean live() {
        return current == this && active() && Bridge.sCp == session && Dipo.iap2 == server;
    }

    @Override public void run() {
        synchronized (CarPlayMedia.class) {
            if (!live() || started) { queued = false; return; }
            attempts++;
        }
        boolean ok = false;
        try {
            ok = server.Iap2SvcNowPlayingStart();
        } catch (Exception e) {
            Dipo.log("USB_CP_MEDIA binder request failed");
        }
        synchronized (CarPlayMedia.class) {
            if (!live()) { queued = false; return; }
            if (ok) {
                started = true;
                queued = false;
                Dipo.log("USB_CP_MEDIA now_playing_requested");
            } else if (attempts < MAX_ATTEMPTS) {
                Bridge.handler().postDelayed(this, 500);
            } else {
                queued = false;
                Dipo.log("USB_CP_MEDIA now_playing_not_ready");
            }
        }
    }

    /** Observe delivery to DiPO's own listener without recording any media contents. */
    static synchronized void receipt(int what) {
        if (what < 36 || what > 38 || current == null || !current.live()) return;
        int bit = 1 << (what - 36);
        if ((current.received & bit) != 0) return;
        current.received |= bit;
        Dipo.log("USB_CP_MEDIA received_type=" + what);
    }
}
