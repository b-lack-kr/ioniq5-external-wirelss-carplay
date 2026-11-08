// Adapted for NEEV USB CarPlay by blackr on 2026-11-07.
// Reference: HMG-Seogaemo v0.1.46, commit 26eeb97e0a745528b438d7a946be5450fadbfd04.
// https://github.com/seogaemo/hmg-seogaemo (AGPL-3.0-or-later).
// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import hmgapi.hook.Car;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/** Changes only the trap-bar field of a previously captured stock record. */
public final class CpTrapOverlay {
    private static final String ACTION="kr.ioniq5.usbcp.APPROACH_BAR";
    private static final String LEGACY="framework.navigation_cluster.update_tbt_trap_info";
    private static final String MODERN="framework.navigation_cluster.update_tbt_trap_info_new";
    private static final String[] CORE={"fixedSpeed","mobileSpeed","overSpeedAlarm","redlineSpeed",
        "nonSpeed","warningZone","curveWarning","mergeWarning","tollGateCurrency","tollGate",
        "displayTG","barGraphLevel","speedLimit","busSpeed","speedLimitType","speedLimitUnit","speedInfoType"};
    private static final String[] EXTRA={"warningZone2","warningZone3","warningZone4","cameraSafetyAlert"};
    private final ModContext mod;
    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final TrapBarState state=new TrapBarState();
    private final Method legacy, modern;
    private final Class<?> record;
    private final Constructor<?> constructor;
    private final Field[] fields, core, extra;
    private final ThreadLocal<Boolean> replay=new ThreadLocal<Boolean>();
    private Snapshot last;
    private int source;
    private String lastStatus;
    private long lastStatusAt=-1000L;
    private static CpTrapOverlay installed;

