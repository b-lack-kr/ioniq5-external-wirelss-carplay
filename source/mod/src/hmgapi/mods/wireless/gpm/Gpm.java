package hmgapi.mods.wireless.gpm;

import android.content.ComponentName;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;

/** AA display adapter. External AP credentials are provided by a separate, shared-AP adapter. */
public final class Gpm {
    static final String BASE="gpm.navigation_status.";
    static final String INIT=BASE+"init",TURN=BASE+"next_turn",DISTANCE=BASE+"next_turn_distance",STATUS=BASE+"status";
    static final String FOCUS="gpm.receiver.send_navigation_focus",END="gpm.receiver.destroy";
    static final String USB="framework.context_impl.start_service";
    static final int MAX_EVENTS=128,MAX_TEXT=65536;
    interface Scheduler {Handler handler();boolean post(Runnable work);void close();}
    interface NativeReady {boolean eligible();boolean applied();}
    static final class Worker implements Scheduler {
        final HandlerThread thread=new HandlerThread("ioniq5-aa-tbt");final Handler handler;
        Worker(){thread.start();handler=new Handler(thread.getLooper());}
        public Handler handler(){return handler;}public boolean post(Runnable r){return handler.post(r);}public void close(){thread.quit();}
    }
    static final class Session {
        final WeakReference<Object> owner;volatile boolean accepted,closed,nativeOwned,needsForget;int focusEpoch,turnsEpoch=-1;AaTurns turns;
        Session(Object owner){this.owner=new WeakReference<Object>(owner);}
    }
    static final class Frame {
        final Session session;final Frame previous;final int epoch;final long focusSerial;
        Frame(Session session,Frame previous){this(session,previous,-1);}
        Frame(Session session,Frame previous,long focusSerial){this.session=session;this.previous=previous;epoch=session==null?-1:session.focusEpoch;this.focusSerial=focusSerial;}
    }
    static final class Event {
        final Session session;final int kind,epoch;final long focusSerial;final String text;final int[] values;
        Event(Session session,int epoch,long focusSerial,int kind,String text,int[] values){this.session=session;this.epoch=epoch;this.focusSerial=focusSerial;this.kind=kind;this.text=text;this.values=values;}
    }
    private final ModContext mod;private final LoadedApp app;private final Scheduler worker;
    private final Object image,enumerated;private final int nativeFocus,projectedFocus;private final boolean standard;private final NativeReady nativeReady;
    private final ArrayDeque<Event> queue=new ArrayDeque<Event>();
    private final ArrayList<Session> retired=new ArrayList<Session>();
    private final ThreadLocal<Frame> initFrames=new ThreadLocal<Frame>(),eventFrames=new ThreadLocal<Frame>();
    private volatile boolean ready;private boolean scheduled,reset;private long dropped;private Session current;
    private boolean nativeDisplay,focusObserved;private long focusSerial;
    private WeakReference<Object> focusedOwner=new WeakReference<Object>(null);
    private int nativeReported=-1;
    private final Runnable drain=new Runnable(){public void run(){drain();}};

