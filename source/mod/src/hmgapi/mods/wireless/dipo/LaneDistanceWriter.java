package hmgapi.mods.wireless.dipo;

import android.content.Context;
import hmgapi.hook.Points;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Optional stock lane-distance API, independent of the installed HMG-API Cluster version. */
public abstract class LaneDistanceWriter {
    public abstract boolean send(int whole, int unit, int tenths, int toll);

    public static LaneDistanceWriter create(Context context) {
        try {
            final Method write = Points.method(null, "framework.navigation_cluster.update_remain_dis");
            Class<?>[] parameters = write.getParameterTypes();
            if (Modifier.isStatic(write.getModifiers()) || write.getReturnType() != Void.TYPE
                    || parameters.length != 4) return null;
            for (Class<?> type : parameters) if (type != Integer.TYPE) return null;
            final Object target = Points.newInstance(null, "framework.navigation_cluster.new", context);
            if (!write.getDeclaringClass().isInstance(target)) return null;
            return new LaneDistanceWriter() {
                @Override public boolean send(int whole, int unit, int tenths, int toll) {
                    boolean clear = whole == 65535 && unit == 15 && tenths == 15;
                    if (toll != 0 || (!clear && (whole < 0 || whole > 65534
                            || unit < 0 || unit > 2 || tenths < 0 || tenths > 9))) return false;
                    try { write.invoke(target, whole, unit, tenths, toll); return true; }
                    catch (Throwable unavailable) { return false; }
                }
            };
        } catch (Throwable unavailable) { return null; }
    }
}
