package kr.ioniq5.usbcp;
import java.util.*;
import hmgapi.hook.ModContext;
/** Relay seam; its actual wire/latch tests live in the power integration suite. */
public final class OemPower {
 public interface Sink{void offer(boolean off);}
 public static final List<Boolean> offers=new ArrayList<Boolean>();
 public static int created;public static boolean fail;
 public static Sink createAnimationSink(ModContext mod){created++;return new Sink(){public void offer(boolean off){if(fail)throw new IllegalStateException("PRIVATE");offers.add(off);}};}
}
