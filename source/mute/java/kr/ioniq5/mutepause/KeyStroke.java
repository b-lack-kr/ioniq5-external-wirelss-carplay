package kr.ioniq5.mutepause;

/** Recognize one physical short down/up; guard canceled, long and repeated sequences. */
final class KeyStroke {
    private int device = -1;
    private long down = -1, lastRelease = -1;

    synchronized boolean accept(int action, int id, long start, long time, int repeat, int flags) {
        if (id <= 0 || start < 0 || time < start) return false;
        boolean invalid = (flags & (0x20 | 0x80 | 0x100)) != 0 || repeat != 0;
        if (action == 0) {
            if (invalid) { if (id == device) device = -1; return false; }
            if (start > lastRelease) { device = id; down = start; }
            return false;
        }
        if (action != 1 || id != device || start != down) return false;
        device = -1;
        if (invalid || time <= lastRelease || time-start >= 800) return false;
        lastRelease = time;
        return true;
    }
}
