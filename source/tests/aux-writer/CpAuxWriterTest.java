package hmgapi.mods.wireless.dipo;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CpAuxWriterTest {
    static int checks;
    static void check(boolean v, String label) { checks++; if (!v) throw new AssertionError(label); }
    static byte[] lane(int n) { return new byte[] {(byte)241, (byte)n}; }
    static int[] pattern(int n) { return new int[] {n, n, 65535, 0, 0}; }
    static class Queue implements CpAuxWriter.Executor {
        Runnable work;
        int posts;
        boolean reject;
        public boolean post(Runnable r) {
            posts++;
            if (reject) return false;
            if (work != null) throw new AssertionError("more than one task");
            work = r; return true;
        }
        void run() { Runnable r = work; work = null; if (r != null) r.run(); }
    }
    static class Output implements CpAuxWriter.Sink {
        final List<Integer> lanes = Collections.synchronizedList(new ArrayList<Integer>());
        final List<Integer> patterns = Collections.synchronizedList(new ArrayList<Integer>());
        boolean failLane, failPattern, throwLane, throwPattern, mutate;
        public boolean lane(byte[] f) {
            lanes.add(f[1] & 255);
            if (mutate) f[1] = 99;
            if (throwLane) throw new IllegalStateException("lane IPC");
            return !failLane;
        }
        public boolean pattern(int[] f) {
            patterns.add(f[0]);
            if (mutate) f[0] = 99;
            if (throwPattern) throw new NoSuchMethodError("optional API");
            return !failPattern;
        }
    }
    static final CpAuxWriter.Gate YES = new CpAuxWriter.Gate() {
        public boolean allowed(long epoch) { return true; }
    };
    static void await(CountDownLatch l) {
        try { if (!l.await(3, TimeUnit.SECONDS)) throw new AssertionError("blocked"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    static void finish(Thread t) {
        try { t.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
        check(!t.isAlive(), "thread finishes");
    }
    static void basic() {
        Queue q = new Queue(); Output out = new Output();
        CpAuxWriter w = new CpAuxWriter(q, out, YES, null);
        byte[] l = lane(1); int[] p = pattern(1);
        w.submit(l, p, 1); l[1] = 77; p[0] = 77;
        check(out.lanes.isEmpty(), "submit does not call sink");
        q.run();
        check(out.lanes.equals(Arrays.asList(1)) && out.patterns.equals(Arrays.asList(1)), "immutable snapshot");
        w.submit(lane(1), pattern(1), 1); q.run();
        check(out.lanes.size() == 1 && out.patterns.size() == 1, "successful dedup");
        for (int i = 2; i < 2000; i++) w.submit(lane(i % 240), pattern(i), 1);
        check(q.posts == 3, "coalesces pending runnable");
        q.run();
        check(out.patterns.equals(Arrays.asList(1, 1999)), "latest pending only");
        w.invalidate(2);
        w.submit(lane(4), pattern(4), 1); q.run();
        check(out.patterns.size() == 2, "old owner rejected");
        w.submit(lane(1), pattern(1), 2); q.run();
        check(out.patterns.size() == 3, "new owner resets successful cache");
        w.submit(lane(7), pattern(7), 2); w.invalidate(2); q.run();
        check(out.patterns.size() == 3, "same epoch invalidate cancels pending");
        w.submit(lane(0), new int[] {65535,65535,65535,0,0}, 3); q.run();
        check(out.lanes.get(3) == 0 && out.patterns.get(3) == 65535, "explicit clear");
    }
    static void failure() {
        Queue q = new Queue(); Output out = new Output(); final List<String> errors = new ArrayList<String>();
        CpAuxWriter w = new CpAuxWriter(q, out, YES, new CpAuxWriter.Audit() {
            public void failed(String name) { errors.add(name); throw new IllegalStateException("audit"); }
        });
        out.throwLane = true;
        w.submit(lane(1), pattern(1), 1); q.run();
        check(out.patterns.size() == 1, "lane failure does not block pattern");
        check(q.work == null, "no failure retry loop");
        w.submit(lane(1), pattern(1), 1); q.run();
        check(out.lanes.size() == 2 && out.patterns.size() == 1, "only failed channel retries on input");
        check(errors.equals(Arrays.asList("lane")), "bounded repeated failure audit");
        out.throwLane = false; w.submit(lane(1), pattern(1), 1); q.run();
        out.throwPattern = true; w.submit(lane(2), pattern(2), 1); q.run();
        check(out.lanes.get(out.lanes.size() - 1) == 2, "pattern linkage failure isolated");
        out.throwPattern = false; out.mutate = true;
        w.submit(lane(3), pattern(3), 1); q.run();
        int nl = out.lanes.size(), np = out.patterns.size();
        w.submit(lane(3), pattern(3), 1); q.run();
        check(out.lanes.size() == nl && out.patterns.size() == np, "sink cannot mutate cache");
        q.reject = true; w.submit(lane(4), pattern(4), 1);
        check(errors.contains("queue") && out.patterns.size() == np, "queue rejection bounded");
        q.reject = false; w.submit(lane(4), pattern(4), 1); q.run();
        check(out.patterns.get(np) == 4, "next input retries scheduling");
        out.failLane = true; out.failPattern = true;
        w.submit(lane(5), pattern(5), 1); q.run();
        out.failLane = false; out.failPattern = false;
        w.submit(lane(5), pattern(5), 1); q.run();
        check(out.patterns.subList(out.patterns.size()-2, out.patterns.size()).equals(Arrays.asList(5,5)), "false acknowledgement retries");
    }
    static void blockedGate() {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean(true);
        Queue q = new Queue(); Output out = new Output();
        final CpAuxWriter w = new CpAuxWriter(q, out, new CpAuxWriter.Gate() {
            public boolean allowed(long epoch) {
                if (first.getAndSet(false)) { entered.countDown(); await(release); }
                return true;
            }
        }, null);
        w.submit(lane(1), pattern(1), 1);
        Thread worker = new Thread(new Runnable() { public void run() { q.run(); } }); worker.setDaemon(true); worker.start();
        await(entered);
        // If gate is under the worker lock these producers cannot finish before it is released.
        Thread producer = new Thread(new Runnable() { public void run() {
            w.invalidate(2); for (int i=0;i<10000;i++) w.submit(lane(2), pattern(2), 2);
        }}); producer.setDaemon(true); producer.start(); finish(producer);
        release.countDown(); finish(worker);
        check(out.lanes.equals(Arrays.asList(2)) && out.patterns.equals(Arrays.asList(2)), "gate stale recheck");
        check(q.posts == 1, "blocked gate never adds worker");
    }
    static void blockedSink() {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Queue q = new Queue();
        Output out = new Output() { public boolean lane(byte[] b) {
            boolean ok = super.lane(b);
            if (b[1] == 1) { entered.countDown(); await(release); }
            return ok;
        }};
        final CpAuxWriter w = new CpAuxWriter(q, out, YES, null);
        w.submit(lane(1), pattern(1), 1);
        Thread worker = new Thread(new Runnable() { public void run() { q.run(); } }); worker.setDaemon(true); worker.start();
        await(entered);
        Thread producer = new Thread(new Runnable() { public void run() {
            // Already accepted IPC cannot be revoked, but remaining old channels can.
            w.invalidate(2); for (int i=0;i<10000;i++) w.submit(lane(2), pattern(2), 2);
        }}); producer.setDaemon(true); producer.start(); finish(producer);
        release.countDown(); finish(worker);
        check(out.lanes.equals(Arrays.asList(1,2)), "one in flight then latest owner");
        check(out.patterns.equals(Arrays.asList(2)), "skip remaining old channel");
        check(q.posts == 1, "one worker despite blocked IPC");
    }
    static void inlineAndGateFailure() {
        Output out = new Output(); final AtomicBoolean allow = new AtomicBoolean(false);
        final List<String> errors = new ArrayList<String>();
        CpAuxWriter w = new CpAuxWriter(new CpAuxWriter.Executor() {
            public boolean post(Runnable r) { r.run(); return true; }
        }, out, new CpAuxWriter.Gate() {
            public boolean allowed(long e) { if (!allow.get()) throw new IllegalStateException(); return true; }
        }, new CpAuxWriter.Audit() { public void failed(String c) { errors.add(c); }});
        w.submit(lane(1), pattern(1), 1); w.submit(lane(1), pattern(1), 1);
        check(out.lanes.isEmpty() && errors.equals(Arrays.asList("gate")), "failing gate bounded, no output");
        allow.set(true); w.submit(lane(1), pattern(1), 1);
        check(out.lanes.equals(Arrays.asList(1)), "inline executor retry");
    }
    public static void main(String[] args) {
        basic(); failure(); blockedGate(); blockedSink(); inlineAndGateFailure();
        System.out.println("CpAuxWriterTest: " + checks + " passed");
    }
}
