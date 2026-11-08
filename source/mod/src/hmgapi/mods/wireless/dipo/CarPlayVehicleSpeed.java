// SPDX-License-Identifier: AGPL-3.0-or-later
package hmgapi.mods.wireless.dipo;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sends fresh, requested vehicle-speed sentences after actual speed changes.
 * Only requested CP output RMC speed is aligned with observed MICOM; global GPS,
 * coordinates, sensor registration and the stock heartbeat stay untouched. */
public final class CarPlayVehicleSpeed {
    private static final String START="dipo.location.start", STOP="dipo.location.stop",
            SPEED="dipo.location_speed.changed", OWNER="dipo.location_speed.manager",
            REQUEST="dipo.location.pascd_requested", RMC_REQUEST="dipo.location.rmc_requested", FORMAT="dipo.location.make_pascd",
            SEND="dipo.location.sentence", CREATED="dipo.carplay.session_created",
            CP_START="dipo.carplay.session_started", FINALIZED="dipo.carplay.session_finalized";
    private static final class StartCall {
        final CarPlaySpeedSession.Start start;final StartCall previous;
        StartCall(CarPlaySpeedSession.Start start,StartCall previous){this.start=start;this.previous=previous;}
    }
    private static final class RequestCall {
        final RmcVehicleSpeedSource.Request request;final RequestCall previous;
        RequestCall(RmcVehicleSpeedSource.Request request,RequestCall previous){this.request=request;this.previous=previous;}
    }
    private static void report(ModContext mod,SpeedPulseStats stats,boolean force){
        try{String line=stats.summary(SystemClock.elapsedRealtime(),force);if(line!=null)mod.log("USB_CP_LOCATION "+line);}
        catch(Throwable ignored){} // Observation must not affect an OEM callback.
    }
    private static final class Worker implements VehicleSpeedPulse.Worker {
        final HandlerThread thread=new HandlerThread("ioniq5-speed-pulse");
        final Handler handler;
        Worker(){thread.start();handler=new Handler(thread.getLooper());}
        public boolean post(Runnable work,long delay){return handler.postDelayed(work,delay);}
        public void cancel(Runnable work){handler.removeCallbacks(work);}
        void close(){thread.quit();}
    }

