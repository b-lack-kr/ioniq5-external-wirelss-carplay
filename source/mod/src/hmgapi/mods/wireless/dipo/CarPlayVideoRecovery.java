package hmgapi.mods.wireless.dipo;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import java.lang.ref.WeakReference;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.reflect.Method;

/** Event-driven key frames and one bounded timeout/stop display reattachment. No frame-progress claim. */
public final class CarPlayVideoRecovery {
    static final long MIN_GAP_MS=2000;
    static final long REBIND_GAP_MS=10000,STOP_WINDOW_MS=2000,REBIND_GRACE_MS=250,REBIND_MAX_WAIT_MS=2000;
    static final String FORCE="dipo.carplay.native_force_key_frame";
    static final String START="dipo.carplay.session_started",END="dipo.carplay.session_finalized";
    static final String DECODE="dipo.carplay.decode_failure",TIMEOUT="dipo.carplay.screen_timeout";
    static final String SHOW="dipo.carplay.show_display",HIDE="dipo.carplay.hide_display";
    static final String SCREEN_START="dipo.carplay.screen_started",STOP="dipo.carplay.screen_stopped";
    static final String MODE="dipo.carplay.video_mode_changed",RESUME="dipo.video_activity.resume",PAUSE="dipo.video_activity.pause";
    static final String CREATED="dipo.video_surface.created",CHANGED="dipo.video_surface.changed",DESTROYED="dipo.video_surface.destroyed";
    interface Scheduler {long now();boolean post(Runnable work,long delay);void cancel(Runnable work);void close();}
    static final class Worker implements Scheduler {
        final HandlerThread thread=new HandlerThread("ioniq5-video-recovery");final Handler handler;
        Worker(){thread.start();handler=new Handler(thread.getLooper());}
        public long now(){return SystemClock.elapsedRealtime();}
        public boolean post(Runnable work,long delay){return handler.postDelayed(work,delay);}
        public void cancel(Runnable work){handler.removeCallbacks(work);}
        public void close(){thread.quit();}
    }
    static final class Main implements Scheduler {
        final Handler handler=new Handler(Looper.getMainLooper());
        public long now(){return SystemClock.elapsedRealtime();}
        public boolean post(Runnable work,long delay){return handler.postDelayed(work,delay);}
        public void cancel(Runnable work){handler.removeCallbacks(work);}
        public void close(){} // The application owns the main looper.
    }
    static final class Session {
        final Object owner;final int generation,transport;boolean active,displayKnown,visible;int displayEpoch,modeEpoch,signalEpoch,screenOwner=-1;
        long timeoutAt=-1;int timeoutEpoch;
        WeakReference<Surface> surface=new WeakReference<Surface>(null);
        Session(Object owner,int generation,int transport){this.owner=owner;this.generation=generation;this.transport=transport;}
    }
    static final class Frame {
        final Session expected;final Frame previous;final int epoch,modeEpoch,signalEpoch;
        Frame(Session expected,Frame previous){this.expected=expected;this.previous=previous;this.epoch=expected==null?-1:expected.displayEpoch;modeEpoch=expected==null?-1:expected.modeEpoch;signalEpoch=expected==null?-1:expected.signalEpoch;}
    }
    static final class Resume {
        final Object target;final int epoch;final Resume previous;
        Resume(Object target,int epoch,Resume previous){this.target=target;this.epoch=epoch;this.previous=previous;}
    }
    private final ModContext mod;private final Scheduler worker,main;private final Method force,show,hide;
    private final ThreadLocal<Frame> starts=new ThreadLocal<Frame>(),events=new ThreadLocal<Frame>(),shows=new ThreadLocal<Frame>();
    private final ThreadLocal<Boolean> selfDisplay=new ThreadLocal<Boolean>();
    private final ThreadLocal<Resume> resumes=new ThreadLocal<Resume>();
    private volatile boolean installed;private boolean inFlight;private Session session;private Runnable pending;
    private int generation,activityEpoch;private long lastAttempt=-1,lastRebind=-1;
    private boolean resumed;private WeakReference<Object> activity=new WeakReference<Object>(null),holder=new WeakReference<Object>(null);
    private Rebind rebind;
    private final class Rebind implements Runnable {
        final Session expected;final int epoch,uiEpoch;final long queuedAt;final WeakReference<Surface> surface;boolean admitted,running;
        Rebind(Session expected){this.expected=expected;epoch=expected.displayEpoch;uiEpoch=activityEpoch;queuedAt=worker.now();surface=new WeakReference<Surface>(expected.surface.get());}
        public void run(){if(admitted)performRebind(this);else admitRebind(this);}
    }

