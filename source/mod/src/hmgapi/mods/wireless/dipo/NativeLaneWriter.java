package hmgapi.mods.wireless.dipo;

import android.content.Context;
import hmgapi.hook.Points;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Optional F1 writer, confined to the auxiliary output worker.
 *
 * Unlike HMG-API Cluster.laneInfo's void wrapper, lookup, receiver and invocation
 * failures are returned to CpAuxWriter so an unchanged later input can retry.
 * A true result means only that the framework method returned normally. The
 * captured Korean NavigationCluster.updateLaneInfo itself swallows
 * RemoteException; this is NOT a Binder, MCU or physical-display acknowledgement.
 * No private service field, raw Binder transaction or CAN write is used here.
 */
final class NativeLaneWriter implements CpTurns.LaneSink {
    private final Context context;
    private Object cluster;
    private Method write;

    private NativeLaneWriter(Context context) { this.context = context; }

    /** Resolution is deferred to the auxiliary worker; construction does no IPC. */
    static NativeLaneWriter create(Context context) { return new NativeLaneWriter(context); }

    private void resolve() throws Exception {
        Method method = Points.method(null, "framework.navigation_cluster.update_lane_info");
        Class<?>[] types = method.getParameterTypes();
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Void.TYPE
                || types.length != 1 || types[0] != byte[].class)
            throw new IllegalArgumentException("lane signature");
        Object receiver = Points.newInstance(null, "framework.navigation_cluster.new", context);
        if (!method.getDeclaringClass().isInstance(receiver))
            throw new IllegalArgumentException("lane receiver");
        cluster = receiver;
        write = method;
    }

    /** The caller retains its buffer. Failure is not cached and has no timer retry. */
    @Override public boolean send(byte[] frame) {
        if (frame == null || frame.length != 81 || (frame[0] & 255) != 0xf1
                || (frame[1] & 255) > 8) return false;
        try {
            if (write == null) resolve();
            write.invoke(cluster, (Object) frame.clone());
            return true;
        } catch (Throwable failed) {
            // A dead/replaced service or a temporarily missing mapped method can
            // be resolved again on the next real route/lane event.
            write = null;
            cluster = null;
            return false;
        }
    }
}
