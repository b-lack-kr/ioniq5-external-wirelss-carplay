package hmgapi.mods.wireless.dipo;

public final class LocationTraceWindowTest {
    private static void has(String text, String value) {
        if (!text.contains(value)) throw new AssertionError("Missing " + value + " in " + text);
    }
    public static void main(String[] args) {
        LocationTraceWindow w = new LocationTraceWindow(0);
        w.event(LocationTraceWindow.SPEED, 100);
        w.event(LocationTraceWindow.GGA_IN, 200);
        w.event(LocationTraceWindow.GGA_OUT, 202);
        w.event(LocationTraceWindow.RMC_IN, 210);
        w.event(LocationTraceWindow.RMC_OUT, 213);
        w.event(LocationTraceWindow.PASCD_OUT, 1000);
        w.event(LocationTraceWindow.SEND_OK, 1001);
        w.sent(8);
        w.event(LocationTraceWindow.PASCD_OUT, 2000);
        w.event(LocationTraceWindow.SEND_FALSE, 2002);
        w.event(LocationTraceWindow.SEND_ERROR, 2200);
        w.sent(12);
        String first = w.summary(10000);
        has(first, "speed_in=1");
        has(first, "pascd_out=2");
        has(first, "pascd_out_max_gap_ms=1000");
        has(first, "send_ok=1 send_false=1 send_error=1");
        has(first, "max_speed_age_at_pascd_ms=1900");
        has(first, "max_gga_age_at_send_ms=2");
        has(first, "max_rmc_age_at_send_ms=3");
        has(first, "max_binder_ms=12");
        w.event(LocationTraceWindow.PASCD_OUT, 11000);
        String second = w.summary(20000);
        has(second, "window_ms=10000");
        has(second, "speed_in=0");
        has(second, "pascd_out=1");
        has(second, "pascd_out_max_gap_ms=9000");
        has(second, "max_speed_age_at_pascd_ms=10900");
        has(second, "max_binder_ms=-1");
        LocationTraceWindow noInput = new LocationTraceWindow(500);
        noInput.event(LocationTraceWindow.PASCD_OUT, 600);
        String missing = noInput.summary(800);
        has(missing, "speed_callback_age_ms=-1");
        has(missing, "max_speed_age_at_pascd_ms=-1");
        LocationTraceWindow newSession = new LocationTraceWindow(21000);
        has(newSession.summary(22000), "pascd_out=0");
        has(newSession.summary(22000), "speed_callback_age_ms=-1");
        System.out.println("PASS location window timing, gap retention, missing source, reset and distinct send outcomes");
    }
}