    private Gpm(ModContext mod,LoadedApp app,Scheduler worker,Object image,Object enumerated,int nativeFocus,int projectedFocus,NativeReady nativeReady){
        this.mod=mod;this.app=app;this.worker=worker;this.image=image;this.enumerated=enumerated;this.nativeFocus=nativeFocus;
        this.projectedFocus=projectedFocus;this.nativeReady=nativeReady;
        standard=mod.settingBoolean("ar_view",false);
    }
    public static void install(ModContext mod,LoadedApp app){install(mod,app,null,null);}
    static Gpm install(final ModContext mod,final LoadedApp app,Scheduler scheduler,NativeReady nativeReady) {
        usb(mod,app);
        if(Car.stepOfSpec()>=999){mod.log("USB_CP_AA stock_tbt=1");return null;}
        Gpm gpm=null;
        try {
            if(nativeReady==null)nativeReady=new NativeRecipeProof(mod);
            if(!nativeReady.eligible())throw new IllegalStateException();
            for(String p:new String[]{INIT,TURN,DISTANCE,STATUS,FOCUS,END}){
                Method method=Points.method(app.classLoader,p);
                if(Modifier.isStatic(method.getModifiers())||method.getReturnType()!=(p.equals(INIT)?Boolean.TYPE:Void.TYPE))throw new IllegalStateException();
            }
            Points.field(app.classLoader,BASE+"receiver");
            Object image=Points.get(app.classLoader,"gpm.cluster_type.image",null),enumerated=Points.get(app.classLoader,"gpm.cluster_type.enum",null);
            int focus=((Integer)Points.get(app.classLoader,"gpm.receiver.focus_native",null)).intValue();
            java.lang.reflect.Field projected=Points.method(app.classLoader,FOCUS).getDeclaringClass().getField("NAVIGATION_FOCUS_STATE_PROJECTED");
            if(projected.getType()!=Integer.TYPE||!Modifier.isStatic(projected.getModifiers()))throw new IllegalStateException();
            int projectedFocus=projected.getInt(null);
            if(image==null||enumerated==null||image==enumerated)throw new IllegalStateException();
            if(projectedFocus==focus)throw new IllegalStateException();
            gpm=new Gpm(mod,app,scheduler==null?new Worker():scheduler,image,enumerated,focus,projectedFocus,nativeReady);
            gpm.hooks();
            mod.nativePatch(app,"libautoreceiver_infobank.so");
            gpm.ready=true;
            mod.log("USB_CP_AA tbt_ready standard="+(gpm.standard?1:0)+" queue_limit=128 native_recipe_requested=1");
        }catch(Throwable unavailable){if(gpm!=null){gpm.ready=false;gpm.worker.close();}mod.log("USB_CP_AA tbt_unavailable");}
        return gpm;
    }
    private static void usb(final ModContext mod,LoadedApp app) {
        try {
            Points.method(null,USB);
            mod.hook(null,USB,new MethodHook(){
                @Override protected void beforeHookedMethod(Param p){
                    if(!mod.enabled()||p.args.length==0||!(p.args[0] instanceof Intent))return;
                    Intent intent=(Intent)p.args[0];ComponentName component=intent.getComponent();
                    if(component==null||!"com.google.android.projection.service.GalReceiverService".equals(component.getClassName())
                            ||intent.getIntExtra("startMode",-1)!=1)return;
                    Object device=intent.getParcelableExtra("device");
                    if(device instanceof UsbDevice&&notPhone((UsbDevice)device))p.setResult(null);
                }
            });
            mod.log("USB_CP_AA usb_guard_ready=1");
        }catch(Throwable unavailable){mod.log("USB_CP_AA usb_guard_ready=0");}
    }
    static boolean notPhone(UsbDevice device) {
        if(device==null||device.getInterfaceCount()==0)return false;
        for(int i=0;i<device.getInterfaceCount();i++){int type=device.getInterface(i).getInterfaceClass();if(type!=8&&type!=9)return false;}
        return true;
    }
    private Object receiver(Object status){try{return Points.get(app.classLoader,BASE+"receiver",status);}catch(Throwable ignored){return null;}}
    private void hooks() {
        mod.hook(app.classLoader,INIT,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){
                Session s=begin(receiver(p.thisObject));initFrames.set(new Frame(s,initFrames.get()));
                if(s!=null&&p.args[3]==image)p.args[3]=enumerated;
            }
            @Override protected void afterHookedMethod(Param p){Frame f=pop(initFrames);if(f!=null)accept(f.session,p.getThrowable()==null&&Boolean.TRUE.equals(p.getResult()));}
        });
        for(final String point:new String[]{TURN,DISTANCE,STATUS,FOCUS})mod.hook(app.classLoader,point,new MethodHook(){
            @Override protected void beforeHookedMethod(Param p){
                Object owner=point.equals(FOCUS)?p.thisObject:receiver(p.thisObject);
                synchronized(Gpm.this){
                    Session s=capture(owner,point.equals(FOCUS));
                    int focus=point.equals(FOCUS)?num(p.args[0]):-1;
                    if(point.equals(FOCUS)&&(focus==nativeFocus||focus==projectedFocus))focusSerial++;
                    if(point.equals(FOCUS)&&focus==nativeFocus){
                        nativeDisplay=true;focusObserved=true;focusedOwner=new WeakReference<Object>(owner);
                        nativeFocus(current);
                    }
                    eventFrames.set(new Frame(s,eventFrames.get(),focusSerial));
                }
            }
            @Override protected void afterHookedMethod(Param p){
                Frame f=pop(eventFrames);if(f==null||p.getThrowable()!=null)return;
                int epoch=f.epoch;
                if(point.equals(FOCUS)&&num(p.args[0])==projectedFocus)epoch=projectedFocus(f.session,epoch,f.focusSerial,p.thisObject);
                if(point.equals(TURN))enqueue(new Event(f.session,epoch,f.focusSerial,2,p.args[0] instanceof String?(String)p.args[0]:null,new int[]{num(p.args[1]),num(p.args[2]),num(p.args[4]),num(p.args[5])}));
                else if(point.equals(DISTANCE))enqueue(new Event(f.session,epoch,f.focusSerial,3,null,new int[]{num(p.args[0]),num(p.args[1]),num(p.args[2]),num(p.args[3])}));
                else enqueue(new Event(f.session,epoch,f.focusSerial,point.equals(STATUS)?1:4,null,new int[]{num(p.args[0])}));
            }
        });
        mod.hook(app.classLoader,END,new MethodHook(){@Override protected void beforeHookedMethod(Param p){end(p.thisObject);}});
    }
    private static int num(Object v){return v instanceof Integer?((Integer)v).intValue():-1;}
    private static Frame pop(ThreadLocal<Frame> local){Frame f=local.get();if(f!=null){if(f.previous==null)local.remove();else local.set(f.previous);}return f;}
    private synchronized Session begin(Object owner){
        if(!ready||!mod.enabled()||owner==null)return null;
        boolean patched=nativeReady.applied();int state=patched?1:0;
        if(nativeReported!=state){nativeReported=state;mod.log("USB_CP_AA native_applied="+state);}
        if(!patched)return null;
        retire(current);current=new Session(owner);current.nativeOwned=nativeDisplay;return current;
    }
    private synchronized void accept(Session s,boolean success){if(s==null||current!=s)return;if(success&&mod.enabled()){s.accepted=true;if(!queue.isEmpty())schedule();}else{retire(s);current=null;if(!queue.isEmpty())schedule();}}
    private synchronized Session capture(Object owner,boolean focus){return current!=null&&current.owner.get()==owner&&exists(current)&&(focus||!nativeDisplay&&!current.nativeOwned&&focusMatches(current))?current:null;}
    private boolean exists(Session s){return ready&&mod.enabled()&&s!=null&&s.owner.get()!=null&&current==s&&!s.closed;}
    private boolean focusMatches(Session s){Object focused=focusedOwner.get();return !focusObserved||focused!=null&&focused==s.owner.get();}
    private boolean valid(Session s){return exists(s)&&s.accepted&&!nativeDisplay&&!s.nativeOwned&&focusMatches(s);}
    private synchronized void nativeFocus(Session s){if(exists(s)){s.nativeOwned=true;s.needsForget=true;s.focusEpoch++;}}
    private synchronized int projectedFocus(Session s,int epoch,long serial,Object owner){
        if(serial!=focusSerial)return -1;
        nativeDisplay=false;focusObserved=true;focusedOwner=new WeakReference<Object>(owner);
        if(exists(s)&&s.focusEpoch==epoch){s.nativeOwned=false;return ++s.focusEpoch;}
        if(s==null&&current!=null&&current.owner.get()==owner){current.nativeOwned=false;current.focusEpoch++;}
        return -1;
    }
    private synchronized void end(Object owner){if(current!=null&&current.owner.get()==owner){retire(current);current=null;}}
    private void retire(Session s){if(s==null)return;s.closed=true;if(s.turns!=null&&!retired.contains(s)){retired.add(s);schedule();}}
    private synchronized void enqueue(Event e){
        if(!exists(e.session)||e.epoch!=e.session.focusEpoch||e.focusSerial!=focusSerial||(nativeDisplay||e.session.nativeOwned)&&e.kind!=4)return;
        if(e.text!=null&&e.text.length()>MAX_TEXT){dropped++;reset=true;schedule();return;}
        if(queue.size()>=MAX_EVENTS){dropped+=queue.size()+1;queue.clear();reset=true;schedule();return;}
        queue.add(e);schedule();
    }
    private void schedule(){if(!scheduled){scheduled=true;if(!worker.post(drain)){scheduled=false;queue.clear();}}}
    private void drain(){
        for(int count=0;count<32;count++){
            Event e;Session old=null;boolean clear=false;long lost=0;
            synchronized(this){
                if(!retired.isEmpty())old=retired.remove(0);
                if(reset){clear=true;reset=false;lost=dropped;dropped=0;}
                e=queue.poll();
                if(e!=null&&exists(e.session)&&!e.session.accepted){queue.addFirst(e);e=null;}
                if(old==null&&!clear&&e==null){scheduled=false;return;}
            }
            if(old!=null)finish(old);
            if(clear){Session s;synchronized(this){s=current;}if(s!=null)finish(s);mod.log("USB_CP_AA queue_reset dropped="+lost);}
            if(e!=null)dispatch(e);
        }
        synchronized(this){scheduled=false;if(!queue.isEmpty()||!retired.isEmpty()||reset)schedule();}
    }
    private void finish(Session s){
        AaTurns t;synchronized(this){t=s.turns;}
        if(t!=null)try{if(!mayRelease(s))t.forget();else t.end();}
        catch(Throwable ignored){t.forget();mod.log("USB_CP_AA release_failed=1");}
    }
    private boolean mayRelease(Session owned){
        boolean allowed;
        synchronized(this){allowed=mod.enabled()&&owned.owner.get()!=null&&!nativeDisplay&&!owned.nativeOwned&&focusMatches(owned)
                &&(current==owned||current==null||current.turns==null||!current.turns.navLive);}
        return allowed&&!Car.carPlayGuiding(app.context);
    }
    private void dispatch(final Event e){
        try {
            synchronized(this){if(!exists(e.session)||!e.session.accepted||e.epoch!=e.session.focusEpoch||e.focusSerial!=focusSerial)return;if(nativeDisplay||e.session.nativeOwned||!focusMatches(e.session)){if(e.session.turns!=null)e.session.turns.forget();return;}}
            AaTurns t=e.session.turns;
            if(e.session.needsForget||(t!=null&&e.session.turnsEpoch!=e.session.focusEpoch)){if(t!=null)t.forget();e.session.turns=null;t=null;e.session.needsForget=false;}
            if(t==null){
                Cluster cluster=Cluster.of(app.context);if(cluster==null)return;
                t=new AaTurns(app.context,cluster,worker.handler());t.standardTurns=standard;t.setCloseApproach(true);
                final Session owned=e.session;
                final int ownedEpoch=e.session.focusEpoch;
                final ApproachSender approach=new ApproachSender(app.context,worker.handler(),
                    new ApproachSender.Clock(){public long now(){return SystemClock.elapsedRealtime();}},
                    new ApproachSender.Owner(){public Object current(){synchronized(Gpm.this){return valid(owned)&&owned.focusEpoch==ownedEpoch&&!owned.needsForget?owned:null;}}},
                    new ApproachSender.Gate(){public boolean allowed(Object token){boolean active;synchronized(Gpm.this){active=token==owned&&valid(owned)&&owned.focusEpoch==ownedEpoch&&!owned.needsForget;}
                        return active&&standard&&mod.settingBoolean("ar_view",false)
                                &&Car.androidAutoGuiding(app.context)&&!Car.carPlayGuiding(app.context);}},ApproachSender.SOURCE_ANDROID_AUTO);
                t.traceSink=new AaTurns.TraceSink(){public void event(String line){mod.log(line);}};
                t.approach=new AaTurns.ApproachSink(){public void update(boolean active,int bar){approach.update(active,bar);}};
                t.owner=new AaTurns.Owner(){
                    public boolean active(){boolean active;synchronized(Gpm.this){active=valid(owned)&&owned.focusEpoch==ownedEpoch&&!owned.needsForget;}return active&&!Car.carPlayGuiding(app.context);}
                    public boolean release(){return mayRelease(owned);}
                };
                synchronized(this){e.session.turns=t;e.session.turnsEpoch=ownedEpoch;if(!valid(e.session)){t.forget();return;}}
            }
            if(Car.carPlayGuiding(app.context)){t.forget();return;}
            if(e.kind==1)t.status(e.values[0]);
            else if(e.kind==4){if(e.values[0]==nativeFocus)t.forget();}
            else if(t.navLive||t.lost||(e.kind==2&&e.values[1]==AaTurns.EV_LANES&&!t.ended)){
                if(e.kind==2)t.nextTurn(e.text,e.values[0],e.values[1],e.values[2],e.values[3]);
                else t.nextTurnDistance(e.values[0],e.values[1],e.values[2],e.values[3]);
            }
        }catch(Throwable ignored){mod.log("USB_CP_AA callback_failed=1");}
    }
}
