package hmgapi.mods.wireless.dipo;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Bounded lifecycle/looper diagnostics. Control bytes are NOT video frames. No recovery calls. */
public final class CarPlayVideoTrace {
    static final long PERIOD=10000;
    static final int DETAIL_LIMIT=12,INFLIGHT_LIMIT=32;
    static final String[] KINDS={"screen_started","screen_stopped","screen_timeout","decode_failure",
            "mode_changed","show_display","hide_display","activity_resume","activity_pause",
            "surface_created","surface_changed","surface_destroyed","iap_control"};
    static final String[] POINTS={"dipo.carplay.screen_started","dipo.carplay.screen_stopped",
            "dipo.carplay.screen_timeout","dipo.carplay.decode_failure","dipo.carplay.video_mode_changed",
            "dipo.carplay.show_display","dipo.carplay.hide_display","dipo.video_activity.resume",
            "dipo.video_activity.pause","dipo.video_surface.created","dipo.video_surface.changed",
            "dipo.video_surface.destroyed","dipo.carplay.iap_message"};
    interface Scheduler {long now();void later(Runnable r,long delay);void cancel(Runnable r);void close();}
    static final class Worker implements Scheduler {
        final HandlerThread thread=new HandlerThread("ioniq5-video-trace");
        final Handler handler;
        Worker(){thread.start();handler=new Handler(thread.getLooper());}
        public long now(){return SystemClock.elapsedRealtime();}
        public void later(Runnable r,long delay){handler.postDelayed(r,delay);}
        public void cancel(Runnable r){handler.removeCallbacks(r);}
        public void close(){thread.quit();}
    }
    static final class Main implements Scheduler {
        final Handler handler=new Handler(Looper.getMainLooper());
        public long now(){return SystemClock.elapsedRealtime();}
        public void later(Runnable r,long delay){if(!handler.postDelayed(r,delay))throw new IllegalStateException();}
        public void cancel(Runnable r){handler.removeCallbacks(r);}
        public void close(){} // The application owns this looper.
    }
    static final class Session {
        final Object owner;final int generation,transport;
        Session(Object owner,int generation,int transport){this.owner=owner;this.generation=generation;this.transport=transport;}
    }
    static final class Call {
        final Session session;final Call previous;final long began;final int kind;boolean tracked;
        Call(Session session,Call previous,long began,int kind){this.session=session;this.previous=previous;this.began=began;this.kind=kind;}
    }
    private final ModContext mod;
    private final Scheduler scheduler,main;
    private final ThreadLocal<Call> calls=new ThreadLocal<Call>();
    private final long[] counts=new long[KINDS.length];
    private final int[] owners={-1,-1,-1,-1,-1,-1};
    private volatile boolean installed;
    private Session session;
    private Runnable heartbeat;
    private Runnable mainPing;
    private final ArrayList<Call> inFlight=new ArrayList<Call>(INFLIGHT_LIMIT);
    private int generation,details,resumed=-1,surfaceNull=-1,surfaceValid=-1,showResult=-1,lastDecode=-1,width=-1,height=-1,format=-1;
    private int maxHookKind=-1;
    private long windowStart,failures,suppressed;
    private long mainQueuedAt=-1,lastMainAck=-1,maxMainLag,mainPostFailures,workerDue=-1,workerLate;
    private long lastEvent=-1,lastDecodeAt=-1,lastScreenEvent=-1,lastControl=-1,controlBytes,maxHookPath,inflightOverflow;
    private WeakReference<Surface> displayedSurface=new WeakReference<Surface>(null);
    private WeakReference<Object> activity=new WeakReference<Object>(null),holder=new WeakReference<Object>(null);

