// Adapted for NEEV USB CarPlay by blackr on 2026-11-07.
// Reference: HMG-Seogaemo v0.1.46, commit 26eeb97e0a745528b438d7a946be5450fadbfd04.
// https://github.com/seogaemo/hmg-seogaemo (AGPL-3.0-or-later).
package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;

/** Leased approach updates for the navigation-process receiver. */
public final class ApproachSender implements CpTurns.ApproachSink {
    public static final int SOURCE_CARPLAY = 1, SOURCE_ANDROID_AUTO = 2;
    static final String ACTION = "kr.ioniq5.usbcp.APPROACH_BAR";
    static final String TARGET_PACKAGE = "com.mnsoft.navi";
    static final long HEARTBEAT_MS = 1000L;
    static final long LEASE_MS = 3500L;

    public interface Clock { long now(); }
    public interface Owner { Object current(); }
    public interface Gate { boolean allowed(Object owner); }

    private final Context context;
    private final Handler handler;
    private final Clock clock;
    private final Owner owner;
    private final Gate gate;
    private final int source;
    private Object activeOwner;
    private boolean active, published;
    private int bar;
    private long lastSent = -1L;
    private Runnable pending;

    public ApproachSender(Context context, Handler handler, Clock clock, Owner owner, Gate gate) {
        this(context, handler, clock, owner, gate, SOURCE_CARPLAY);
    }

    public ApproachSender(Context context, Handler handler, Clock clock, Owner owner, Gate gate, int source) {
        if (source != SOURCE_CARPLAY && source != SOURCE_ANDROID_AUTO)
            throw new IllegalArgumentException("approach source");
        this.context = context;
        this.handler = handler;
        this.clock = clock;
        this.owner = owner;
        this.gate = gate;
        this.source = source;
    }

    @Override public synchronized void update(boolean requestedActive, int requestedBar) {
        if (!requestedActive || requestedBar < 0 || requestedBar > 100 || !available()) {
            release();
            return;
        }
        Object current = currentOwner();
        long now = now();
        if (current == null || !allowed(current) || now < 0L) {
            release();
            return;
        }
        // A real update can start a new lease. An old queued heartbeat cannot.
        if (active && activeOwner == current && bar == requestedBar && published
                && now >= lastSent && now - lastSent < HEARTBEAT_MS) return;
        cancel();
        activeOwner = current;
        active = true;
        bar = requestedBar;
        if (!sendActive(now)) release();
        else schedule(HEARTBEAT_MS);
    }

    private boolean available() {
        return context != null && handler != null && clock != null && owner != null && gate != null;
    }

    private Object currentOwner() {
        try { return owner == null ? null : owner.current(); }
        catch (Throwable unavailable) { return null; }
    }

    private long now() {
        try { return clock == null ? -1L : clock.now(); }
        catch (Throwable unavailable) { return -1L; }
    }

    private boolean allowed(Object expected) {
        try {
            return expected != null && currentOwner() == expected && gate.allowed(expected)
                    && currentOwner() == expected;
        } catch (Throwable unavailable) { return false; }
    }

    private boolean sendActive(long now) {
        if (!active || !allowed(activeOwner)) return false;
        if (!broadcast(true, bar)) return false;
        published = true;
        lastSent = now;
        return true;
    }

    private boolean broadcast(boolean enabled, int level) {
        try {
            Intent intent = new Intent(ACTION).setPackage(TARGET_PACKAGE);
            intent.putExtra("active", enabled);
            intent.putExtra("bar", level);
            intent.putExtra("source", source);
            // Receiver registration requires BLUETOOTH_PRIVILEGED from senders.
            // Its own ownership check remains authoritative at delivery time.
            context.sendBroadcast(intent);
            return true;
        } catch (Throwable unavailable) { return false; }
    }

    private void schedule(long delay) {
        final Runnable work = new Runnable() {
            @Override public void run() { heartbeat(this); }
        };
        pending = work;
        try {
            if (!handler.postDelayed(work, delay)) release();
        } catch (Throwable unavailable) { release(); }
    }

    private synchronized void heartbeat(Runnable work) {
        if (pending != work) return;
        pending = null;
        long now = now();
        if (!active || !allowed(activeOwner) || now < 0L || now < lastSent
                || now - lastSent >= LEASE_MS) {
            release();
            return;
        }
        long elapsed = now - lastSent;
        if (elapsed < HEARTBEAT_MS) {
            schedule(HEARTBEAT_MS - elapsed);
            return;
        }
        if (!sendActive(now)) release();
        else schedule(HEARTBEAT_MS);
    }

    private void cancel() {
        Runnable old = pending;
        pending = null;
        if (old != null && handler != null) {
            try { handler.removeCallbacks(old); } catch (Throwable ignored) {}
        }
    }

    private void release() {
        cancel();
        active = false;
        activeOwner = null;
        lastSent = -1L;
        boolean clear = published;
        published = false;
        if (clear) broadcast(false, 0);
        // If delivery fails, the receiver's independent 3500ms lease expires.
    }
}