    public static void install(final ModContext mod, LoadedApp app) {
        Worker worker=null;
        try {
            final Field owner=Points.field(app.classLoader,OWNER);
            final Field requested=Points.field(app.classLoader,REQUEST);
            final Field rmcRequested=Points.field(app.classLoader,RMC_REQUEST);
            final Method format=Points.method(app.classLoader,FORMAT);
            final Method send=Points.method(app.classLoader,SEND);
            Class<?> manager=format.getDeclaringClass();
            if (owner.getType()!=manager || requested.getDeclaringClass()!=manager
                    || requested.getType()!=Boolean.TYPE || rmcRequested.getDeclaringClass()!=manager
                    || rmcRequested.getType()!=Boolean.TYPE || Modifier.isStatic(rmcRequested.getModifiers())
                    || Modifier.isStatic(owner.getModifiers())
                    || Modifier.isStatic(requested.getModifiers())
                    || format.getReturnType()!=String.class || format.getParameterTypes().length!=0
                    || Modifier.isStatic(format.getModifiers()) || send.getDeclaringClass()!=manager
                    || send.getReturnType()!=Void.TYPE || Modifier.isStatic(send.getModifiers())
                    || send.getParameterTypes().length!=1 || send.getParameterTypes()[0]!=String.class)
                throw new IllegalStateException("vehicle speed target mismatch");
            for (String point:new String[]{START,STOP,SPEED,CREATED,CP_START,FINALIZED}) Points.method(app.classLoader,point);
            Method cpCreated=Points.method(app.classLoader,CREATED),
                    cpStarted=Points.method(app.classLoader,CP_START),
                    cpFinalized=Points.method(app.classLoader,FINALIZED);
            if(cpCreated.getReturnType()!=Void.TYPE||cpCreated.getParameterTypes().length!=0
                    ||Modifier.isStatic(cpCreated.getModifiers()))throw new IllegalStateException("CarPlay lifecycle target mismatch");
            for(Method callback:new Method[]{cpStarted,cpFinalized}){
                if(callback.getDeclaringClass()!=cpCreated.getDeclaringClass()||callback.getReturnType()!=Void.TYPE
                        ||callback.getParameterTypes().length!=1||callback.getParameterTypes()[0]!=Integer.TYPE
                        ||Modifier.isStatic(callback.getModifiers()))throw new IllegalStateException("CarPlay lifecycle target mismatch");
            }
            worker=new Worker();
            final AtomicBoolean installed=new AtomicBoolean();
            final CarPlaySpeedSession carplay=new CarPlaySpeedSession();
            final SpeedPulseStats stats=new SpeedPulseStats(SystemClock.elapsedRealtime());
            final RmcVehicleSpeedSource vehicle=new RmcVehicleSpeedSource();
            final RmcSpeedStats rmcStats=new RmcSpeedStats(SystemClock.elapsedRealtime());
            final VehicleSpeedPulse pulse=new VehicleSpeedPulse(worker,new VehicleSpeedPulse.Target(){
                public boolean allowed(Object manager) {
                    int reason=0;
                    if(!installed.get()||!mod.enabled()||!Bridge.enabled())reason=1;
                    else if(Bridge.sUsb)reason=2;
                    else if(!Bridge.sCpWireless)reason=3;
                    else if(!carplay.active(Bridge.sCp))reason=4;
                    else try{if(!requested.getBoolean(manager))reason=5;}catch(Exception unavailable){reason=6;}
                    stats.gate(reason);return reason==0;
                }
                public Object identity(Object manager){return carplay.identity(Bridge.sCp);}
                public void send(Object manager,VehicleSpeedPulse.Validity current) throws Exception {
                    if (!current.valid()) return;
                    String sentence=(String)format.invoke(manager);
                    if (sentence==null || !sentence.startsWith("$PASCD,")) {stats.invalid();return;}
                    // Exact existing OEM formatter keeps units, gear flags,
                    // timestamp and checksum; the same OEM send path is used.
                    String payload=sentence.replace("\r\n","\u0000");
                    // A new request can reuse the same manager and requested
                    // flag. Its identity must still match after formatting.
                    if (!current.valid()) return;
                    send.invoke(manager,payload);
                    stats.invoked();
                }
                public void failed(){stats.failed();mod.log("USB_CP_LOCATION pulse_failed");}
            });
            mod.hook(app.classLoader,START,new MethodHook(){
                private final ThreadLocal<RequestCall> calls=new ThreadLocal<RequestCall>();
                @Override protected void beforeHookedMethod(Param p){
                    pulse.stop(p.thisObject);
                    calls.set(new RequestCall(vehicle.begin(p.thisObject,carplay.identity(Bridge.sCp)),calls.get()));
                }
                @Override protected void afterHookedMethod(Param p){
                    boolean success=p.getThrowable()==null && p.args[0]!=null;
                    RequestCall call=calls.get();
                    if(call!=null){if(call.previous==null)calls.remove();else calls.set(call.previous);vehicle.complete(call.request,success);}
                    if(success)pulse.start(p.thisObject);
                }
            });
            mod.hook(app.classLoader,STOP,new MethodHook(){
                @Override protected void beforeHookedMethod(Param p){pulse.stop(p.thisObject);vehicle.stop(p.thisObject);}
            });
            mod.hook(app.classLoader,SPEED,new MethodHook(){
                @Override protected void afterHookedMethod(Param p){
                    if (p.getThrowable()!=null || !(p.args[0] instanceof Integer)) {
                        try{vehicle.changed(owner.get(p.thisObject),carplay.identity(Bridge.sCp),-1,SystemClock.elapsedRealtime());}catch(Exception ignored){}
                        return;
                    }
                    int value=((Integer)p.args[0]).intValue();
                    // Invalid codes clear our observation: OEM turns them into0,
                    // which must never masquerade as a measured standstill.
                    try {vehicle.changed(owner.get(p.thisObject),carplay.identity(Bridge.sCp),value,SystemClock.elapsedRealtime());}
                    catch(Exception ignored){}
                    if (value<0 || value>=511) return;
                    stats.changed();
                    try { pulse.changed(owner.get(p.thisObject),value); } catch (Exception ignored) { }
                    report(mod,stats,false);
                }
            });
            mod.hook(app.classLoader,CREATED,new MethodHook(){
                @Override protected void beforeHookedMethod(Param p){vehicle.reset();carplay.created(p.thisObject);}
                @Override protected void afterHookedMethod(Param p){
                    if(p.getThrowable()!=null)carplay.finalized(p.thisObject);
                }
            });
            mod.hook(app.classLoader,CP_START,new MethodHook(){
                private final ThreadLocal<StartCall> calls=new ThreadLocal<StartCall>();
                @Override protected void beforeHookedMethod(Param p){
                    if(carplay.identity(p.thisObject)!=null)vehicle.invalidateSample();
                    calls.set(new StartCall(carplay.starting(p.thisObject),calls.get()));
                }
                @Override protected void afterHookedMethod(Param p){
                    StartCall call=calls.get();if(call==null)return;
                    if(call.previous==null)calls.remove();else calls.set(call.previous);
                    // Bridge's before-hook already resolves raw transport 0 vs 1.
                    // Do not infer wireless from Bluetooth SPP or the raw value here.
                    boolean wireless=p.thisObject!=null&&p.thisObject==Bridge.sCp
                            &&!Bridge.sUsb&&Bridge.sCpWireless;
                    carplay.started(call.start,p.getThrowable()==null,wireless);
                }
            });
            mod.hook(app.classLoader,FINALIZED,new MethodHook(){
                @Override protected void beforeHookedMethod(Param p){
                    if(carplay.identity(p.thisObject)!=null)vehicle.reset();
                    carplay.finalized(p.thisObject);report(mod,stats,false);
                }
            });
            mod.hook(app.classLoader,SEND,new MethodHook(){
                private boolean allowed(Object manager){
                    if(!installed.get()||!mod.enabled()||!Bridge.enabled()||Bridge.sUsb||!Bridge.sCpWireless
                            ||!carplay.active(Bridge.sCp))return false;
                    try{return rmcRequested.getBoolean(manager);}catch(Exception unreadable){return false;}
                }
                @Override protected void beforeHookedMethod(Param p){
                    if(!(p.args[0] instanceof String)||!((String)p.args[0]).startsWith("$GPRMC,"))return;
                    try {
                        RmcSpeedRewrite.Result result=null;RmcVehicleSpeedSource.Sample sample=null;int reason=0;
                        if(!allowed(p.thisObject))reason=1;
                        else {
                            Object identity=carplay.identity(Bridge.sCp);
                            sample=vehicle.sample(p.thisObject,identity,SystemClock.elapsedRealtime());
                            if(sample==null)reason=2;
                            else {
                                result=RmcSpeedRewrite.apply((String)p.args[0],sample.kmh);
                                if(result.status==RmcSpeedRewrite.REPLACED||result.status==RmcSpeedRewrite.UNCHANGED){
                                    if(!allowed(p.thisObject)||!vehicle.current(sample,p.thisObject,carplay.identity(Bridge.sCp))){reason=1;result=null;}
                                    else p.args[0]=result.sentence;
                                }
                            }
                        }
                        rmcStats.event(reason,result,sample);
                        String log=rmcStats.summary(SystemClock.elapsedRealtime());if(log!=null)mod.log("USB_CP_LOCATION "+log);
                    }catch(Throwable ignored){} // Unexpected input retains the original OEM sentence.
                }
            });
            installed.set(true);
            mod.log("USB_CP_LOCATION pulse_ready min_gap_ms=0");
            mod.log("USB_CP_LOCATION rmc_ready output_only=1 vehicle_kmh=1 preserve_fix=1");
        } catch (Throwable unavailable) {
            if (worker!=null) worker.close();
            mod.log("USB_CP_LOCATION pulse_unavailable");
        }
    }
}
