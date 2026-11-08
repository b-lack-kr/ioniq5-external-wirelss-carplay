package android.os;

import java.util.ArrayList;
import java.util.List;

public final class Handler {
    public static final class Entry {
        public final Runnable work;
        public final long delay;
        Entry(Runnable work, long delay) { this.work = work; this.delay = delay; }
    }
    public final List<Entry> queue = new ArrayList<Entry>();
    public boolean reject, fail, failCancel;
    public boolean postDelayed(Runnable work, long delay) {
        if (fail) throw new IllegalStateException("synthetic queue failure");
        if (reject) return false;
        queue.add(new Entry(work, delay));
        return true;
    }
    public void removeCallbacks(Runnable work) {
        if (failCancel) throw new IllegalStateException("synthetic cancellation failure");
        for (int i = queue.size() - 1; i >= 0; i--) if (queue.get(i).work == work) queue.remove(i);
    }
    public Entry take() { return queue.remove(0); }
}
