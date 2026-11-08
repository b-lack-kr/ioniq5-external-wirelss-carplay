package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;

public final class ApproachSenderTest {
    private static int checks;
    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    private static final class Fixture implements ApproachSender.Clock, ApproachSender.Owner, ApproachSender.Gate {
        final Context context = new Context();
        final Handler handler = new Handler();
        Object token = new Object();
        long time = 10L;
        boolean enabled = true, failOwner, failGate, failClock;
        final ApproachSender sender = new ApproachSender(context, handler, this, this, this);
        public long now() { if (failClock) throw new IllegalStateException(); return time; }
        public Object current() { if (failOwner) throw new IllegalStateException(); return token; }
        public boolean allowed(Object expected) {
            if (failGate) throw new IllegalStateException();
            return enabled && expected == token;
        }
        void fireAt(long next) { time = next; handler.take().work.run(); }
        Intent last() { return context.sent.get(context.sent.size() - 1); }
        void assertFrame(boolean active, int bar) {
            Intent frame = last();
            check(ApproachSender.ACTION.equals(frame.action), "exact action");
            check(ApproachSender.TARGET_PACKAGE.equals(frame.target), "only navigation package");
            check(Integer.valueOf(1).equals(frame.source), "CP source on every active/release frame");
            check(Boolean.valueOf(active).equals(frame.active) && Integer.valueOf(bar).equals(frame.bar), "typed frame values");
        }
    }

    private static void heartbeatAndChanges() {
        Fixture f = new Fixture();
        f.sender.update(true, 25);
        check(f.context.sent.size() == 1 && f.handler.queue.size() == 1, "immediate frame and one heartbeat");
        f.assertFrame(true, 25);
        check(f.handler.queue.get(0).delay == 1000L, "one-second schedule");
        f.time = 500L;
        f.sender.update(true, 25);
        check(f.context.sent.size() == 1 && f.handler.queue.size() == 1, "same-value updates do not flood");
        f.fireAt(1010L);
        check(f.context.sent.size() == 2, "heartbeat renews unchanged bar");
        f.assertFrame(true, 25);
        Runnable stale = f.handler.queue.get(0).work;
        f.time = 1100L;
        f.sender.update(true, 24);
        check(f.context.sent.size() == 3 && f.handler.queue.size() == 1, "real changed bar immediate");
        stale.run();
        check(f.context.sent.size() == 3 && f.handler.queue.size() == 1, "canceled callback cannot send");
        f.fireAt(1500L);
        check(f.context.sent.size() == 3 && f.handler.queue.get(0).delay == 600L, "early callback reschedules remaining interval");
        f.fireAt(2100L);
        f.assertFrame(true, 24);
        check(f.context.sent.size() == 4, "rescheduled heartbeat sends once");
    }

    private static void releaseAndOwnership() {
        Fixture f = new Fixture();
        f.token = null;
        f.sender.update(true, 40);
        check(f.context.sent.isEmpty() && f.handler.queue.isEmpty(), "no owner means no activation");
        f.token = new Object();
        f.enabled = false;
        f.sender.update(true, 40);
        check(f.context.sent.isEmpty(), "disabled gate blocks activation");
        f.enabled = true;
        f.sender.update(true, 40);
        Runnable stale = f.handler.queue.get(0).work;
        f.sender.update(false, Integer.MIN_VALUE);
        f.assertFrame(false, 0);
        check(f.handler.queue.isEmpty(), "explicit release cancels heartbeat");
        int count = f.context.sent.size();
        stale.run();
        f.sender.update(false, 0);
        check(f.context.sent.size() == count, "release is idempotent and stale callback inactive");
        f.sender.update(true, 30);
        f.token = new Object();
        f.fireAt(f.time + 1000L);
        f.assertFrame(false, 0);
        check(f.handler.queue.isEmpty(), "owner switch releases old frame without adopting new session");
        f.sender.update(true, 20);
        f.assertFrame(true, 20);
        f.enabled = false;
        f.fireAt(f.time + 1000L);
        f.assertFrame(false, 0);
        check(f.handler.queue.isEmpty(), "runtime gate loss releases");
    }

