package kr.ioniq5.usbcp;

/** Exercise the observer's distinction between logical power and raw ACC states. */
public final class HeadUnitPowerTest {
    public static final class Reading {
        boolean off;
        int acc;
        boolean failOff, failAcc;
        public boolean isPowerOff() { if (failOff) throw new IllegalStateException(); return off; }
        public int getPowerStatus() { if (failAcc) throw new IllegalStateException(); return acc; }
    }
    private static String sample(Reading r) throws Exception {
        return HeadUnitPower.sample(r, Reading.class.getMethod("isPowerOff"),
                Reading.class.getMethod("getPowerStatus"));
    }
    private static void expect(String want, String got) {
        if (!want.equals(got)) throw new AssertionError(want + " != " + got);
    }
    public static void main(String[] args) throws Exception {
        Reading r = new Reading();
        expect("ON 0", sample(r));
        r.acc=6; expect("ON 6", sample(r)); // retained ACC-off is not shutdown
        r.off=true; expect("OFF 6", sample(r));
        r.failAcc=true; expect("OFF unknown", sample(r));
        r.failOff=true;
        try { sample(r); throw new AssertionError("failed read was accepted"); }
        catch (java.lang.reflect.InvocationTargetException expected) { }
        System.out.println("PASS logical power observation does not guess from ACC status");
    }
}
