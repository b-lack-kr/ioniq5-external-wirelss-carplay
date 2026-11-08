package hmgapi.mods.wireless.dipo;

import android.content.Context;
import hmgapi.hook.Points;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Exact Korean NavigationCluster CluDataNew14 API. No raw CAN or image upload. */
final class NativePatternWriter implements CpTurns.PatternSink {
    private final Object cluster;
    private final Method write;
    private boolean reportedFailure;
    private NativePatternWriter(Object cluster, Method write) {
        this.cluster = cluster; this.write = write;
    }
    static CpTurns.PatternSink create(Context context) {
        try {
            Method method = Points.method(null, "framework.navigation_cluster.update_clu_data_new_14");
            Class<?>[] types = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Void.TYPE
                    || types.length != 5) throw new IllegalArgumentException("pattern signature");
            for (Class<?> type : types) if (type != Integer.TYPE)
                throw new IllegalArgumentException("pattern parameter");
            Object instance = Points.newInstance(null, "framework.navigation_cluster.new", context);
            if (!method.getDeclaringClass().isInstance(instance))
                throw new IllegalArgumentException("pattern receiver");
            log("USB_CP_TBT_JUNCTION event=ready");
            return new NativePatternWriter(instance, method);
        } catch (Throwable unavailable) {
            log("USB_CP_TBT_JUNCTION event=unavailable");
            return null;
        }
    }
    @Override public boolean send(int[] values) {
        if (values == null || values.length != 5) return false;
        for (int i=0; i<3; i++) if (values[i]<0 || values[i]>65535) return false;
        if (values[3]<0 || values[3]>255 || values[4]<0 || values[4]>15) return false;
        try {
            write.invoke(cluster, values[0], values[1], values[2], values[3], values[4]);
            reportedFailure = false;
            return true;
        } catch (Throwable failure) {
            if (!reportedFailure) log("USB_CP_TBT_JUNCTION event=write_failed");
            reportedFailure = true;
            return false;
        }
    }
    private static void log(String event) {
        try { Dipo.log(event); } catch (Throwable ignored) { /* Recording cannot break projection. */ }
    }
}