    private CpTrapOverlay(ModContext m, Context c) throws Exception {
        mod=m; context=c;
        legacy=Points.method(null,LEGACY); modern=Points.method(null,MODERN);
        if (legacy.getReturnType()!=Void.TYPE || modern.getReturnType()!=Void.TYPE
                || legacy.getParameterTypes().length!=17 || modern.getParameterTypes().length!=1)
            throw new IllegalArgumentException("trap signatures");
        for (Class<?> t:legacy.getParameterTypes()) if(t!=Integer.TYPE) throw new IllegalArgumentException("trap argument");
        record=modern.getParameterTypes()[0]; constructor=record.getConstructor();
        ArrayList<Field> all=new ArrayList<Field>();
        for(Field f:record.getFields()) {
            if(Modifier.isStatic(f.getModifiers())) continue;
            if(f.getType()!=Integer.TYPE || Modifier.isFinal(f.getModifiers())) throw new IllegalArgumentException("trap fields");
            all.add(f);
        }
        fields=all.toArray(new Field[all.size()]); core=lookup(CORE); extra=lookup(EXTRA);
    }
    private Field[] lookup(String[] names) throws Exception {
        Field[] out=new Field[names.length];
        for(int i=0;i<names.length;i++) {
            out[i]=record.getField(names[i]);
            if(out[i].getType()!=Integer.TYPE || Modifier.isStatic(out[i].getModifiers())) throw new IllegalArgumentException("trap field");
        }
        return out;
    }
    private boolean owner() {
        return owner(source);
    }
    private boolean owner(int candidate) {
        try {
            if (!mod.enabled()) return false;
            boolean cp=Car.carPlayGuiding(context), aa=Car.androidAutoGuiding(context);
            if (!mod.settingBoolean("ar_view",false)) return false;
            if (candidate==1) return cp && !aa;
            if (candidate==2) return aa && !cp;
            return false;
        }
        catch(Throwable ignored) { return false; }
    }
    /** No AA takeover and hooks still observe stock. A newer stock callback
     * marks the old overlay superseded before this restoration can run. */
    private boolean restorable() {
        try { return mod.enabled() && (source==1 ? !Car.androidAutoGuiding(context)
                : source==2 && !Car.carPlayGuiding(context)); }
        catch(Throwable ignored) { return false; }
    }
    private void status(String value) {
        try {
            long now=SystemClock.elapsedRealtime();
            if(value.equals(lastStatus) || (now>=lastStatusAt && now-lastStatusAt<1000L)) return;
            mod.log("USB_CP_APPROACH_BAR event="+value);
            lastStatus=value; lastStatusAt=now;
        } catch(Throwable ignored) { }
    }
    public static synchronized void install(final ModContext mod, LoadedApp app) {
        if(installed!=null || app.context==null || !"com.mnsoft.navi".equals(app.packageName)) return;
        try {
            final CpTrapOverlay o=new CpTrapOverlay(mod,app.context);
            o.hook(LEGACY,false); o.hook(MODERN,true);
            app.context.registerReceiver(new BroadcastReceiver(){
                public void onReceive(Context c,Intent i) {
                    if(i==null || !ACTION.equals(i.getAction()) || !"com.mnsoft.navi".equals(i.getPackage())) return;
                    o.receive(i.getIntExtra("source",1),i.getBooleanExtra("active",false),i.getIntExtra("bar",-1));
                }
            },new IntentFilter(ACTION),"android.permission.BLUETOOTH_PRIVILEGED",null);
            installed=o; mod.log("USB_CP_APPROACH_BAR event=ready");
        } catch(Throwable ignored) { mod.log("USB_CP_APPROACH_BAR event=unavailable"); }
    }
    private void hook(String point,final boolean object) {
        mod.hook(null,point,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p) {
                if(Boolean.TRUE.equals(replay.get())) return;
                synchronized(CpTrapOverlay.this) {
                    try {
                        Snapshot snap=new Snapshot(p.thisObject,p.args,object);
                        int[] out=state.capture(snap.values,snap.extraAlert,SystemClock.elapsedRealtime(),owner());
                        last=snap;
                        Object[] forwarded=out!=null && out[11]!=snap.values[11] ? snap.args(out[11]) : p.args;
                        try {
                            replay.set(Boolean.TRUE);
                            Object result=(object?modern:legacy).invoke(p.thisObject,forwarded);
                            p.setResult(result);
                            if(owner() && state.isActive(SystemClock.elapsedRealtime()))
                                status(state.warningOwnsBar()?"camera_owned":"applied");
                        } catch(InvocationTargetException failure) {
                            last=null; state.invalid(); status("failed"); p.setThrowable(failure.getCause());
                        } catch(Throwable failure) {
                            last=null; state.invalid(); status("failed"); p.setThrowable(failure);
                        } finally { replay.remove(); }
                    } catch(Throwable ignored) { last=null; state.invalid(); status("failed"); }
                }
            }
        });
    }
    private final Runnable expire=new Runnable(){public void run(){ synchronized(CpTrapOverlay.this) {
        long now=SystemClock.elapsedRealtime();
        if(state.isExpired(now)) { send(state.expire(now,restorable()),false); status("expired"); }
    }}};
    private synchronized void receive(int sender,boolean active,int bar) {
        if(sender!=1 && sender!=2) return;
        if(active && (bar<0 || bar>100)) return;
        // A delayed release/heartbeat from the previous projection must not
        // cancel the new owner's lease or replay an old stock record over it.
        if(active && !owner(sender) && sender!=source) return;
        if(!active && sender!=source) return;
        handler.removeCallbacks(expire);
        if(active && !owner(sender)) {
            send(state.request(false,0,SystemClock.elapsedRealtime(),restorable()),false);
            return;
        }
        if(active) source=sender;
        int[] values=state.request(active,bar,SystemClock.elapsedRealtime(),active?owner():restorable());
        send(values,active);
        if(active && owner() && values==null) status(state.waitingSnapshot()?"waiting_snapshot":"camera_owned");
        if(active && owner()) handler.postDelayed(expire,TrapBarState.LEASE_MS);
    }
    private void send(int[] values,boolean overlay) {
        if(values==null || last==null || (overlay?!owner():!restorable())) return;
        try {
            Object[] args=last.args(values[11]);
            replay.set(Boolean.TRUE);
            (last.object?modern:legacy).invoke(last.target,args);
            state.applied(overlay);
            status(overlay?"applied":"restored");
        } catch(Throwable ignored) { status("failed"); }
        finally { replay.remove(); }
    }
    private final class Snapshot {
        final Object target; final boolean object,extraAlert; final int[] values, objectValues;
        Snapshot(Object target,Object[] args,boolean object) throws Exception {
            if(target==null || args==null) throw new IllegalArgumentException("missing stock");
            this.target=target; this.object=object; values=new int[17];
            if(!object) {
                if(args.length!=17) throw new IllegalArgumentException("legacy");
                for(int i=0;i<17;i++) values[i]=((Integer)args[i]).intValue();
                objectValues=null; extraAlert=false;
            } else {
                if(args.length!=1 || args[0]==null || args[0].getClass()!=record) throw new IllegalArgumentException("record");
                objectValues=new int[fields.length]; for(int i=0;i<fields.length;i++) objectValues[i]=fields[i].getInt(args[0]);
                for(int i=0;i<17;i++) values[i]=core[i].getInt(args[0]);
                boolean warning=false; for(Field f:extra) warning |= TrapBarState.alert(-1,f.getInt(args[0]));
                extraAlert=warning;
            }
        }
        Object[] args(int bar) throws Exception {
            if(!object) { Object[] out=new Object[17]; for(int i=0;i<17;i++) out[i]=Integer.valueOf(i==11?bar:values[i]); return out; }
            Object out=constructor.newInstance(); for(int i=0;i<fields.length;i++) fields[i].setInt(out,objectValues[i]);
            core[11].setInt(out,bar); return new Object[]{out};
        }
    }
}
