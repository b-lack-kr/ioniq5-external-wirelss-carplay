package hmgapi.mods.wireless.dipo;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import android.view.Surface;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class VideoRecoveryTest {
    static int checks;
    static void check(boolean pass,String why){checks++;if(!pass)throw new AssertionError(why);}
    enum DisplayResult { OK, ERROR }
    static final class Native {
        int requests,result;boolean throwRequest,worker;Runnable inside;
        int detaches,reattaches;DisplayResult hideResult=DisplayResult.OK,showResult=DisplayResult.OK;Runnable insideHide,insideShow;
        public void start(int transport){}public void end(int transport){}public void decode(int code){}public void timeout(){}
        public void screenStart(){}public void screenStop(){}public void mode(int a,int b,int c,int d,int e,int f){}
        public void resume(){}public void pause(){}public void surface(Object holder){}
        public DisplayResult show(final Surface surface)throws Throwable{
            return (DisplayResult)ModContext.dispatch(CarPlayVideoRecovery.SHOW,this,new Object[]{surface},new ModContext.Original(){public Object run(){
                check(worker,"show JNI stays on single worker, not main coordinator");reattaches++;if(insideShow!=null)insideShow.run();return showResult;}});
        }
        public DisplayResult hide()throws Throwable{
            return (DisplayResult)ModContext.dispatch(CarPlayVideoRecovery.HIDE,this,new Object[]{},new ModContext.Original(){public Object run(){
                check(worker,"hide JNI stays on single worker, not main coordinator");detaches++;if(insideHide!=null)insideHide.run();return hideResult;}});
        }
        private int keyframe(){check(worker,"native invoked on explicit worker only");requests++;if(inside!=null)inside.run();if(throwRequest)throw new IllegalStateException("private error");return result;}
        public String bad(){return "not-int";}
        @Override public String toString(){throw new AssertionError("owner content must never be logged");}
    }
    static final class Worker implements CarPlayVideoRecovery.Scheduler {
        long now=100;boolean closed,reject,main;int posts;final List<Runnable> queue=new ArrayList<Runnable>();
        final Map<Runnable,Long> due=new IdentityHashMap<Runnable,Long>();Native owner;
        public long now(){return now;}
        public boolean post(Runnable r,long delay){posts++;if(reject)return false;queue.add(r);due.put(r,now+delay);return true;}
        public void cancel(Runnable r){queue.remove(r);due.remove(r);}public void close(){closed=true;queue.clear();due.clear();}
        void run(){List<Runnable> ready=new ArrayList<Runnable>();for(Runnable r:queue)if(due.get(r)<=now)ready.add(r);for(Runnable r:ready){queue.remove(r);due.remove(r);}
            owner.worker=!main;try{for(Runnable r:ready)r.run();}finally{owner.worker=false;}}
    }
    static final class Rig {
        final ModContext mod=new ModContext();final Worker worker=new Worker(),main=new Worker();final Native owner=new Native();final Object activity=new Object(),holder=new Object();final CarPlayVideoRecovery recovery;
        int originals;
        Rig(String reject,boolean bad)throws Exception {
            worker.owner=owner;main.owner=owner;main.main=true;Points.methods.clear();
            Points.methods.put(CarPlayVideoRecovery.FORCE,Native.class.getDeclaredMethod(bad?"bad":"keyframe"));
            Points.methods.put(CarPlayVideoRecovery.START,Native.class.getMethod("start",int.class));
            Points.methods.put(CarPlayVideoRecovery.END,Native.class.getMethod("end",int.class));
            Points.methods.put(CarPlayVideoRecovery.DECODE,Native.class.getMethod("decode",int.class));
            Points.methods.put(CarPlayVideoRecovery.TIMEOUT,Native.class.getMethod("timeout"));
            Points.methods.put(CarPlayVideoRecovery.SHOW,Native.class.getMethod("show",Surface.class));
            Points.methods.put(CarPlayVideoRecovery.HIDE,Native.class.getMethod("hide"));
            Points.methods.put(CarPlayVideoRecovery.SCREEN_START,Native.class.getMethod("screenStart"));
            Points.methods.put(CarPlayVideoRecovery.STOP,Native.class.getMethod("screenStop"));
            Points.methods.put(CarPlayVideoRecovery.MODE,Native.class.getMethod("mode",int.class,int.class,int.class,int.class,int.class,int.class));
            Points.methods.put(CarPlayVideoRecovery.RESUME,Native.class.getMethod("resume"));
            Points.methods.put(CarPlayVideoRecovery.PAUSE,Native.class.getMethod("pause"));
            for(String point:new String[]{CarPlayVideoRecovery.CREATED,CarPlayVideoRecovery.CHANGED,CarPlayVideoRecovery.DESTROYED})
                Points.methods.put(point,Native.class.getMethod("surface",Object.class));
            mod.rejectPoint=reject;recovery=CarPlayVideoRecovery.install(mod,new LoadedApp(),worker,main);
        }
        void call(String point,Object target,Object...args)throws Throwable {ModContext.dispatch(point,target,args,new ModContext.Original(){public Object run(){originals++;return null;}});}
        void start(int type)throws Throwable {call(CarPlayVideoRecovery.START,owner,type);}
        void end()throws Throwable {call(CarPlayVideoRecovery.END,owner,1);}
        void decode(int code)throws Throwable {call(CarPlayVideoRecovery.DECODE,owner,code);}
        void timeout()throws Throwable {call(CarPlayVideoRecovery.TIMEOUT,owner);}
        void show(final Surface surface,final DisplayResult result)throws Throwable {
            ModContext.dispatch(CarPlayVideoRecovery.SHOW,owner,new Object[]{surface},new ModContext.Original(){public Object run(){originals++;return result;}});
        }
        void hide()throws Throwable {call(CarPlayVideoRecovery.HIDE,owner);}
        void at(long time){worker.now=time;main.now=time;}
        void stopScreen()throws Throwable {call(CarPlayVideoRecovery.STOP,owner);}
        void startScreen()throws Throwable {call(CarPlayVideoRecovery.SCREEN_START,owner);}
        void mode(int screen)throws Throwable {call(CarPlayVideoRecovery.MODE,owner,screen,1,2,1,2,0);}
        void foreground(Surface surface)throws Throwable {
            start(1);call(CarPlayVideoRecovery.RESUME,activity);call(CarPlayVideoRecovery.CREATED,activity,holder);
            call(CarPlayVideoRecovery.CHANGED,activity,holder,1,1920,720);mode(1);show(surface,DisplayResult.OK);startScreen();worker.run();
        }
        void incident()throws Throwable {timeout();worker.run();at(worker.now+20);stopScreen();}
        void repair(){at(worker.now+250);main.run();worker.run();}
        String last(){return mod.logs.get(mod.logs.size()-1);}
    }
    public static void main(String[] args)throws Throwable {
        Rig r=new Rig(null,false);check(r.last().contains("periodic=0"),"explicit nonperiodic policy");
        r.decode(1);r.timeout();check(r.worker.queue.isEmpty(),"callbacks without session ignored");
        for(int type:new int[]{0,2,-1}){r.start(type);r.decode(1);r.timeout();check(r.worker.queue.isEmpty(),"only wireless transport1 eligible");}
        r.start(1);for(int type:new int[]{-1,0,4,Integer.MAX_VALUE})r.decode(type);
        check(r.worker.queue.isEmpty(),"only verified failure enums1..3 eligible");
        r.decode(1);for(int i=0;i<1000;i++){r.decode(2);r.timeout();}
        check(r.worker.queue.size()==1&&r.worker.posts==1,"burst retains at most one queued request");
        check(r.owner.requests==0,"callback never invokes JNI");r.worker.run();
        check(r.owner.requests==1&&r.last().contains("native_result=0 exception=0 current=1"),"worker result numeric only");
        r.decode(2);r.worker.now+=1999;r.timeout();check(r.worker.queue.isEmpty(),"both triggers share cooldown");
        r.worker.now++;r.decode(3);r.worker.run();check(r.owner.requests==2,"exact2second boundary permits next observed failure");
        int logs=r.mod.logs.size();r.worker.now+=100000;r.worker.run();check(r.mod.logs.size()==logs&&r.owner.requests==2,"time passage alone never generates request");
        r.owner.result=-6700;r.timeout();r.worker.run();check(r.last().contains("native_result=-6700 exception=0"),"native rejection retained without retry");
        r.worker.now+=2000;r.owner.throwRequest=true;r.decode(1);r.worker.run();
        check(r.last().contains("native_result=-1 exception=1")&&!r.last().contains("private"),"native exception private and cooldown preserved");
        r.timeout();check(r.worker.queue.isEmpty(),"exception does not retry immediately");r.owner.throwRequest=false;
        r.worker.now+=2000;r.timeout();Runnable stale=r.worker.queue.get(0);r.end();check(r.worker.queue.isEmpty(),"finalization cancels pending task");
        int requests=r.owner.requests;r.start(1);stale.run();check(r.owner.requests==requests,"old queued generation never binds new session");
        r.call(CarPlayVideoRecovery.DECODE,new Native(),1);check(r.worker.queue.isEmpty(),"foreign owner ignored");
        r.timeout();r.mod.on=false;r.worker.run();check(r.owner.requests==requests,"disabled before worker prevents request");r.mod.on=true;
        r.worker.reject=true;r.timeout();r.worker.reject=false;r.timeout();check(r.worker.queue.size()==1,"post failure leaves no poisoned pending flag");r.worker.run();
        for(String line:r.mod.logs)check(line.matches("[A-Za-z0-9_= -]+")&&line.length()<256,"bounded numeric recovery logs only");

        final IllegalStateException error=new IllegalStateException("original error");
        Rig failed=new Rig(null,false);
        try{ModContext.dispatch(CarPlayVideoRecovery.START,failed.owner,new Object[]{1},new ModContext.Original(){public Object run(){throw error;}});throw new AssertionError();}
        catch(IllegalStateException actual){check(actual==error,"original start failure retained");}
        failed.decode(1);check(failed.worker.queue.isEmpty(),"failed start never accepted");
        failed.start(1);
        try{ModContext.dispatch(CarPlayVideoRecovery.DECODE,failed.owner,new Object[]{1},new ModContext.Original(){public Object run(){throw error;}});throw new AssertionError();}
        catch(IllegalStateException actual){check(actual==error,"original callback failure retained");}
        check(failed.worker.queue.isEmpty(),"failed callback does not enqueue");
        final Rig nested=new Rig(null,false);
        ModContext.dispatch(CarPlayVideoRecovery.START,nested.owner,new Object[]{1},new ModContext.Original(){public Object run()throws Throwable{nested.decode(1);nested.end();return null;}});
        nested.decode(1);check(nested.worker.queue.isEmpty(),"no recovery before accepted start or after nested finalization");
        nested.start(1);
        ModContext.dispatch(CarPlayVideoRecovery.DECODE,nested.owner,new Object[]{1},new ModContext.Original(){public Object run()throws Throwable{nested.end();nested.start(1);return null;}});
        check(nested.worker.queue.isEmpty(),"old callback completion cannot request against replacement generation");
        nested.owner.inside=new Runnable(){public void run(){try{nested.end();nested.start(1);nested.worker.now+=3000;nested.timeout();}catch(Throwable t){throw new AssertionError(t);}}};
        nested.decode(1);nested.worker.run();
        check(nested.worker.queue.isEmpty()&&nested.owner.requests==1&&nested.last().contains("current=0"),"in-flight request stays original owner and blocks new session enqueue");
        nested.owner.inside=null;nested.timeout();nested.worker.run();check(nested.owner.requests==2,"new observed event can recover after prior in-flight completion");
        final Rig blocking=new Rig(null,false);blocking.start(1);
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        blocking.owner.inside=new Runnable(){public void run(){entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("test release timed out");}catch(InterruptedException e){throw new AssertionError(e);}}};
        blocking.decode(1);Thread nativeThread=new Thread(new Runnable(){public void run(){blocking.worker.run();}});
        nativeThread.start();check(entered.await(1,TimeUnit.SECONDS),"native request running on worker");
        blocking.end();blocking.start(1);blocking.worker.now+=10000;blocking.timeout();
        check(blocking.worker.queue.isEmpty(),"blocked native call does not block lifecycle hook or create another request");
        release.countDown();nativeThread.join(2000);check(!nativeThread.isAlive()&&blocking.owner.requests==1,"single in-flight native completes without orphaned worker");
        check(blocking.last().contains("current=0"),"late native result retains old generation");
        Rig display=new Rig(null,false);Surface surface=new Surface();
        display.show(surface,DisplayResult.OK);check(display.worker.queue.isEmpty(),"show without accepted session does nothing");
        display.start(0);display.show(surface,DisplayResult.OK);check(display.worker.queue.isEmpty(),"wired attachment unchanged");
        display.start(1);display.show(surface,DisplayResult.OK);check(display.worker.queue.size()==1,"successful visible wireless attachment queues once");
        Runnable oldSurface=display.worker.queue.get(0);display.hide();check(display.worker.queue.isEmpty(),"hide invalidates pending attachment");
        display.decode(1);display.timeout();check(display.worker.queue.isEmpty(),"known-hidden renderer never requests frames");
        oldSurface.run();check(display.owner.requests==0,"cancelled prior surface request cannot run");
        display.show(surface,DisplayResult.ERROR);display.timeout();check(display.worker.queue.isEmpty(),"rejected show does not claim a visible renderer");
        display.show(null,DisplayResult.OK);display.decode(1);check(display.worker.queue.isEmpty(),"null surface cannot be refreshed");
        Surface invalid=new Surface();invalid.valid=false;display.show(invalid,DisplayResult.OK);check(display.worker.queue.isEmpty(),"invalid surface cannot be refreshed");
        display.show(surface,DisplayResult.OK);display.worker.run();check(display.owner.requests==1,"reattachment refresh executes off callback");
        check(display.mod.logs.get(display.mod.logs.size()-2).contains("reason=3 code=0"),"surface attachment reason is distinct from decoder failure");
        display.hide();display.worker.now+=2000;display.show(surface,DisplayResult.OK);surface.valid=false;display.worker.run();
        check(display.owner.requests==1,"surface released before worker drops request");surface.valid=true;
        display.show(surface,DisplayResult.OK);display.worker.run();check(display.owner.requests==2,"new valid attach can refresh after release");
        display.worker.now+=2000;
        ModContext.dispatch(CarPlayVideoRecovery.DECODE,display.owner,new Object[]{1},new ModContext.Original(){public Object run()throws Throwable{display.hide();return null;}});
        check(display.worker.queue.isEmpty(),"callback finishing after hide cannot queue keyframe");
        final Rig nestedDisplay=new Rig(null,false);nestedDisplay.start(1);final Surface first=new Surface(),second=new Surface();
        ModContext.dispatch(CarPlayVideoRecovery.SHOW,nestedDisplay.owner,new Object[]{first},new ModContext.Original(){public Object run()throws Throwable{nestedDisplay.show(second,DisplayResult.OK);return DisplayResult.OK;}});
        nestedDisplay.worker.run();check(nestedDisplay.owner.requests==1,"older outer show completion does not overwrite newer surface");
        nestedDisplay.worker.now+=2000;nestedDisplay.hide();nestedDisplay.timeout();check(nestedDisplay.worker.queue.isEmpty(),"hide keeps silent lifecycle inactive");
        int beforeIdle=nestedDisplay.owner.requests;nestedDisplay.worker.now+=100000;nestedDisplay.worker.run();
        check(nestedDisplay.owner.requests==beforeIdle,"hidden or static time passage never drives blind periodic request");
        final Rig stoppedDisplay=new Rig(null,false);stoppedDisplay.start(1);
        ModContext.dispatch(CarPlayVideoRecovery.SHOW,stoppedDisplay.owner,new Object[]{new Surface()},new ModContext.Original(){public Object run()throws Throwable{stoppedDisplay.end();stoppedDisplay.start(1);return DisplayResult.OK;}});
        check(stoppedDisplay.worker.queue.isEmpty(),"old show callback cannot refresh replacement session");
        Rig deferred=new Rig(null,false);deferred.start(1);deferred.decode(1);deferred.worker.run();
        deferred.worker.now+=1000;deferred.show(new Surface(),DisplayResult.OK);
        check(deferred.worker.queue.size()==1&&deferred.owner.requests==1,"successful attachment inside cooldown is retained once");
        deferred.worker.run();check(deferred.owner.requests==1,"deferred attachment does not bypass cooldown");
        deferred.worker.now+=999;deferred.worker.run();check(deferred.owner.requests==1,"attachment waits through final cooldown millisecond");
        deferred.worker.now++;deferred.worker.run();check(deferred.owner.requests==2&&deferred.worker.queue.isEmpty(),"attachment refresh runs exactly at remaining cooldown boundary");
        deferred.worker.now++;deferred.show(new Surface(),DisplayResult.OK);Runnable beforeHide=deferred.worker.queue.get(0);deferred.hide();
        deferred.worker.now+=2000;beforeHide.run();deferred.worker.run();check(deferred.owner.requests==2,"hide cancels deferred refresh including stale runnable");
        deferred.show(new Surface(),DisplayResult.OK);deferred.worker.run();deferred.worker.now++;
        deferred.show(new Surface(),DisplayResult.OK);Runnable beforeEnd=deferred.worker.queue.get(0);deferred.end();deferred.start(1);
        deferred.worker.now+=2000;beforeEnd.run();deferred.worker.run();check(deferred.owner.requests==3,"finalize/restart cancels deferred previous generation");
        deferred.decode(1);deferred.worker.run();deferred.worker.now++;
        Surface firstDeferred=new Surface();deferred.show(firstDeferred,DisplayResult.OK);Runnable previousAttach=deferred.worker.queue.get(0);
        Surface latestDeferred=new Surface();for(int i=0;i<1000;i++)deferred.show(latestDeferred,DisplayResult.OK);
        check(deferred.worker.queue.size()==1,"repeated attach replaces pending event with only latest surface epoch");
        firstDeferred.valid=false;previousAttach.run();deferred.worker.now+=1999;deferred.worker.run();
        check(deferred.owner.requests==5&&deferred.worker.queue.isEmpty(),"latest surface alone refreshes after cooldown; old released surface does not block it");
        deferred.worker.now++;deferred.show(new Surface(),DisplayResult.OK);deferred.mod.on=false;deferred.worker.now+=2000;deferred.worker.run();
        check(deferred.owner.requests==5,"disable before deferred execution prevents refresh");deferred.mod.on=true;
        Rig inFlightShow=new Rig(null,false);inFlightShow.start(1);
        inFlightShow.owner.inside=new Runnable(){public void run(){try{inFlightShow.worker.now+=500;inFlightShow.show(new Surface(),DisplayResult.OK);}
            catch(Throwable t){throw new AssertionError(t);}}};
        inFlightShow.decode(1);inFlightShow.worker.run();inFlightShow.owner.inside=null;
        check(inFlightShow.owner.requests==1&&inFlightShow.worker.queue.size()==1,"show observed during native call retains one follow-up");
        inFlightShow.worker.now+=1499;inFlightShow.worker.run();check(inFlightShow.owner.requests==1,"in-flight follow-up honors original attempt cooldown");
        inFlightShow.worker.now++;inFlightShow.worker.run();check(inFlightShow.owner.requests==2&&inFlightShow.worker.queue.isEmpty(),"follow-up runs once after native call and cooldown");
        inFlightShow.worker.now+=100000;inFlightShow.worker.run();check(inFlightShow.owner.requests==2,"deferred work is not a periodic retry loop");
        Rig partial=new Rig(CarPlayVideoRecovery.TIMEOUT,false);partial.start(1);partial.decode(1);
        check(partial.worker.closed&&partial.worker.queue.isEmpty()&&partial.last().endsWith("recovery_unavailable"),"partial hooks stay inactive and worker closes");
        Rig bad=new Rig(null,true);check(bad.recovery==null&&bad.mod.hooks.isEmpty()&&bad.last().endsWith("recovery_unavailable"),"incorrect native return rejected before installing hooks");
        rebindTests();
        check(r.mod.hookErrors.isEmpty()&&failed.mod.hookErrors.isEmpty()&&nested.mod.hookErrors.isEmpty(),"observer hooks preserve original outcomes without hook errors");
        System.out.println("VideoRecoveryTest PASS checks="+checks);
    }
    static void rebindTests()throws Throwable {
        Rig actual=new Rig(null,false);actual.at(41500);actual.foreground(new Surface());
        actual.at(50613);actual.timeout();actual.worker.run();actual.at(50637);actual.stopScreen();
        check(actual.main.queue.size()==1&&actual.owner.detaches==0,"actual timeout then 24ms stop queues one main admission");
        actual.at(50886);actual.main.run();actual.worker.run();check(actual.owner.detaches==0,"native teardown grace includes final millisecond");
        actual.at(50887);actual.main.run();check(actual.owner.detaches==0,"main coordinator never calls JNI");actual.worker.run();
        check(actual.owner.detaches==1&&actual.owner.reattaches==1,"actual first freeze rebinds same foreground display once");
        check(actual.last().contains("hide_result=0 show_result=0 exception=0 current=1"),"literal return codes without claiming rendered-frame success");
        int posts=actual.main.posts;actual.stopScreen();actual.stopScreen();check(actual.main.posts==posts,"duplicate stops without new timeout cannot repeat repair");
        actual.at(91981);actual.timeout();actual.worker.run();actual.at(92007);actual.stopScreen();actual.repair();
        check(actual.owner.detaches==2&&actual.owner.reattaches==2,"actual second freeze beyond cooldown repairs once");
        actual.at(200000);actual.main.run();actual.worker.run();check(actual.owner.detaches==2,"time alone never reattaches display");
        check(actual.mod.hookErrors.isEmpty(),"self hide/show hooks do not recurse or corrupt epoch");

        for(String cancellation:new String[]{"pause","hide","mode","created","changed","destroyed","show","end","start","screen_start","released","disable"}){
            Rig r=new Rig(null,false);Surface surface=new Surface();r.foreground(surface);r.at(5000);r.incident();
            Runnable stale=r.main.queue.get(0);
            if(cancellation.equals("pause"))r.call(CarPlayVideoRecovery.PAUSE,r.activity);
            else if(cancellation.equals("hide"))r.hide();
            else if(cancellation.equals("mode"))r.mode(2);
            else if(cancellation.equals("created"))r.call(CarPlayVideoRecovery.CREATED,r.activity,r.holder);
            else if(cancellation.equals("changed"))r.call(CarPlayVideoRecovery.CHANGED,r.activity,r.holder,1,1920,720);
            else if(cancellation.equals("destroyed"))r.call(CarPlayVideoRecovery.DESTROYED,r.activity,r.holder);
            else if(cancellation.equals("show"))r.show(new Surface(),DisplayResult.OK);
            else if(cancellation.equals("end"))r.end();
            else if(cancellation.equals("start"))r.start(1);
            else if(cancellation.equals("screen_start"))r.startScreen();
            else if(cancellation.equals("released"))surface.valid=false;
            else r.mod.on=false;
            r.at(6000);stale.run();r.main.run();r.worker.run();
            check(r.owner.detaches==0&&r.owner.reattaches==0,"queued and stale repair cancelled by "+cancellation);
        }
        Rig nav=new Rig(null,false);nav.foreground(new Surface());nav.at(5000);nav.timeout();
        nav.call(CarPlayVideoRecovery.PAUSE,nav.activity);nav.hide();nav.stopScreen();nav.repair();
        check(nav.owner.detaches==0,"normal NAV switch and its screen stop never force CarPlay back");
        nav.call(CarPlayVideoRecovery.RESUME,nav.activity);nav.mode(1);nav.show(new Surface(),DisplayResult.OK);nav.at(20000);nav.incident();nav.repair();
        check(nav.owner.detaches==1,"manual return establishes fresh eligible attachment");
        for(String excluded:new String[]{"wired","no_resume","no_owner","accessory","stopped_only","timeout_only","late_stop","decode_stop"}){
            Rig r=new Rig(null,false);Surface surface=new Surface();r.foreground(surface);r.at(5000);
            if(excluded.equals("wired")){r.start(0);r.mode(1);r.show(surface,DisplayResult.OK);}
            if(excluded.equals("no_resume"))r.call(CarPlayVideoRecovery.PAUSE,r.activity);
            if(excluded.equals("no_owner")){r.start(1);r.show(surface,DisplayResult.OK);}
            if(excluded.equals("accessory"))r.mode(2);
            if(excluded.equals("stopped_only"))r.stopScreen();
            else if(excluded.equals("timeout_only"))r.timeout();
            else if(excluded.equals("late_stop")){r.timeout();r.at(7001);r.stopScreen();}
            else if(excluded.equals("decode_stop")){r.decode(1);r.stopScreen();}
            else r.incident();
            r.repair();check(r.owner.detaches==0,"requires exact visible wireless timeout-stop pair: "+excluded);
        }
        Rig cool=new Rig(null,false);cool.foreground(new Surface());cool.at(5000);cool.incident();cool.repair();
        cool.at(6000);for(int i=0;i<1000;i++){cool.timeout();cool.stopScreen();}
        cool.repair();check(cool.owner.detaches==1&&cool.main.queue.isEmpty(),"burst during10second cooldown drops without delayed retries");
        cool.at(15270);cool.incident();cool.repair();check(cool.owner.detaches==2,"later distinct eligible incident allowed after cooldown");

        Rig handoff=new Rig(null,false);handoff.foreground(new Surface());handoff.at(5000);handoff.incident();
        handoff.at(5270);handoff.main.run();handoff.call(CarPlayVideoRecovery.PAUSE,handoff.activity);handoff.worker.run();
        check(handoff.owner.detaches==0,"UI exit after main admission cancels before JNI worker");
        Rig delayed=new Rig(null,false);delayed.foreground(new Surface());delayed.at(5000);delayed.incident();
        delayed.at(5270);delayed.main.run();delayed.at(7021);delayed.worker.run();
        check(delayed.owner.detaches==0,"repair expires when single JNI worker was unavailable for over2seconds");
        Rig during=new Rig(null,false);during.foreground(new Surface());during.at(5000);during.incident();
        during.owner.insideHide=new Runnable(){public void run(){try{during.call(CarPlayVideoRecovery.PAUSE,during.activity);}catch(Throwable t){throw new AssertionError(t);}}};
        during.repair();check(during.owner.detaches==1&&during.owner.reattaches==0,"pause racing admitted hide cancels show and cannot reopen CarPlay");
        Rig began=new Rig(null,false);began.foreground(new Surface());began.at(5000);began.incident();
        began.owner.insideShow=new Runnable(){public void run(){try{began.startScreen();}catch(Throwable t){throw new AssertionError(t);}}};
        began.repair();check(began.owner.reattaches==1&&began.last().contains("current=1"),"synchronous screen-start caused by own show cannot abort same repair");
        final Rig nestedMode=new Rig(null,false);nestedMode.foreground(new Surface());nestedMode.at(5000);
        ModContext.dispatch(CarPlayVideoRecovery.MODE,nestedMode.owner,new Object[]{1,1,2,1,2,0},new ModContext.Original(){public Object run()throws Throwable{nestedMode.mode(2);return null;}});
        nestedMode.incident();nestedMode.repair();check(nestedMode.owner.detaches==0,"outer mode callback cannot overwrite newer nested accessory ownership");
        final Rig nestedResume=new Rig(null,false);nestedResume.foreground(new Surface());nestedResume.at(5000);
        ModContext.dispatch(CarPlayVideoRecovery.RESUME,nestedResume.activity,new Object[]{},new ModContext.Original(){public Object run()throws Throwable{nestedResume.call(CarPlayVideoRecovery.PAUSE,nestedResume.activity);return null;}});
        nestedResume.incident();nestedResume.repair();check(nestedResume.owner.detaches==0,"resume completion cannot undo nested newer pause");
        Rig failedResume=new Rig(null,false);failedResume.foreground(new Surface());failedResume.at(5000);
        try{ModContext.dispatch(CarPlayVideoRecovery.RESUME,failedResume.activity,new Object[]{},new ModContext.Original(){public Object run(){throw new IllegalStateException();}});}catch(IllegalStateException expected){}
        failedResume.incident();failedResume.repair();check(failedResume.owner.detaches==0,"failed activity resume never retains foreground authorization");
        final Rig staleStart=new Rig(null,false);staleStart.foreground(new Surface());staleStart.at(5000);
        ModContext.dispatch(CarPlayVideoRecovery.SCREEN_START,staleStart.owner,new Object[]{},new ModContext.Original(){public Object run()throws Throwable{staleStart.incident();return null;}});
        staleStart.repair();check(staleStart.owner.detaches==1,"older screen-start completion cannot cancel newer timeout-stop incident");
        final Rig staleTimeout=new Rig(null,false);staleTimeout.foreground(new Surface());staleTimeout.at(5000);
        ModContext.dispatch(CarPlayVideoRecovery.TIMEOUT,staleTimeout.owner,new Object[]{},new ModContext.Original(){public Object run()throws Throwable{staleTimeout.startScreen();return null;}});
        staleTimeout.stopScreen();staleTimeout.repair();check(staleTimeout.owner.detaches==0,"timeout superseded by newer screen-start cannot arm stale repair");
        final Rig staleStop=new Rig(null,false);staleStop.foreground(new Surface());staleStop.at(5000);
        ModContext.dispatch(CarPlayVideoRecovery.STOP,staleStop.owner,new Object[]{},new ModContext.Original(){public Object run()throws Throwable{staleStop.timeout();return null;}});
        staleStop.repair();check(staleStop.owner.detaches==0,"stop beginning before newer timeout cannot complete that newer incident");
        for(boolean failHide:new boolean[]{true,false}){
            Rig r=new Rig(null,false);r.foreground(new Surface());r.at(5000);r.incident();
            if(failHide)r.owner.hideResult=DisplayResult.ERROR;else r.owner.showResult=DisplayResult.ERROR;
            r.repair();check(r.owner.detaches==1&&r.owner.reattaches==(failHide?0:1),"failed native wrapper produces no retry loop");
            r.at(20000);r.incident();r.repair();check(r.owner.detaches==1,"failed attachment requires a new valid OEM show before more repair");
        }
        Rig reject=new Rig(null,false);reject.foreground(new Surface());reject.at(5000);reject.main.reject=true;reject.incident();
        reject.main.reject=false;reject.at(6000);reject.incident();reject.worker.reject=true;reject.repair();
        reject.worker.reject=false;reject.at(7000);reject.incident();reject.repair();
        check(reject.owner.detaches==1,"main and worker post failures leave no stuck pending repair");
        for(String line:actual.mod.logs)check(line.matches("[A-Za-z0-9_= -]+")&&line.length()<256,"rebind logs remain numeric bounded metadata");
    }
}
