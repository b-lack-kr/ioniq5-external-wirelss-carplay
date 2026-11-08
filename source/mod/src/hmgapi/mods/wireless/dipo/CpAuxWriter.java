package hmgapi.mods.wireless.dipo;

import java.util.Arrays;

/** Bounded, independently scheduled lane/pattern output. Never calls Binder under its lock. */
public final class CpAuxWriter {
    public interface Executor { boolean post(Runnable work); }
    public interface Sink {
        boolean lane(byte[] frame);
        boolean pattern(int[] values);
    }
    public interface Gate { boolean allowed(long epoch); }
    public interface Audit { void failed(String channel); }

    private final Object lock = new Object();
    private final Executor executor;
    private final Sink sink;
    private final Gate gate;
    private final Audit audit;
    private final boolean[] failed = new boolean[4];
    private long epoch = Long.MIN_VALUE, revision;
    private Frame latest, pending;
    private byte[] sentLane;
    private int[] sentPattern;
    private boolean scheduled;

    private static final class Frame {
        final byte[] lane;
        final int[] pattern;
        final long epoch, revision;
        Frame(byte[] lane, int[] pattern, long epoch, long revision) {
            this.lane = lane; this.pattern = pattern; this.epoch = epoch; this.revision = revision;
        }
    }

    public CpAuxWriter(Executor executor, Sink sink, Gate gate, Audit audit) {
        if (executor == null || sink == null || gate == null) throw new IllegalArgumentException("worker");
        this.executor = executor; this.sink = sink; this.gate = gate; this.audit = audit;
    }

    /** Both channels are complete desired state, including explicit clear frames. */
    public void submit(byte[] lane, int[] pattern, long ownerEpoch) {
        if (lane == null || lane.length > 512 || pattern == null || pattern.length != 5)
            throw new IllegalArgumentException("complete bounded snapshot required");
        byte[] laneCopy = lane.clone();
        int[] patternCopy = pattern.clone();
        boolean post = false;
        synchronized (lock) {
            if (ownerEpoch < epoch) return;
            if (ownerEpoch > epoch) invalidateLocked(ownerEpoch);
            // Repeated identical input must not starve a write already in progress.
            if (latest == null || !Arrays.equals(latest.lane, laneCopy)
                    || !Arrays.equals(latest.pattern, patternCopy)) revision++;
            latest = pending = new Frame(laneCopy, patternCopy, epoch, revision);
            if (!scheduled) { scheduled = true; post = true; }
        }
        if (post) {
            boolean accepted = false;
            try { accepted = executor.post(new Runnable() { public void run() { drain(); } }); }
            catch (RuntimeException | LinkageError ignored) { }
            if (!accepted) {
                synchronized (lock) { scheduled = false; }
                failure(2, "queue"); // Retry only on the next actual input event.
            } else success(2);
        }
    }

    public void invalidate(long ownerEpoch) {
        synchronized (lock) {
            if (ownerEpoch >= epoch) invalidateLocked(ownerEpoch);
        }
    }

    private void invalidateLocked(long ownerEpoch) {
        epoch = ownerEpoch;
        revision++;
        latest = pending = null;
        sentLane = null; sentPattern = null;
        // A queued/running worker retains ownership. No second worker while an IPC is blocked.
    }

    private boolean currentLocked(Frame f) {
        return f.epoch == epoch && latest != null && f.revision == latest.revision;
    }

    private boolean allowed(Frame f) {
        synchronized (lock) { if (!currentLocked(f)) return false; }
        boolean ok;
        try { ok = gate.allowed(f.epoch); success(3); }
        catch (RuntimeException | LinkageError ignored) { failure(3, "gate"); return false; }
        synchronized (lock) { return ok && currentLocked(f); }
    }

    private void drain() {
        for (;;) {
            Frame f;
            synchronized (lock) {
                f = pending;
                pending = null;
                if (f == null) { scheduled = false; return; }
            }
            if (!allowed(f)) continue;
            boolean sendLane;
            synchronized (lock) { sendLane = currentLocked(f) && !Arrays.equals(sentLane, f.lane); }
            if (sendLane && allowed(f)) {
                boolean ok = false;
                try { ok = sink.lane(f.lane.clone()); } catch (RuntimeException | LinkageError ignored) { }
                if (ok) {
                    synchronized (lock) { if (currentLocked(f)) sentLane = f.lane; }
                    success(0);
                } else failure(0, "lane");
            }
            // Recheck after lane IPC. A new owner or newer input invalidates the other old channel.
            if (!allowed(f)) continue;
            boolean sendPattern;
            synchronized (lock) {
                sendPattern = currentLocked(f) && !Arrays.equals(sentPattern, f.pattern);
            }
            if (sendPattern && allowed(f)) {
                boolean ok = false;
                try { ok = sink.pattern(f.pattern.clone()); } catch (RuntimeException | LinkageError ignored) { }
                if (ok) {
                    synchronized (lock) { if (currentLocked(f)) sentPattern = f.pattern; }
                    success(1);
                } else failure(1, "pattern");
            }
            // One in-flight snapshot + latest pending snapshot; no timers or self-retry loop.
        }
    }

    private void success(int channel) { synchronized (lock) { failed[channel] = false; } }

    private void failure(int channel, String name) {
        boolean report;
        synchronized (lock) { report = !failed[channel]; failed[channel] = true; }
        if (report && audit != null) {
            try { audit.failed(name); } catch (RuntimeException | LinkageError ignored) { }
        }
    }
}
