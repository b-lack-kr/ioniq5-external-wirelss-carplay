// SPDX-License-Identifier: AGPL-3.0-or-later
package hmgapi.mods.wireless.dipo;

import java.lang.ref.WeakReference;

/** Immediate, change-driven supplement to the OEM one-second PASCD heartbeat.
 * One queued task and one in-flight send at most; bursts coalesce to the latest
 * actual OEM state. Repeated raw speed callbacks do not add another send.
 * There is no timer or artificial inter-send delay.
 * No values or timestamps are manufactured here: Target calls the OEM formatter.
 * An already accepted Binder call cannot be revoked by a later stop event. */
final class VehicleSpeedPulse {
    interface Worker {
        boolean post(Runnable work, long delay);
        void cancel(Runnable work);
    }
    interface Target {
        boolean allowed(Object owner);
        /** Opaque CarPlay generation, including a created-but-not-started session. */
        Object identity(Object owner);
        void send(Object owner, Validity current) throws Exception;
        void failed();
    }
    interface Validity { boolean valid(); }
    private static final class Session {
        final WeakReference<Object> owner;
        boolean dirty, failed, hasValue;
        int lastValue;
        Object transport;
        Session(Object owner,Object transport) {
            this.owner = new WeakReference<Object>(owner);this.transport=transport;
        }
    }
    private final Worker worker;
    private final Target target;
    private Session session;
    private Runnable pending;
    private boolean inFlight;

    VehicleSpeedPulse(Worker worker, Target target) { this.worker=worker; this.target=target; }

    synchronized void start(Object owner) {
        cancel();
        session = owner == null ? null : new Session(owner,target.identity(owner));
    }
    synchronized void stop(Object owner) {
        if (session != null && session.owner.get() == owner) {
            cancel();
            session = null;
        }
    }
    synchronized void changed(Object owner, int value) {
        Session expected=session;
        if(expected==null||expected.failed||owner==null||expected.owner.get()!=owner
                ||!target.allowed(owner))return;
        // Location can be requested by the bootstrap channel before CarPlay is
        // created. Bind that request once, at its first active speed callback.
        // Never rebind an already-bound request to a replacement CarPlay session.
        if(expected.transport==null)expected.transport=target.identity(owner);
        if (!eligible(expected, owner)) return;
        if (expected.hasValue && expected.lastValue == value) return;
        expected.hasValue=true;
        expected.lastValue=value;
        expected.dirty=true;
        schedule(expected);
    }
    private boolean eligible(Session expected, Object owner) {
        return expected != null && session == expected && !expected.failed
                && owner != null && expected.owner.get() == owner && target.allowed(owner)
                && expected.transport!=null && expected.transport==target.identity(owner);
    }
    private void cancel() {
        if (pending != null) { worker.cancel(pending); pending=null; }
    }
    private void schedule(final Session expected) {
        if (pending != null || inFlight || !expected.dirty) return;
        final Object owner=expected.owner.get();
        if (!eligible(expected, owner)) return;
        pending=new Runnable(){ public void run(){ perform(expected, this); } };
        if (!worker.post(pending,0)) {
            pending=null; expected.failed=true; target.failed();
        }
    }
    private void perform(final Session expected, Runnable work) {
        final Object owner;
        synchronized(this) {
            if (pending != work) return;
            pending=null;
            owner=expected.owner.get();
            if (!eligible(expected,owner) || inFlight) return;
            expected.dirty=false; inFlight=true;
        }
        boolean failed=false;
        try {
            // Formatting may overlap a stop/restart on the same OEM manager.
            // Carry this request's identity through to the final send check.
            target.send(owner,new Validity(){public boolean valid(){
                synchronized(VehicleSpeedPulse.this){return eligible(expected,owner);}
            }});
        } catch (Exception failure) { failed=true; }
        finally {
            synchronized(this) {
                inFlight=false;
                if (failed) { expected.failed=true; target.failed(); }
                // A new session can have acquired a dirty change while an old
                // call was finishing. It gets one fresh task, never the old data.
                if (session != null) schedule(session);
            }
        }
    }
}