    private static void leaseAndClock() {
        Fixture f = new Fixture();
        f.sender.update(true, 100);
        f.fireAt(3509L);
        f.assertFrame(true, 100);
        check(f.handler.queue.size() == 1, "3499ms delay still within lease");
        f.fireAt(7009L);
        f.assertFrame(false, 0);
        check(f.handler.queue.isEmpty(), "3500ms delay expires and cannot resurrect old bar");
        f.sender.update(true, 0);
        f.assertFrame(true, 0);
        f.fireAt(7008L);
        f.assertFrame(false, 0);
        check(f.handler.queue.isEmpty(), "clock reversal expires heartbeat");
        f.sender.update(true, 20);
        f.failClock = true;
        f.handler.take().work.run();
        f.assertFrame(false, 0);
        check(f.handler.queue.isEmpty(), "clock failure releases");
    }

    private static void boundsAndFailures() {
        for (int invalid : new int[] { Integer.MIN_VALUE, -1, 101, Integer.MAX_VALUE }) {
            Fixture f = new Fixture();
            f.sender.update(true, invalid);
            check(f.context.sent.isEmpty(), "invalid level cannot start overlay");
            f.sender.update(true, 50);
            f.sender.update(true, invalid);
            f.assertFrame(false, 0);
            check(f.handler.queue.isEmpty(), "invalid level clears active overlay");
        }
        for (int valid : new int[] { 0, 1, 99, 100 }) {
            Fixture f = new Fixture();
            f.sender.update(true, valid);
            f.assertFrame(true, valid);
        }
        Fixture f = new Fixture();
        f.context.fail = true;
        f.sender.update(true, 50);
        check(f.context.sent.isEmpty() && f.handler.queue.isEmpty(), "broadcast error is fail-closed");
        f.context.fail = false;
        f.sender.update(true, 50);
        check(f.context.sent.size() == 1, "fresh update retries after transport recovery");
        f.context.fail = true;
        f.fireAt(1010L);
        check(f.handler.queue.isEmpty(), "failed renewal stops; receiver lease handles failed clear");
        f = new Fixture();
        f.handler.reject = true;
        f.sender.update(true, 50);
        check(f.context.sent.size() == 2 && f.handler.queue.isEmpty(), "queue rejection releases immediately");
        f.assertFrame(false, 0);
        f = new Fixture();
        f.handler.fail = true;
        f.sender.update(true, 50);
        f.assertFrame(false, 0);
        f = new Fixture();
        f.sender.update(true, 50);
        Runnable uncanceled = f.handler.queue.get(0).work;
        f.handler.failCancel = true;
        f.sender.update(false, 0);
        int count = f.context.sent.size();
        uncanceled.run();
        check(f.context.sent.size() == count, "cancellation failure cannot re-enable stale task");
        for (boolean ownerFailure : new boolean[] { false, true }) {
            f = new Fixture();
            f.sender.update(true, 50);
            f.failOwner = ownerFailure;
            f.failGate = !ownerFailure;
            f.fireAt(1010L);
            f.assertFrame(false, 0);
            check(f.handler.queue.isEmpty(), "owner/gate exception releases");
        }
        Fixture aa = new Fixture();
        ApproachSender aaSender = new ApproachSender(aa.context, aa.handler, aa, aa, aa, ApproachSender.SOURCE_ANDROID_AUTO);
        aaSender.update(true, 30);
        check(Integer.valueOf(2).equals(aa.last().source), "AA activation tagged");
        aaSender.update(false, 0);
        check(Integer.valueOf(2).equals(aa.last().source) && !aa.last().active, "AA release tagged");
        boolean rejected = false;
        try { new ApproachSender(null, null, null, null, null, 3); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "unknown sender rejected at construction");
        ApproachSender unavailable = new ApproachSender(null, null, null, null, null);
        unavailable.update(true, 50);
        unavailable.update(false, 0);
        check(true, "absent dependencies do not throw");
    }

    public static void main(String[] args) {
        heartbeatAndChanges();
        releaseAndOwnership();
        leaseAndClock();
        boundsAndFailures();
        System.out.println("ApproachSenderTest PASS checks=" + checks);
    }
}
