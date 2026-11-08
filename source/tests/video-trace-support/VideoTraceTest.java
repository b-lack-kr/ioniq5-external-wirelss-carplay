package hmgapi.mods.wireless.dipo;

import android.view.Surface;
import android.view.SurfaceHolder;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.util.ArrayList;
import java.util.List;

/** Actual observer hooks and their window state; no Android or vehicle execution. */
public final class VideoTraceTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    enum Return {OK,ERROR}
    static final class Native {
        public void start(int type){} public void end(int type){}
        public void empty(){} public void decode(int code){}
        public void mode(int a,int b,int c,int d,int e,int f){}
        public Return show(Surface surface){return Return.OK;}
        public Return hide(){return Return.OK;}
        public void iap(byte[] bytes,int length){}
    }
    static final class Activity {public void resume(){} public void pause(){}}
    static final class Callback {
        public void created(SurfaceHolder holder){}
        public void changed(SurfaceHolder holder,int format,int width,int height){}
        public void destroyed(SurfaceHolder holder){}
    }
    static final class Holder implements SurfaceHolder {
        final Surface surface=new Surface();public Surface getSurface(){return surface;}
        @Override public String toString(){throw new AssertionError("holder must not be formatted");}
    }
    static final class Clock implements CarPlayVideoTrace.Scheduler {
        long time=100;boolean closed;final List<Runnable> pending=new ArrayList<Runnable>();
        public long now(){return time;}
        public void later(Runnable r,long delay){check(delay==10000,"10-second cadence");check(!pending.contains(r),"only one pending heartbeat");pending.add(r);}
        public void cancel(Runnable r){pending.remove(r);}
        public void close(){closed=true;pending.clear();}
        void tick(){time+=10000;List<Runnable> work=new ArrayList<Runnable>(pending);pending.clear();for(Runnable r:work)r.run();}
    }
    static final class MainQueue implements CarPlayVideoTrace.Scheduler {
        final Clock clock;final List<Runnable> pending=new ArrayList<Runnable>();boolean closed,failPost;
        MainQueue(Clock clock){this.clock=clock;}
        public long now(){return clock.now();}
        public void later(Runnable r,long delay){check(delay==0,"main probe is queued without delay");if(failPost)throw new IllegalStateException();check(pending.size()<1,"at most one main probe");pending.add(r);}
        public void cancel(Runnable r){pending.remove(r);}
        public void close(){closed=true;pending.clear();}
        void drain(){List<Runnable> copy=new ArrayList<Runnable>(pending);pending.clear();for(Runnable r:copy)r.run();}
    }
    static final class Rig {
        final ModContext mod=new ModContext();final Clock clock=new Clock();final MainQueue main=new MainQueue(clock);final Native nativeOwner=new Native();
        final Activity activity=new Activity();final Callback surface=new Callback();final CarPlayVideoTrace trace;
        int originals;
        Rig(String reject)throws Exception {
            Points.methods.clear();
            Points.methods.put("dipo.carplay.session_started",Native.class.getMethod("start",int.class));
            Points.methods.put("dipo.carplay.session_finalized",Native.class.getMethod("end",int.class));
            for(int i=0;i<3;i++)Points.methods.put(CarPlayVideoTrace.POINTS[i],Native.class.getMethod("empty"));
            Points.methods.put(CarPlayVideoTrace.POINTS[3],Native.class.getMethod("decode",int.class));
            Points.methods.put(CarPlayVideoTrace.POINTS[4],Native.class.getMethod("mode",int.class,int.class,int.class,int.class,int.class,int.class));
            Points.methods.put(CarPlayVideoTrace.POINTS[5],Native.class.getMethod("show",Surface.class));
            Points.methods.put(CarPlayVideoTrace.POINTS[6],Native.class.getMethod("hide"));
            Points.methods.put(CarPlayVideoTrace.POINTS[7],Activity.class.getMethod("resume"));
            Points.methods.put(CarPlayVideoTrace.POINTS[8],Activity.class.getMethod("pause"));
            Points.methods.put(CarPlayVideoTrace.POINTS[9],Callback.class.getMethod("created",SurfaceHolder.class));
            Points.methods.put(CarPlayVideoTrace.POINTS[10],Callback.class.getMethod("changed",SurfaceHolder.class,int.class,int.class,int.class));
            Points.methods.put(CarPlayVideoTrace.POINTS[11],Callback.class.getMethod("destroyed",SurfaceHolder.class));
            Points.methods.put(CarPlayVideoTrace.POINTS[12],Native.class.getMethod("iap",byte[].class,int.class));
            mod.rejectPoint=reject;trace=CarPlayVideoTrace.install(mod,new LoadedApp(),clock,main);
        }
        Object call(String point,Object target,Object[] args,final Object result)throws Throwable {
            return ModContext.dispatch(point,target,args,new ModContext.Original(){public Object run(){originals++;return result;}});
        }
        void start()throws Throwable {call("dipo.carplay.session_started",nativeOwner,new Object[]{2},null);}
        void end()throws Throwable {call("dipo.carplay.session_finalized",nativeOwner,new Object[]{2},null);}
        void event(int kind,Object target,Object[] args,Object result)throws Throwable {call(CarPlayVideoTrace.POINTS[kind],target,args,result);}
        String last(){return mod.logs.get(mod.logs.size()-1);}
        String lastWindow(){for(int i=mod.logs.size()-1;i>=0;i--)if(mod.logs.get(i).startsWith("USB_CP_VIDEO window"))return mod.logs.get(i);throw new AssertionError("no window");}
    }
    public static void main(String[] args)throws Throwable {
        Rig r=new Rig(null);check(r.last().contains("no_frame_counter=1"),"does not claim frame measurement");r.start();
        check(r.originals==1&&r.clock.pending.size()==1,"start original and heartbeat");
        r.event(0,r.nativeOwner,new Object[0],null);r.event(3,r.nativeOwner,new Object[]{1},null);
        r.event(4,r.nativeOwner,new Object[]{1,2,0,1,2,3},null);
        check(r.last().contains("screen_owner=1 audio_owner=2 phone_owner=0 tbt_owner=1 speech_owner=2 speech_mode=3"),"all numeric ownership fields");
        Object returned=r.call(CarPlayVideoTrace.POINTS[5],r.nativeOwner,new Object[]{null},Return.OK);
        check(returned==Return.OK&&r.last().contains("code=0 surface_null=1 surface_valid=-1"),"null surface safely observed and original result retained");
        Surface invalid=new Surface();invalid.valid=false;r.event(5,r.nativeOwner,new Object[]{invalid},Return.ERROR);
        check(r.last().contains("code=1 surface_null=0 surface_valid=0"),"invalid surface result");
        Holder h=new Holder();r.event(9,r.surface,new Object[]{h},null);
        r.event(10,r.surface,new Object[]{h,7,1920,720},null);
        r.event(11,r.surface,new Object[]{null},null);r.clock.tick();
        check(r.lastWindow().contains("surface_valid=1")&&r.lastWindow().contains("width=1920 height=720 format=7"),"null destroy does not clear a valid holder");
        Holder next=new Holder();r.event(9,r.surface,new Object[]{next},null);r.event(11,r.surface,new Object[]{h},null);
        r.event(7,r.activity,new Object[0],null);r.event(8,new Activity(),new Object[0],null);r.clock.tick();
        check(r.lastWindow().contains("resumed=1 surface_valid=1"),"stale activity pause and holder destroy do not replace current state");
        r.event(11,r.surface,new Object[]{next},null);r.event(8,r.activity,new Object[0],null);r.clock.tick();
        check(r.lastWindow().contains("resumed=0 surface_valid=0"),"matching lifecycle updates state");

        int before=r.mod.logs.size();for(int i=0;i<1000;i++)r.event(3,r.nativeOwner,new Object[]{3},null);
        check(r.mod.logs.size()-before==12,"burst emits only 12 details per window");r.clock.tick();
        check(r.lastWindow().contains("decode_failure=1000")&&r.lastWindow().contains("detail_suppressed=988"),"all burst counts retained in summary");
        before=r.mod.logs.size();r.clock.tick();check(r.mod.logs.size()==before+1&&r.lastWindow().contains("decode_failure=0"),"active zero-event heartbeat");
        for(String line:r.mod.logs)check(line.matches("[A-Za-z0-9_= .:-]+")&&line.length()<1800,"bounded scalar-only grammar");

        before=r.mod.logs.size();r.event(0,new Native(),new Object[0],null);check(r.mod.logs.size()==before,"foreign native session ignored");
        Runnable stale=r.clock.pending.get(0);
        r.end();check(r.clock.pending.isEmpty()&&r.main.pending.isEmpty(),"teardown cancels heartbeat and main probe");before=r.mod.logs.size();
        r.event(0,r.nativeOwner,new Object[0],null);r.event(9,r.surface,new Object[]{h},null);r.clock.tick();stale.run();
        check(r.mod.logs.size()==before,"no late logs or timer resurrection after teardown");
        r.start();before=r.mod.logs.size();stale.run();
        check(r.mod.logs.size()==before&&r.clock.pending.size()==1,"old-generation heartbeat cannot run against new session");

        Rig nested=new Rig(null);nested.start();before=nested.mod.logs.size();
        ModContext.dispatch(CarPlayVideoTrace.POINTS[5],nested.nativeOwner,new Object[]{new Surface()},new ModContext.Original(){
            public Object run()throws Throwable {nested.end();return Return.OK;}
        });
        check(nested.mod.logs.size()==before+2,"nested finalization flushes and ends without stale show completion");
        check(nested.clock.pending.isEmpty(),"nested completion cannot reschedule");
        Rig nestedStart=new Rig(null);
        ModContext.dispatch("dipo.carplay.session_started",nestedStart.nativeOwner,new Object[]{2},new ModContext.Original(){
            public Object run()throws Throwable {nestedStart.end();return null;}
        });check(nestedStart.clock.pending.isEmpty(),"finalize within start cannot resurrect session");

        Rig failed=new Rig(null);failed.start();final IllegalStateException error=new IllegalStateException("PRIVATE exception must not appear");
        try {ModContext.dispatch(CarPlayVideoTrace.POINTS[5],failed.nativeOwner,new Object[]{new Surface()},new ModContext.Original(){public Object run(){throw error;}});throw new AssertionError("exception swallowed");}
        catch(IllegalStateException actual){check(actual==error,"original exception preserved");}
        check(failed.last().contains("exception=1")&&!failed.last().contains("PRIVATE"),"only exception flag recorded");
        failed.mod.on=false;failed.clock.tick();check(failed.clock.pending.isEmpty(),"disable stops aggregation");
        failed.mod.on=true;failed.start();check(failed.clock.pending.size()==1,"new session resumes diagnostics");
        Rig failedStart=new Rig(null);
        try {ModContext.dispatch("dipo.carplay.session_started",failedStart.nativeOwner,new Object[]{2},new ModContext.Original(){public Object run(){throw error;}});throw new AssertionError("start exception swallowed");}
        catch(IllegalStateException actual){check(actual==error,"failed start retains original exception");}
        check(failedStart.clock.pending.isEmpty()&&failedStart.lastWindow().contains("failure=1"),"failed session start flushes and cancels its heartbeat");
        Rig partial=new Rig(CarPlayVideoTrace.POINTS[5]);check(partial.clock.closed&&partial.last().endsWith("observers_unavailable"),"partial installation closes worker");
        before=partial.mod.logs.size();partial.start();partial.event(0,partial.nativeOwner,new Object[0],null);
        check(partial.clock.pending.isEmpty()&&partial.mod.logs.size()==before,"partial hooks remain inert");
        check(r.mod.hookErrors.isEmpty()&&nested.mod.hookErrors.isEmpty()&&failed.mod.hookErrors.isEmpty(),"no observer exception reaches dispatcher");

        Rig health=new Rig(null);health.start();check(health.main.pending.size()==1,"session queues one main probe");
        health.clock.tick();health.clock.tick();
        check(health.main.pending.size()==1&&health.lastWindow().contains("main_pending_age_ms=20000"),"stalled main cannot grow its queue");
        health.clock.time+=150;health.main.drain();health.clock.tick();
        check(health.lastWindow().contains("main_lag_max_ms=20150")&&health.lastWindow().contains("worker_late_ms=150"),"main round trip and worker lateness measured separately");
        health.main.drain();Surface shown=new Surface();health.event(5,health.nativeOwner,new Object[]{shown},Return.OK);
        shown.valid=false;health.clock.tick();
        check(health.lastWindow().contains("surface_valid=1")&&health.lastWindow().contains("surface_valid_now=0"),"fresh validity distinguished from last callback state");
        byte[] control=new byte[]{41,42,43};before=health.mod.logs.size();
        health.event(12,health.nativeOwner,new Object[]{control,3},null);
        health.event(12,health.nativeOwner,new Object[]{control,7},null);
        health.event(12,new Native(),new Object[]{control,3},null);
        check(health.mod.logs.size()==before&&control[0]==41,"control observer adds no per-packet log and preserves payload");
        health.clock.tick();check(health.lastWindow().contains("iap_control=2")&&health.lastWindow().contains("control_bytes=3"),"only validated control byte lengths counted, not foreign session");
        health.event(3,health.nativeOwner,new Object[]{2},null);health.clock.tick();health.clock.tick();
        check(health.lastWindow().contains("decode_failure=0")&&health.lastWindow().contains("decode_age_ms=20000"),"old error carries its age instead of implying new failure");
        int oldCount=health.originals;
        ModContext.dispatch(CarPlayVideoTrace.POINTS[5],health.nativeOwner,new Object[]{shown},new ModContext.Original(){
            public Object run(){health.clock.time+=3200;health.clock.tick();
                check(health.lastWindow().contains("inflight_tracked=1")&&health.lastWindow().contains("inflight_oldest_ms=13200")&&health.lastWindow().contains("inflight_kind_mask=32"),"callback blocked in OEM path visible to worker");return Return.OK;}
        });health.clock.tick();
        check(health.lastWindow().contains("hook_path_max_ms=13200 hook_path_kind=5")&&health.lastWindow().contains("inflight_tracked=0"),"completion records duration and releases tracking");
        check(health.originals==oldCount,"diagnostic tick never invokes a display/recovery operation");
        nestedCalls(health,40);health.clock.tick();
        check(health.lastWindow().contains("inflight_tracked=0"),"bounded nested tracking released");
        Runnable oldPing=health.main.pending.get(0);health.end();health.start();
        oldPing.run();health.clock.tick();
        check(health.lastWindow().contains("main_ack_age_ms=-1"),"stale generation main ack cannot mark new session responsive");
        health.main.drain();health.main.failPost=true;health.clock.tick();health.clock.tick();
        check(health.lastWindow().contains("main_post_failed=1")&&health.lastWindow().contains("main_pending_age_ms=-1"),"failed main post is reported without stranded pending flag");
        check(health.mod.hookErrors.isEmpty(),"diagnostic edge cases do not alter dispatcher errors");
        System.out.println("VideoTraceTest PASS checks="+checks);
    }
    static void nestedCalls(final Rig rig,final int depth)throws Throwable {
        ModContext.dispatch(CarPlayVideoTrace.POINTS[12],rig.nativeOwner,new Object[]{new byte[0],0},new ModContext.Original(){
            public Object run()throws Throwable {if(depth>0)nestedCalls(rig,depth-1);else{
                rig.clock.tick();check(rig.lastWindow().contains("inflight_tracked=32")&&rig.lastWindow().contains("inflight_overflow=9"),"in-flight tracking capped even under nested callbacks");
            }return null;}
        });
    }
}
