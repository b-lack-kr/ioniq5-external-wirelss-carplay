// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import android.os.SystemClock;
import hmgapi.hook.Car;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;

/** Passive native-Navi evidence. No argument changes, writer calls, IPC or replay.
 * The fine trap byte is not assumed to control AR cursor motion. */
public final class NativeTbtEvidence {
    static final String[] POINTS = {
        "framework.navigation_cluster.update_tbt_trap_info",
        "framework.navigation_cluster.update_tbt_trap_info_new",
        "framework.navigation_cluster.update_clu_data_14",
        "framework.navigation_cluster.update_clu_data_new_14"
    };
    static final String[] WRITERS = { "trap", "trap_new", "clu14", "clu14_new" };
    private static final int[] ARITY = { 17, 1, 3, 5 };
    interface Port {
        boolean nativeOwner(); long now(); void event(String line);
        // -1 disabled/unknown; 0 native/free drive, 1 CP, 2 AA, 3 both phone flags.
        default int cameraOwner() { return -1; }
    }
    private static final String[] CAMERA_FIELDS={"fixedSpeed","mobileSpeed","overSpeedAlarm","redlineSpeed",
        "nonSpeed","warningZone","curveWarning","mergeWarning","tollGateCurrency","tollGate",
        "displayTG","barGraphLevel","speedLimit","busSpeed","speedLimitType","speedLimitUnit","speedInfoType",
        "warningZone2","warningZone3","warningZone4","cameraSafetyAlert"};
    private static final class Capture {
        final int writer;
        final String values,camera;
        final int cameraOwner;
        Capture(int writer,String values,String camera,int cameraOwner) {
            this.writer=writer;this.values=values;this.camera=camera;this.cameraOwner=cameraOwner;
        }
    }
    private final Port port;
    private final Field bar;
    private final Field[] cameraFields;
    private final long[] cameraAt={Long.MIN_VALUE,Long.MIN_VALUE};
    private final int[] cameraDropped=new int[2];
    // Disabled ModContext.Guard may omit after: do not retain orphaned callbacks.
    private final Map<MethodHook.Param, Capture> captures = new WeakHashMap<MethodHook.Param, Capture>();
    private final long[] lastLog = { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };
    private volatile boolean installed;