    private CarPlayVideoTrace(ModContext mod,Scheduler scheduler,Scheduler main){this.mod=mod;this.scheduler=scheduler;this.main=main;}
    public static void install(ModContext mod,LoadedApp app){install(mod,app,null,null);}
    static CarPlayVideoTrace install(ModContext mod,LoadedApp app,Scheduler testScheduler,Scheduler testMain) {
        CarPlayVideoTrace trace=null;
        try {
            Points.method(app.classLoader,"dipo.carplay.session_started");
            Points.method(app.classLoader,"dipo.carplay.session_finalized");
            for(String point:POINTS)Points.method(app.classLoader,point);
            trace=new CarPlayVideoTrace(mod,testScheduler==null?new Worker():testScheduler,testMain==null?new Main():testMain);
            trace.hooks(app.classLoader);trace.installed=true;
            mod.log("USB_CP_VIDEO observers_ready aggregate_ms=10000 detail_limit_per_window=12 no_frame_counter=1 control_is_video=0 inflight_limit=32 main_probe_pending_limit=1");
        }catch(Throwable unavailable){if(trace!=null){trace.scheduler.close();trace.main.close();}mod.log("USB_CP_VIDEO observers_unavailable");}
        return trace;
    }
    private void hooks(ClassLoader cl) {
        mod.hook(cl,"dipo.carplay.session_started",new MethodHook(){
            private final ThreadLocal<Call> starts=new ThreadLocal<Call>();
            @Override protected void beforeHookedMethod(Param p){
                Call previous=starts.get();starts.set(new Call(start(p.thisObject,number(p.args[0])),previous,scheduler.now(),-1));
            }
            @Override protected void afterHookedMethod(Param p){
                Call call=starts.get();if(call==null)return;
                if(call.previous==null)starts.remove();else starts.set(call.previous);
                if(p.getThrowable()!=null)stop(call.session,true);
            }
        });
        mod.hook(cl,"dipo.carplay.session_finalized",new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){
                synchronized(CarPlayVideoTrace.this){if(session!=null&&session.owner==p.thisObject)stop(session,false);}
            }
        });
        for(int i=0;i<POINTS.length;i++) {
            final int kind=i;
            mod.hook(cl,POINTS[i],new MethodHook(){
                @Override protected void beforeHookedMethod(Param p){
                    synchronized(CarPlayVideoTrace.this){
                        Session active=installed&&mod.enabled()?session:null;
                        if(active!=null&&(kind<=6||kind==12)&&active.owner!=p.thisObject)active=null;
                        Call call=new Call(active,calls.get(),scheduler.now(),kind);
                        if(active!=null){
                            if(inFlight.size()<INFLIGHT_LIMIT){inFlight.add(call);call.tracked=true;}
                            else inflightOverflow++;
                        }
                        calls.set(call);
                    }
                }
                @Override protected void afterHookedMethod(Param p){
                    Call call=calls.get();if(call==null)return;
                    if(call.previous==null)calls.remove();else calls.set(call.previous);
                    completed(call,kind,p.thisObject,p.args,p.getResult(),p.getThrowable()!=null);
                }
            });
        }
    }
    private synchronized Session start(Object owner,int transport) {
        if(!installed||!mod.enabled()||owner==null)return null;
        if(session!=null)stop(session,false);
        session=new Session(owner,++generation,transport);windowStart=scheduler.now();
        resetWindow();for(int i=0;i<owners.length;i++)owners[i]=-1;
        mainQueuedAt=lastMainAck=lastEvent=lastDecodeAt=lastScreenEvent=lastControl=-1;
        workerDue=windowStart+PERIOD;workerLate=0;inFlight.clear();displayedSurface.clear();
        resumed=surfaceNull=surfaceValid=showResult=lastDecode=width=height=format=-1;
        activity.clear();holder.clear();
        mod.log("USB_CP_VIDEO session_started generation="+generation+" transport="+transport+" elapsed_ms="+windowStart);
        final Session expected=session;
        heartbeat=new Runnable(){public void run(){tick(expected,this);}};
        scheduler.later(heartbeat,PERIOD);queueMain(expected);return session;
    }
    private synchronized void stop(Session expected,boolean failure) {
        if(expected==null||session!=expected)return;
        if(heartbeat!=null)scheduler.cancel(heartbeat);
        heartbeat=null;
        if(mainPing!=null)main.cancel(mainPing);
        mainPing=null;mainQueuedAt=-1;
        if(failure)failures++;
        if(mod.enabled()) {
            report();
            mod.log("USB_CP_VIDEO session_finalized generation="+session.generation+" transport="+session.transport+" elapsed_ms="+scheduler.now());
        }
        session=null;activity.clear();holder.clear();displayedSurface.clear();inFlight.clear();
    }
    private static int number(Object value){return value instanceof Integer?((Integer)value).intValue():-1;}
    private static int valid(Surface value){try{return value==null?-1:value.isValid()?1:0;}catch(Throwable ignored){return -1;}}
    private static Surface surface(Object value){try{return value instanceof SurfaceHolder?((SurfaceHolder)value).getSurface():null;}catch(Throwable ignored){return null;}}
    private static int result(Object value){return value instanceof Enum?((Enum<?>)value).ordinal():-1;}
    private synchronized void completed(Call call,int kind,Object target,Object[] args,Object returned,boolean exception) {
        if(call.tracked)inFlight.remove(call);
        Session expected=call.session;
        if(!installed||!mod.enabled()||expected==null||session!=expected)return;
        long now=scheduler.now(),duration=Math.max(0,now-call.began);
        if(duration>maxHookPath){maxHookPath=duration;maxHookKind=kind;}
        lastEvent=now;
        counts[kind]++;if(exception)failures++;
        if(kind==12){
            // Already mapped oniAPSendMessage. Count length only; never copy or
            // interpret payloads, and never call these control bytes video data.
            if(!exception&&args.length==2&&args[0] instanceof byte[]&&args[1] instanceof Integer){
                int size=((Integer)args[1]).intValue();
                if(size>=0&&size<=((byte[])args[0]).length){controlBytes+=size;lastControl=now;}
            }
            return;
        }
        int code=-1,nul=-1,valid=-1,w=-1,h=-1,f=-1;
        if(kind<=2)lastScreenEvent=now;
        if(kind==3){code=number(args[0]);lastDecode=code;lastDecodeAt=now;}
        if(kind==4&&!exception)for(int i=0;i<owners.length;i++)owners[i]=number(args[i]);
        if(kind==5) {
            Surface value=args[0] instanceof Surface?(Surface)args[0]:null;
            nul=value==null?1:0;valid=valid(value);code=result(returned);
            surfaceNull=nul;surfaceValid=valid;showResult=code;
            if(!exception&&code==0)displayedSurface=new WeakReference<Surface>(value);
        }
        if(kind==6){code=result(returned);if(!exception&&code==0){surfaceValid=0;displayedSurface.clear();}}
        if(kind==7&&!exception){activity=new WeakReference<Object>(target);resumed=1;}
        if(kind==8&&!exception&&activity.get()==target)resumed=0;
        if(kind>=9) {
            Object current=args[0];Surface value=surface(current);
            nul=current==null?1:0;valid=valid(value);
            if(kind==10){f=number(args[1]);w=number(args[2]);h=number(args[3]);}
            if(!exception) {
                if(kind!=11) {
                    holder=new WeakReference<Object>(current);surfaceNull=nul;surfaceValid=valid;
                    if(kind==10){width=w;height=h;format=f;}
                }else if(current!=null&&holder.get()==current){surfaceValid=0;holder.clear();}
            }
        }
        if(details<DETAIL_LIMIT) {
            details++;
            mod.log("USB_CP_VIDEO event generation="+session.generation+" elapsed_ms="+scheduler.now()+" kind="+KINDS[kind]
                    +" exception="+(exception?1:0)+" code="+code+" surface_null="+nul+" surface_valid="+valid
                    +" width="+w+" height="+h+" format="+f+ownerFields(false));
        }else suppressed++;
    }
    private String ownerFields(boolean last) {
        String prefix=last?" last_":" ";
        return prefix+"screen_owner="+owners[0]+prefix+"audio_owner="+owners[1]+prefix+"phone_owner="+owners[2]
                +prefix+"tbt_owner="+owners[3]+prefix+"speech_owner="+owners[4]+prefix+"speech_mode="+owners[5];
    }
    private void resetWindow(){for(int i=0;i<counts.length;i++)counts[i]=0;details=0;failures=suppressed=0;
        controlBytes=maxHookPath=inflightOverflow=maxMainLag=mainPostFailures=0;maxHookKind=-1;}
    private static long age(long now,long then){return then<0?-1:Math.max(0,now-then);}
    private void queueMain(final Session expected){
        if(mainPing!=null||session!=expected)return;
        mainQueuedAt=scheduler.now();
        Runnable ping=new Runnable(){public void run(){mainAcknowledged(expected,this);}};
        mainPing=ping;
        try{main.later(ping,0);}catch(Throwable unavailable){if(mainPing==ping){mainPing=null;mainQueuedAt=-1;mainPostFailures++;}}
    }
    private synchronized void mainAcknowledged(Session expected,Runnable ping){
        if(session!=expected||mainPing!=ping)return;
        long now=scheduler.now();maxMainLag=Math.max(maxMainLag,age(now,mainQueuedAt));
        lastMainAck=now;mainPing=null;mainQueuedAt=-1;
    }
    private void report() {
        long now=scheduler.now();
        StringBuilder line=new StringBuilder("USB_CP_VIDEO window generation=").append(session.generation)
                .append(" elapsed_ms=").append(now).append(" window_ms=").append(Math.max(0,now-windowStart));
        for(int i=0;i<KINDS.length;i++)line.append(' ').append(KINDS[i]).append('=').append(counts[i]);
        long oldest=-1;int kindMask=0;for(Call call:inFlight)if(call.session==session){oldest=Math.max(oldest,age(now,call.began));kindMask|=1<<call.kind;}
        line.append(" failure=").append(failures).append(" detail_suppressed=").append(suppressed).append(ownerFields(true))
                .append(" resumed=").append(resumed).append(" surface_valid=").append(surfaceValid).append(" surface_null=").append(surfaceNull)
                .append(" show_result=").append(showResult).append(" last_decode=").append(lastDecode)
                .append(" width=").append(width).append(" height=").append(height).append(" format=").append(format)
                .append(" surface_valid_now=").append(valid(displayedSurface.get()))
                .append(" last_event_age_ms=").append(age(now,lastEvent)).append(" decode_age_ms=").append(age(now,lastDecodeAt))
                .append(" screen_event_age_ms=").append(age(now,lastScreenEvent)).append(" control_age_ms=").append(age(now,lastControl))
                .append(" control_bytes=").append(controlBytes).append(" hook_path_max_ms=").append(maxHookPath).append(" hook_path_kind=").append(maxHookKind)
                .append(" inflight_tracked=").append(inFlight.size()).append(" inflight_oldest_ms=").append(oldest)
                .append(" inflight_kind_mask=").append(kindMask).append(" inflight_overflow=").append(inflightOverflow).append(" worker_late_ms=").append(workerLate)
                .append(" main_pending_age_ms=").append(mainPing==null?-1:age(now,mainQueuedAt))
                .append(" main_ack_age_ms=").append(age(now,lastMainAck)).append(" main_lag_max_ms=").append(maxMainLag)
                .append(" main_post_failed=").append(mainPostFailures);
        mod.log(line.toString());resetWindow();windowStart=now;
    }
    private synchronized void tick(Session expected,Runnable work){
        if(session!=expected||heartbeat!=work)return;
        if(!mod.enabled()){stop(session,false);return;}
        workerLate=workerDue<0?0:Math.max(0,scheduler.now()-workerDue);
        report();queueMain(expected);workerDue=scheduler.now()+PERIOD;scheduler.later(work,PERIOD);
    }
}
