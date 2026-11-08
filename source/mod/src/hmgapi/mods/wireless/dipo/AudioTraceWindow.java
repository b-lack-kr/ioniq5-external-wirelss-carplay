package hmgapi.mods.wireless.dipo;

/** Bounded event budget and counters; carries no audio samples or media content. */
final class AudioTraceWindow {
    static final int NATIVE_START = 0, NATIVE_STOP = 1, STOCK_START = 2, STOCK_STOP = 3,
            DUCK = 4, UNDUCK = 5, MICOM_START = 6, MICOM_END = 7, MICOM_MUSIC = 8,
            MICOM_STATUS = 9, VOLUME_KEY = 10, FAILURE = 11;
    private static final String[] NAMES = { "native_start", "native_stop", "stock_start",
            "stock_stop", "duck", "unduck", "micom_start", "micom_end", "micom_music",
            "micom_status", "volume_key", "failure" };
    private final int[] counts = new int[NAMES.length];
    private int detailsLeft = 24;
    private int suppressed;
    private long windowStart;
    private boolean dirty;

    AudioTraceWindow(long now) { windowStart = now; }

    boolean event(int kind) {
        if (kind < 0 || kind >= counts.length) return false;
        if (counts[kind] < Integer.MAX_VALUE) counts[kind]++;
        dirty = true;
        if (detailsLeft > 0) { detailsLeft--; return true; }
        if (suppressed < Integer.MAX_VALUE) suppressed++;
        return false;
    }

    String summary(long now) {
        if (!dirty) return null;
        StringBuilder out = new StringBuilder("window_ms=").append(Math.max(0, now - windowStart));
        for (int i = 0; i < counts.length; i++) {
            out.append(' ').append(NAMES[i]).append('=').append(counts[i]);
            counts[i] = 0;
        }
        out.append(" detail_suppressed=").append(suppressed);
        suppressed = 0;
        dirty = false;
        windowStart = now;
        return out.toString();
    }
}