    NativeTbtEvidence(Port port, Field bar) {
        if (bar == null || bar.getType() != Integer.TYPE || Modifier.isStatic(bar.getModifiers()))
            throw new IllegalArgumentException("trap bar field");
        this.port = port; this.bar = bar;
        Field[] mapped=new Field[CAMERA_FIELDS.length];
        try {
            for(int i=0;i<mapped.length;i++) {
                mapped[i]=bar.getDeclaringClass().getField(CAMERA_FIELDS[i]);
                if(mapped[i].getType()!=Integer.TYPE || Modifier.isStatic(mapped[i].getModifiers()))throw new IllegalArgumentException();
            }
        } catch(Exception missing) { mapped=null; }
        cameraFields=mapped;
    }
    public static void install(final ModContext mod, final LoadedApp app) {
        if (app.context == null || !"com.mnsoft.navi".equals(app.packageName)) return;
        try {
            Method[] methods = new Method[POINTS.length];
            for (int slot = 0; slot < POINTS.length; slot++) {
                methods[slot] = Points.method(null, POINTS[slot]);
                Class<?>[] args = methods[slot].getParameterTypes();
                if (args.length != ARITY[slot] || methods[slot].getReturnType() != Void.TYPE)
                    throw new IllegalArgumentException("native evidence signature");
                if (slot != 1) for (Class<?> type : args)
                    if (type != Integer.TYPE) throw new IllegalArgumentException("native evidence signature");
            }
            Field bar = methods[1].getParameterTypes()[0].getField("barGraphLevel");
            final NativeTbtEvidence trace = new NativeTbtEvidence(new Port() {
                public boolean nativeOwner() {
                    return mod.enabled() && !Car.carPlayGuiding(app.context) && !Car.androidAutoGuiding(app.context);
                }
                public int cameraOwner() {
                    if(!mod.enabled())return -1;
                    return (Car.carPlayGuiding(app.context)?1:0) | (Car.androidAutoGuiding(app.context)?2:0);
                }
                public long now() { return SystemClock.elapsedRealtime(); }
                public void event(String line) { mod.log(line); }
            }, bar);
            for (int slot = 0; slot < POINTS.length; slot++) {
                final int writer = slot;
                mod.hook(null, POINTS[slot], new MethodHook() {
                    @Override protected void beforeHookedMethod(Param p) {
                        if (trace.installed) trace.before(p, writer);
                    }
                    @Override protected void afterHookedMethod(Param p) { trace.after(p); }
                });
            }
            trace.installed = true;
            mod.log("USB_CP_NATIVE_AR event=ready");
            mod.log("USB_CP_CAMERA event=ready legacy_fields=17 modern_fields="+(trace.cameraFields==null?0:21));
        } catch (Throwable ignored) {
            mod.log("USB_CP_NATIVE_AR event=unavailable");
        }
    }
    private boolean owner() {
        try { return port.nativeOwner(); } catch (Throwable ignored) { return false; }
    }
    private int cameraOwner() {
        try { int value=port.cameraOwner();return value>=0 && value<=3?value:-1; }catch(Throwable ignored){return -1;}
    }
    private String cameraValues(Object[] args,int writer) throws Exception {
        StringBuilder out=new StringBuilder();
        if(writer==0) {
            for(Object value:args) {
                if(value==null || value.getClass()!=Integer.class)return null;
                if(out.length()>0)out.append(',');out.append(((Integer)value).intValue());
            }
        } else if(writer==1 && cameraFields!=null && args[0]!=null && args[0].getClass()==bar.getDeclaringClass()) {
            for(Field field:cameraFields){if(out.length()>0)out.append(',');out.append(field.getInt(args[0]));}
        } else return null;
        return out.toString();
    }
    void before(MethodHook.Param p, int writer) {
        if (writer < 0 || writer >= POINTS.length || p.args == null || p.args.length != ARITY[writer]) return;
        boolean nativeOwner=owner();int cameraOwner=writer<2?cameraOwner():-1;
        if(!nativeOwner && cameraOwner<0)return;
        try {
            String values;
            if (writer == 1) {
                if (p.args[0] == null || p.args[0].getClass() != bar.getDeclaringClass()) return;
                values = Integer.toString(bar.getInt(p.args[0]));
            } else if (writer == 0) {
                if (p.args[11] == null || p.args[11].getClass() != Integer.class) return;
                values = Integer.toString(((Integer)p.args[11]).intValue());
            } else {
                StringBuilder numbers = new StringBuilder(64);
                for (int i = 0; i < p.args.length; i++) {
                    if (p.args[i] == null || p.args[i].getClass() != Integer.class) return;
                    if (i > 0) numbers.append(',');
                    numbers.append(((Integer)p.args[i]).intValue());
                }
                values = numbers.toString();
            }
            String camera=cameraOwner<0?null:cameraValues(p.args,writer);
            synchronized (this) { captures.put(p, new Capture(writer,nativeOwner?values:null,camera,cameraOwner)); }
        } catch (Throwable ignored) { /* A failed observer must not affect native guidance. */ }
    }
    void after(MethodHook.Param p) {
        Capture capture;
        synchronized (this) { capture = captures.remove(p); }
        if (capture == null) return;
        long now;
        try { now = port.now(); } catch (Throwable ignored) { return; }
        if (now < 0) return;
        camera(capture,now,p.getThrowable()==null);
        if(capture.values==null || p.getThrowable()!=null || !owner())return;
        synchronized (this) {
            long previous = lastLog[capture.writer];
            if (previous != Long.MIN_VALUE && now >= previous && now - previous < 1000) return;
            lastLog[capture.writer] = now;
        }
        // Success means the public API returned normally, not a MICOM acknowledgment.
        try { port.event("USB_CP_NATIVE_AR writer=" + WRITERS[capture.writer] + " values=" + capture.values); }
        catch (Throwable ignored) { }
    }
    private synchronized void camera(Capture capture,long now,boolean returned) {
        if(capture.camera==null || capture.cameraOwner!=cameraOwner())return;
        int slot=capture.writer;long prior=cameraAt[slot];
        if(prior!=Long.MIN_VALUE && now>=prior && now-prior<1000) {
            if(cameraDropped[slot]<Integer.MAX_VALUE)cameraDropped[slot]++;return;
        }
        cameraAt[slot]=now;
        try { port.event("USB_CP_CAMERA writer="+WRITERS[slot]+" source=navi owner="+capture.cameraOwner+
                " returned="+(returned?1:0)+" elapsed_ms="+now+" values="+capture.camera+" dropped="+cameraDropped[slot]); }
        catch(Throwable ignored){}
        cameraDropped[slot]=0;
    }
}
