package hmgapi.mods.wireless.dipo;

public final class AudioTraceWindowTest {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        AudioTraceWindow w = new AudioTraceWindow(100);
        require(w.summary(200) == null, "idle windows do not emit logs");
        int detail = 0;
        for (int i = 0; i < 80; i++) if (w.event(AudioTraceWindow.MICOM_STATUS)) detail++;
        require(detail == 24, "first session detail budget is 24");
        String first = w.summary(10100);
        require(first.contains("micom_status=80"), "all observations are aggregated");
        require(first.contains("detail_suppressed=56"), "overflow is counted");
        require(first.contains("window_ms=10000"), "elapsed time is monotonic");
        require(w.summary(20100) == null, "counts reset and idle interval is silent");
        require(!w.event(AudioTraceWindow.NATIVE_START), "window flush must not reset session budget");
        w.event(AudioTraceWindow.FAILURE);
        String second = w.summary(30100);
        require(second.contains("native_start=1") && second.contains("failure=1"), "failure stays visible");
        require(second.contains("micom_status=0"), "window counters reset");
        AudioTraceWindow nextSession = new AudioTraceWindow(40000);
        require(nextSession.event(AudioTraceWindow.VOLUME_KEY), "new session gets a new budget");
        require(!nextSession.event(-1), "unknown event codes are rejected");
        System.out.println("PASS audio observer bounded detail, idle silence, aggregation and session reset");
    }
}