    private CarPlayVideoRecovery(ModContext mod,Scheduler worker,Scheduler main,Method force,Method show,Method hide){
        this.mod=mod;this.worker=worker;this.main=main;this.force=force;this.show=show;this.hide=hide;
    }
    public static void install(ModContext mod,LoadedApp app){install(mod,app,null);}
    static CarPlayVideoRecovery install(ModContext mod,LoadedApp app,Scheduler scheduler) {
        return install(mod,app,scheduler,null);
    }
    static CarPlayVideoRecovery install(ModContext mod,LoadedApp app,Scheduler scheduler,Scheduler coordinator) {
        CarPlayVideoRecovery recovery=null;
        try {
            Method method=Points.method(app.classLoader,FORCE);
            if(method.getParameterTypes().length!=0||method.getReturnType()!=Integer.TYPE
                    ||java.lang.reflect.Modifier.isStatic(method.getModifiers()))throw new IllegalStateException();
            for(String point:new String[]{START,END,DECODE,TIMEOUT,SCREEN_START,STOP,MODE,RESUME,PAUSE,CREATED,CHANGED,DESTROYED})Points.method(app.classLoader,point);
            Method show=Points.method(app.classLoader,SHOW),hide=Points.method(app.classLoader,HIDE);
            if(show.getParameterTypes().length!=1||show.getParameterTypes()[0]!=Surface.class
                    ||!show.getReturnType().isEnum()||hide.getParameterTypes().length!=0
                    ||hide.getReturnType()!=show.getReturnType()
                    ||java.lang.reflect.Modifier.isStatic(show.getModifiers())
                    ||java.lang.reflect.Modifier.isStatic(hide.getModifiers()))throw new IllegalStateException();
            recovery=new CarPlayVideoRecovery(mod,scheduler==null?new Worker():scheduler,coordinator==null?new Main():coordinator,method,show,hide);
            recovery.hooks(app.classLoader);recovery.installed=true;
            mod.log("USB_CP_VIDEO recovery_ready cooldown_ms=2000 periodic=0 native_serial=1");
            mod.log("USB_CP_VIDEO rebind_ready cooldown_ms=10000 grace_ms=250 stop_window_ms=2000 periodic=0");
        }catch(Throwable unavailable){if(recovery!=null){recovery.worker.close();recovery.main.close();}mod.log("USB_CP_VIDEO recovery_unavailable");}
        return recovery;
    }
    private void hooks(ClassLoader loader) {
        mod.hook(loader,START,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){starts.set(new Frame(begin(p.thisObject,number(p.args[0])),starts.get()));}
            @Override protected void afterHookedMethod(Param p){Frame frame=pop(starts);if(frame!=null)accept(frame.expected,p.getThrowable()==null);}
        });
        mod.hook(loader,END,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){end(p.thisObject);}
        });
        for(final String point:new String[]{DECODE,TIMEOUT})mod.hook(loader,point,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){events.set(point.equals(TIMEOUT)?captureVideoEvent(p.thisObject,events.get()):captureCurrent(p.thisObject,events.get()));}
            @Override protected void afterHookedMethod(Param p){
                Frame frame=pop(events);if(frame==null||p.getThrowable()!=null)return;
                int reason=point.equals(DECODE)?1:2,code=reason==1?number(p.args[0]):-1;
                if(reason==2)timedOut(frame.expected,frame.epoch,frame.signalEpoch);
                if(reason==2||(code>=1&&code<=3))request(frame.expected,frame.epoch,reason,code);
            }
        });
        mod.hook(loader,SHOW,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){shows.set(Boolean.TRUE.equals(selfDisplay.get())?captureCurrent(p.thisObject,shows.get()):displayChanging(p.thisObject,shows.get()));}
            @Override protected void afterHookedMethod(Param p){
                Frame frame=pop(shows);if(frame==null)return;
                Surface surface=p.args[0] instanceof Surface?(Surface)p.args[0]:null;
                displayReady(frame.expected,frame.epoch,surface,p.getThrowable()==null&&ok(p.getResult()));
            }
        });
        mod.hook(loader,HIDE,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){if(!Boolean.TRUE.equals(selfDisplay.get()))displayChanging(p.thisObject,null);}
        });
        for(final String point:new String[]{SCREEN_START,STOP,MODE})mod.hook(loader,point,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){
                events.set(point.equals(MODE)?modeChanging(p.thisObject,number(p.args[0]),events.get()):captureVideoEvent(p.thisObject,events.get()));
            }
            @Override protected void afterHookedMethod(Param p){
                Frame frame=pop(events);if(frame==null||p.getThrowable()!=null)return;
                if(point.equals(STOP))stopped(frame.expected,frame.epoch,frame.signalEpoch);
                else if(point.equals(SCREEN_START))screenStarted(frame.expected,frame.signalEpoch);
                else modeReady(frame.expected,frame.modeEpoch,number(p.args[0]));
            }
        });
        mod.hook(loader,RESUME,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){resumes.set(activityResuming(p.thisObject,resumes.get()));}
            @Override protected void afterHookedMethod(Param p){
                Resume frame=resumes.get();if(frame==null)return;
                if(frame.previous==null)resumes.remove();else resumes.set(frame.previous);
                if(p.getThrowable()==null)activityResumed(frame);
            }
        });
        mod.hook(loader,PAUSE,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){activityPaused(p.thisObject);}
        });
        for(final String point:new String[]{CREATED,CHANGED,DESTROYED})mod.hook(loader,point,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){surfaceChanging(p.thisObject,p.args[0],point.equals(DESTROYED));}
        });
    }
    private static boolean ok(Object result){return result instanceof Enum&&((Enum<?>)result).ordinal()==0;}
    private static boolean valid(Surface surface){try{return surface!=null&&surface.isValid();}catch(Throwable ignored){return false;}}
    /** Invalidate before native display work, so queued work for the old renderer cannot run. */
    private synchronized Frame displayChanging(Object owner,Frame previous){
        if(!eligible(session)||session.owner!=owner)return new Frame(null,previous);
        invalidateDisplay(session);
        return new Frame(session,previous);
    }
    private synchronized void displayReady(Session expected,int epoch,Surface surface,boolean success){
        if(!eligible(expected)||expected.displayEpoch!=epoch||!success||!valid(surface))return;
        expected.surface=new WeakReference<Surface>(surface);expected.visible=true;
        // Retain the newest successful attachment through cooldown. It is an
        // observed event, never a periodic guess at a silent freeze.
        request(expected,epoch,3,0);
    }
    private static Frame pop(ThreadLocal<Frame> stack){Frame frame=stack.get();if(frame!=null){if(frame.previous==null)stack.remove();else stack.set(frame.previous);}return frame;}
    private static int number(Object value){return value instanceof Integer?((Integer)value).intValue():-1;}
    private synchronized Session begin(Object owner,int transport) {
        if(!installed||!mod.enabled()||owner==null)return null;
        cancelPending();cancelRebind();session=new Session(owner,++generation,transport);return session;
    }
    private synchronized void accept(Session expected,boolean success) {
        if(expected==null||session!=expected)return;
        if(!success||!mod.enabled()){cancelPending();cancelRebind();session=null;return;}expected.active=true;
    }
    private synchronized void end(Object owner){if(session!=null&&session.owner==owner){cancelPending();cancelRebind();session=null;}}
    private void cancelPending(){if(pending!=null){worker.cancel(pending);pending=null;}}
    private void cancelRebind(){if(rebind!=null){main.cancel(rebind);worker.cancel(rebind);rebind=null;}if(session!=null)session.timeoutAt=-1;}
    private void invalidateDisplay(Session expected){
        cancelPending();cancelRebind();expected.displayKnown=true;expected.visible=false;expected.surface.clear();expected.displayEpoch++;
    }
    private synchronized Resume activityResuming(Object target,Resume previous){
        if(!installed||!mod.enabled())return new Resume(target,-1,previous);
        cancelRebind();activity=new WeakReference<Object>(target);resumed=false;activityEpoch++;
        return new Resume(target,activityEpoch,previous);
    }
    private synchronized void activityResumed(Resume frame){
        if(installed&&mod.enabled()&&frame.epoch==activityEpoch&&activity.get()==frame.target)resumed=true;
    }
    private synchronized void activityPaused(Object target){
        if(activity.get()!=target)return;
        resumed=false;activityEpoch++;cancelRebind();
    }
    private synchronized Frame modeChanging(Object owner,int value,Frame previous){
        if(!eligible(session)||session.owner!=owner)return new Frame(null,previous);
        if(session.screenOwner!=value){cancelRebind();session.screenOwner=-1;}
        session.modeEpoch++; // A nested newer mode must win over this callback's later completion.
        return new Frame(session,previous);
    }
    private synchronized void modeReady(Session expected,int epoch,int value){if(eligible(expected)&&expected.modeEpoch==epoch)expected.screenOwner=value;}
    private synchronized void surfaceChanging(Object target,Object current,boolean destroyed){
        if(!installed||!mod.enabled()||activity.get()!=target)return;
        if(destroyed&&holder.get()!=current)return;
        cancelRebind();
        if(eligible(session))invalidateDisplay(session);
        if(destroyed)holder.clear();else holder=new WeakReference<Object>(current);
    }
    private boolean rebindEligible(Session expected){
        return displayEligible(expected)&&expected.displayKnown&&expected.visible&&expected.screenOwner==1
                &&resumed&&activity.get()!=null&&valid(expected.surface.get());
    }
    private synchronized void timedOut(Session expected,int epoch,int signal){
        if(!rebindEligible(expected)||expected.displayEpoch!=epoch||expected.signalEpoch!=signal||rebind!=null)return;
        expected.timeoutAt=worker.now();expected.timeoutEpoch=epoch;
    }
    private synchronized void screenStarted(Session expected,int signal){
        if(!eligible(expected)||expected.signalEpoch!=signal)return;
        expected.timeoutAt=-1;
        // A spontaneous start cancels a queued repair. A start caused by our own
        // already-running reattachment is an observation, not a reason to stop halfway.
        if(rebind!=null&&!rebind.running)cancelRebind();
    }
    private synchronized void stopped(Session expected,int epoch,int signal){
        if(!eligible(expected)||expected.signalEpoch!=signal)return;
        long timeout=expected.timeoutAt,now=worker.now();expected.timeoutAt=-1;
        if(timeout<0||now<timeout||now-timeout>STOP_WINDOW_MS||expected.timeoutEpoch!=epoch
                ||!rebindEligible(expected)||expected.displayEpoch!=epoch||rebind!=null||!rebindCooled(now))return;
        rebind=new Rebind(expected);
        if(!main.post(rebind,REBIND_GRACE_MS))rebind=null;
    }
    private boolean rebindCooled(long now){return lastRebind<0||(now>=lastRebind&&now-lastRebind>=REBIND_GAP_MS);}
    private boolean current(Rebind work){
        return rebind==work&&rebindEligible(work.expected)&&work.epoch==work.expected.displayEpoch
                &&work.uiEpoch==activityEpoch&&work.surface.get()==work.expected.surface.get()&&valid(work.surface.get())
                &&(work.running||(worker.now()>=work.queuedAt&&worker.now()-work.queuedAt<=REBIND_MAX_WAIT_MS));
    }
    private synchronized void admitRebind(Rebind work){
        if(!current(work)||!rebindCooled(worker.now())){if(rebind==work)cancelRebind();return;}
        work.admitted=true;
        if(!worker.post(work,0))cancelRebind();
    }
    private void performRebind(Rebind work){
        final long began;final Surface target;
        synchronized(this){
            if(!current(work)||inFlight||!rebindCooled(worker.now())){if(rebind==work)cancelRebind();return;}
            target=work.surface.get();began=worker.now();lastRebind=began;work.running=true;inFlight=true;cancelPending();
        }
        int hidden=-1,shown=-1;boolean exception=false;
        try{
            mod.log("USB_CP_VIDEO rebind_request generation="+work.expected.generation+" elapsed_ms="+began+" epoch="+work.epoch);
            // Main-thread admission precedes this single worker. Both mapped OEM
            // wrappers call their exact synchronous JNI entries, without spawning
            // OEM nonBlockingMode threads. Never restart the CarPlay/Wi-Fi session.
            selfDisplay.set(Boolean.TRUE);
            Object result=hide.invoke(work.expected.owner);hidden=ordinal(result);
            synchronized(this){if(!current(work)||!ok(result))return;}
            result=show.invoke(work.expected.owner,target);shown=ordinal(result);
            synchronized(this){
                if(current(work)){
                    if(ok(result))displayReady(work.expected,work.epoch,target,true);
                    else invalidateDisplay(work.expected);
                }
            }
        }catch(Throwable failure){exception=true;}
        finally{
            selfDisplay.remove();
            synchronized(this){
                boolean stillCurrent=current(work);
                if(stillCurrent&&(hidden!=0||shown!=0||exception))invalidateDisplay(work.expected);
                if(rebind==work)rebind=null;
                inFlight=false;
                // This firmware's display JNI returns 0 after invoking an external
                // callback; it does NOT confirm new frames. Keep results literal.
                if(mod.enabled())mod.log("USB_CP_VIDEO rebind_result generation="+work.expected.generation+" elapsed_ms="+worker.now()
                        +" duration_ms="+Math.max(0,worker.now()-began)+" hide_result="+hidden+" show_result="+shown
                        +" exception="+(exception?1:0)+" current="+(stillCurrent?1:0));
            }
        }
    }
    private static int ordinal(Object result){return result instanceof Enum?((Enum<?>)result).ordinal():-1;}
    private synchronized Frame captureCurrent(Object owner,Frame previous){return new Frame(displayEligible(session)&&session.owner==owner?session:null,previous);}
    private synchronized Frame captureVideoEvent(Object owner,Frame previous){
        if(!displayEligible(session)||session.owner!=owner)return new Frame(null,previous);
        session.signalEpoch++;return new Frame(session,previous);
    }
    private boolean displayEligible(Session expected){return eligible(expected)&&(!expected.displayKnown||(expected.visible&&valid(expected.surface.get())));}
    private boolean eligible(Session expected){return installed&&mod.enabled()&&expected!=null&&session==expected&&expected.active&&expected.transport==1;}
    private boolean cooled(long now){return lastAttempt<0||(now>=lastAttempt&&now-lastAttempt>=MIN_GAP_MS);}
    private long remaining(long now){return lastAttempt<0?0:now<lastAttempt?MIN_GAP_MS:Math.max(0,MIN_GAP_MS-(now-lastAttempt));}
    private synchronized void request(final Session expected,final int epoch,final int reason,final int code) {
        if(!displayEligible(expected)||expected.displayEpoch!=epoch||pending!=null)return;
        long now=worker.now();
        // SHOW is a one-shot refresh for a specific attachment. Preserve one
        // pending event during cooldown/in-flight work; the single worker runs
        // it only after the prior call returns. Other errors keep the drop policy.
        if(reason!=3&&(inFlight||!cooled(now)))return;
        pending=new Runnable(){public void run(){perform(expected,epoch,this,reason,code);}};
        if(!worker.post(pending,reason==3?remaining(now):0))pending=null;
    }
    private void perform(Session expected,int epoch,Runnable work,int reason,int code) {
        final long began;
        synchronized(this) {
            if(pending!=work)return;pending=null;
            if(!displayEligible(expected)||expected.displayEpoch!=epoch||inFlight)return;
            if(!cooled(worker.now())){if(reason==3)request(expected,epoch,reason,code);return;}
            inFlight=true;began=worker.now();lastAttempt=began;
        }
        int result=-1;boolean exception=false;
        try {
            mod.log("USB_CP_VIDEO recovery_request generation="+expected.generation+" elapsed_ms="+began+" reason="+reason+" code="+code);
            // One worker invokes the exact verified JNI entry. The public wrapper can create
            // an unbounded OEM thread per call in nonBlockingMode, so is deliberately unused.
            // JNI accepts no session token. An admitted call racing teardown cannot
            // be cancelled or prove which internal native session receives it.
            Object returned=force.invoke(expected.owner);result=number(returned);
        }catch(Throwable failure){exception=true;}
        finally {
            synchronized(this) {
                inFlight=false;
                if(mod.enabled())mod.log("USB_CP_VIDEO recovery_result generation="+expected.generation+" elapsed_ms="+worker.now()
                        +" duration_ms="+Math.max(0,worker.now()-began)+" native_result="+result+" exception="+(exception?1:0)
                        +" current="+(displayEligible(expected)&&expected.displayEpoch==epoch?1:0));
            }
        }
    }
}
