package hmgapi.mods.wireless.gpm;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import hmgapi.hook.NativeProofFixture;
import java.util.ArrayList;
import java.util.List;

public final class GpmTest {
    static int checks;
    static void check(boolean pass,String why){checks++;if(!pass)throw new AssertionError(why);}
    enum ClusterType{IMAGE,ENUM}
    public static final class Receiver {public static int NATIVE=0,NAVIGATION_FOCUS_STATE_PROJECTED=1;public void focus(int f){}public void end(){}}
    public static final class Status {
        public Receiver receiver;Status(Receiver r){receiver=r;}
        public boolean init(int a,long b,int c,ClusterType type,int d,int e,int f){return true;}
        public void turn(String road,int side,int event,byte[] image,int angle,int number){}
        public void distance(int meters,int seconds,int e3,int flags){}
        public void status(int state){}
    }
    public static final class Services {public Object start(Intent i){return new Object();}}
    static final class Worker implements Gpm.Scheduler {
        final Handler handler=new Handler();final List<Runnable> queue=new ArrayList<Runnable>();boolean closed,reject;
        public Handler handler(){return handler;}public boolean post(Runnable r){if(reject)return false;queue.add(r);return true;}
        public void close(){closed=true;queue.clear();}
        void run(){int guard=1000;while(!queue.isEmpty()){check(--guard>0,"worker bounded progress");queue.remove(0).run();}}
    }
    static final class Rig {
        static final class NativeGate implements Gpm.NativeReady{boolean eligible=true,applied=true;public boolean eligible(){return eligible;}public boolean applied(){return applied;}}
        final NativeGate gate=new NativeGate();
        final ModContext mod=new ModContext();final Worker worker=new Worker();final Receiver receiver=new Receiver();final Status status=new Status(receiver);final Gpm gpm;int originals;
        Rig(String reject,int spec)throws Exception{this(reject,spec,null);}
        Rig(String reject,int spec,Boolean standard)throws Exception{
            Car.cp=false;Car.aa=true;Car.spec=spec;Car.unit=Car.DISTANCE_KM;Cluster.current=new Cluster();Points.methods.clear();Points.fields.clear();
            Points.methods.put(Gpm.INIT,Status.class.getMethod("init",int.class,long.class,int.class,ClusterType.class,int.class,int.class,int.class));
            Points.methods.put(Gpm.TURN,Status.class.getMethod("turn",String.class,int.class,int.class,byte[].class,int.class,int.class));
            Points.methods.put(Gpm.DISTANCE,Status.class.getMethod("distance",int.class,int.class,int.class,int.class));
            Points.methods.put(Gpm.STATUS,Status.class.getMethod("status",int.class));
            Points.methods.put(Gpm.FOCUS,Receiver.class.getMethod("focus",int.class));Points.methods.put(Gpm.END,Receiver.class.getMethod("end"));
            Points.methods.put(Gpm.USB,Services.class.getMethod("start",Intent.class));
            Points.fields.put("gpm.navigation_status.receiver",Status.class.getField("receiver"));
            Points.fields.put("gpm.cluster_type.image",ClusterType.class.getField("IMAGE"));Points.fields.put("gpm.cluster_type.enum",ClusterType.class.getField("ENUM"));
            Points.fields.put("gpm.receiver.focus_native",Receiver.class.getField("NATIVE"));
            for(String old:new String[]{"aa_tbt","aa_standard_turns","aa_ar_close_approach","aa_approach_bar","aa_usb_drives"})mod.settings.put(old,false);
            if(standard!=null)mod.settings.put("ar_view",standard);
            mod.rejectPoint=reject;gpm=Gpm.install(mod,new LoadedApp(),worker,gate);
        }
        Object call(String point,Object owner,Object[] args,final Object result)throws Throwable{return ModContext.dispatch(point,owner,args,new ModContext.Original(){public Object run(){originals++;return result;}});}
        Object[] initArgs(){return new Object[]{1,2L,3,ClusterType.IMAGE,4,5,6};}
        void start()throws Throwable {Object[] args=initArgs();call(Gpm.INIT,status,args,true);check(args[3]==ClusterType.ENUM,"ENUM argument only at accepted installed hook");}
        void state(int value)throws Throwable{call(Gpm.STATUS,status,new Object[]{value},null);}
        void turn(String road)throws Throwable{call(Gpm.TURN,status,new Object[]{road,2,4,new byte[0],90,1},null);}
        void distance(int flags)throws Throwable{call(Gpm.DISTANCE,status,new Object[]{100,30,100000,flags},null);}
        void end()throws Throwable{call(Gpm.END,receiver,new Object[0],null);}
        boolean contains(String s){for(String c:Cluster.current.calls)if(c.contains(s))return true;return false;}
        Gpm.Session session()throws Exception{java.lang.reflect.Field f=Gpm.class.getDeclaredField("current");f.setAccessible(true);return (Gpm.Session)f.get(gpm);}
    }
    public static void main(String[] args)throws Throwable{
        Rig r=new Rig(null,100);check(r.mod.nativeRequests==1,"one native recipe request");
        r.state(1);r.turn("before init");r.worker.run();check(Cluster.current.calls.isEmpty(),"callback before accepted init inert");
        r.start();r.state(2);r.turn("before guidance");r.worker.run();check(Cluster.current.calls.isEmpty(),"inactive route cannot clear stock or write names and arrows");
        r.state(1);r.turn("private road");r.distance(1);check(Cluster.current.calls.isEmpty(),"callback thread never writes cluster");r.worker.run();
        check(r.contains("tbt 67"),"AA right turn rendered through actual hooks");check(r.contains("100.0"),"turn distance rendered");
        r.distance(0x101);r.worker.run();check(r.worker.handler.delayed!=null,"trip uses existing bounded unit timer");
        int before=Cluster.current.calls.size();r.call(Gpm.FOCUS,r.receiver,new Object[]{0},null);r.worker.run();
        check(Cluster.current.calls.size()==before&&r.worker.handler.delayed==null,"native focus cancels writes/timer without blanking new owner's display");
        r.turn("late name");r.distance(1);r.worker.run();check(Cluster.current.calls.size()==before,"no late output after yielding native focus");
        r.state(1);r.turn("still native");r.worker.run();check(Cluster.current.calls.size()==before,"active status alone cannot reclaim native focus");
        r.call(Gpm.FOCUS,r.receiver,new Object[]{1},null);r.state(1);r.turn("new route");r.worker.run();check(Cluster.current.calls.size()>before,"projected focus plus active status permits route output");
        r.end();r.turn("destroyed route");r.worker.run();check(r.contains("tbt 0"),"disconnect clears owned guidance");
        before=Cluster.current.calls.size();r.state(1);r.turn("late destroyed callback");r.worker.run();check(Cluster.current.calls.size()==before,"destroyed session never resurrects via late status");
        r.start();r.state(1);r.turn("other phone active");Car.cp=true;r.worker.run();check(Cluster.current.calls.size()==before,"CP-owned display not cleared or replaced by AA");Car.cp=false;
        r.state(1);r.distance(0x101);r.worker.run();Runnable unitTick=r.worker.handler.delayed;r.mod.on=false;unitTick.run();check(r.worker.handler.delayed==null,"disable causes unit timer to retire quietly");r.mod.on=true;
        before=Cluster.current.calls.size();for(int i=0;i<1000;i++)r.turn("burst");check(r.worker.queue.size()==1,"burst bounded to one drain task");r.worker.run();
        check(r.mod.logs.toString().contains("queue_reset dropped="),"overflow reported numerically and stale queue reset");
        r.state(1);r.turn(new String(new char[65537]));r.worker.run();check(r.mod.logs.toString().contains("queue_reset"),"oversized payload excluded");
        for(String line:r.mod.logs)check(!line.contains("private road")&&!line.contains("other phone")&&line.length()<180,"AA telemetry omits navigation contents");
        final Rig nested=new Rig(null,100);nested.start();nested.state(1);nested.worker.run();
        ModContext.dispatch(Gpm.TURN,nested.status,new Object[]{"stale",2,4,new byte[0],90,1},new ModContext.Original(){public Object run()throws Throwable{nested.end();nested.start();return null;}});
        nested.worker.run();check(!nested.contains("stale"),"nested replacement rejects old callback completion");
        Rig focusEnd=new Rig(null,100);focusEnd.start();focusEnd.state(1);focusEnd.turn("owned arrow");focusEnd.distance(1);focusEnd.worker.run();
        before=Cluster.current.calls.size();focusEnd.call(Gpm.FOCUS,focusEnd.receiver,new Object[]{0},null);focusEnd.end();focusEnd.worker.run();
        check(Cluster.current.calls.size()==before,"native focus then destroy before drain never clears native owner");
        Rig endFocus=new Rig(null,100);endFocus.start();endFocus.state(1);endFocus.turn("owned arrow");endFocus.worker.run();
        before=Cluster.current.calls.size();endFocus.end();endFocus.call(Gpm.FOCUS,endFocus.receiver,new Object[]{0},null);endFocus.worker.run();
        check(Cluster.current.calls.size()==before,"native focus after destroy also prevents queued cleanup over native owner");
        Rig beforeInit=new Rig(null,100);
        beforeInit.call(Gpm.FOCUS,beforeInit.receiver,new Object[]{0},null);
        beforeInit.call(Gpm.FOCUS,beforeInit.receiver,new Object[]{1},null);
        beforeInit.start();beforeInit.state(1);beforeInit.turn("focus before init");beforeInit.worker.run();
        check(beforeInit.contains("focus before init"),"successful projected focus before init clears global native latch");
        final Rig failedFocus=new Rig(null,100);
        failedFocus.call(Gpm.FOCUS,failedFocus.receiver,new Object[]{0},null);
        try{ModContext.dispatch(Gpm.FOCUS,failedFocus.receiver,new Object[]{1},new ModContext.Original(){public Object run(){throw new IllegalStateException();}});}catch(IllegalStateException expected){}
        failedFocus.start();failedFocus.state(1);failedFocus.turn("failed projected");failedFocus.worker.run();
        check(Cluster.current.calls.isEmpty(),"failed projected focus preserves native ownership before init");
        Rig otherNative=new Rig(null,100);otherNative.start();otherNative.state(1);otherNative.turn("old route");otherNative.worker.run();
        before=Cluster.current.calls.size();otherNative.end();Receiver newer=new Receiver();
        otherNative.call(Gpm.FOCUS,newer,new Object[]{0},null);otherNative.worker.run();
        check(Cluster.current.calls.size()==before,"native focus on a different receiver blocks old retired cleanup");
        Rig otherProjection=new Rig(null,100);otherProjection.start();otherProjection.state(1);otherProjection.turn("old route");otherProjection.worker.run();
        before=Cluster.current.calls.size();otherProjection.end();Receiver newerProjected=new Receiver();
        otherProjection.call(Gpm.FOCUS,newerProjected,new Object[]{0},null);otherProjection.call(Gpm.FOCUS,newerProjected,new Object[]{1},null);otherProjection.worker.run();
        check(Cluster.current.calls.size()==before,"different projected owner also blocks old retired cleanup");
        Rig liveOther=new Rig(null,100);liveOther.start();liveOther.state(1);liveOther.turn("old live");liveOther.distance(1);liveOther.worker.run();
        before=Cluster.current.calls.size();Receiver outside=new Receiver();
        liveOther.call(Gpm.FOCUS,outside,new Object[]{0},null);liveOther.call(Gpm.FOCUS,outside,new Object[]{1},null);
        liveOther.state(1);liveOther.turn("stale live");liveOther.worker.run();
        check(Cluster.current.calls.size()==before,"different receiver focus cannot reactivate previous AA session");
        liveOther.call(Gpm.FOCUS,liveOther.receiver,new Object[]{1},null);liveOther.state(1);liveOther.turn("same receiver returns");liveOther.worker.run();
        check(liveOther.contains("same receiver returns"),"matching projected callback can recover the retained session");
        boolean reused=false;for(int i=before;i<Cluster.current.calls.size();i++){String line=Cluster.current.calls.get(i);reused|=line.startsWith("tbt ")&&line.contains(" 100.0 ");}
        check(!reused,"native ownership by another receiver invalidates previous distance cache");
        Rig lostOwner=new Rig(null,100);lostOwner.start();lostOwner.state(1);lostOwner.turn("owner alive");lostOwner.distance(0x101);lostOwner.worker.run();
        before=Cluster.current.calls.size();Runnable lostTick=lostOwner.worker.handler.delayed;lostOwner.session().owner.clear();lostTick.run();
        check(Cluster.current.calls.size()==before&&lostOwner.worker.handler.delayed==null,"collected receiver stops and forgets unit timer without another cluster write");
        Rig lostRetired=new Rig(null,100);lostRetired.start();lostRetired.state(1);lostRetired.turn("retired owner");lostRetired.worker.run();
        before=Cluster.current.calls.size();Gpm.Session retiredOwner=lostRetired.session();lostRetired.end();retiredOwner.owner.clear();lostRetired.worker.run();
        check(Cluster.current.calls.size()==before,"collected retired receiver cannot clear a later owner's display");
        final Rig outputRace=new Rig(null,100);outputRace.start();outputRace.state(1);outputRace.worker.run();
        Cluster.current.calls.clear();Cluster.current.afterCall=new Runnable(){public void run(){Cluster.current.afterCall=null;try{outputRace.call(Gpm.FOCUS,outputRace.receiver,new Object[]{0},null);}catch(Throwable t){throw new AssertionError(t);}}};
        outputRace.turn("already begun name");outputRace.worker.run();
        check(Cluster.current.calls.size()==1&&Cluster.current.calls.get(0).equals("road [already begun name]"),"focus arriving during one output stops every later output without locking Binder");
        final Rig sync=new Rig(null,100);
        ModContext.dispatch(Gpm.INIT,sync.status,sync.initArgs(),new ModContext.Original(){public Object run()throws Throwable{sync.state(1);sync.turn("sync in init");sync.worker.run();check(Cluster.current.calls.isEmpty(),"unaccepted init callbacks buffered without output");return true;}});
        sync.worker.run();check(sync.contains("sync in init"),"synchronous init callbacks delivered after successful acceptance");
        Rig noPatch=new Rig(null,100);noPatch.gate.applied=false;Object[] originalType=noPatch.initArgs();noPatch.call(Gpm.INIT,noPatch.status,originalType,true);noPatch.state(1);noPatch.worker.run();
        check(originalType[3]==ClusterType.IMAGE&&Cluster.current.calls.isEmpty(),"unapplied/conflicted native recipe preserves original IMAGE and guidance");
        noPatch.gate.applied=true;noPatch.start();noPatch.state(1);noPatch.turn("patch applied");noPatch.worker.run();check(noPatch.contains("patch applied"),"later confirmed recipe supports a new init");
        final Rig nestedInit=new Rig(null,100);Object[] ia=nestedInit.initArgs();
        ModContext.dispatch(Gpm.INIT,nestedInit.status,ia,new ModContext.Original(){public Object run()throws Throwable{nestedInit.end();return true;}});
        nestedInit.state(1);nestedInit.turn("nested ended");nestedInit.worker.run();check(Cluster.current.calls.isEmpty(),"finalize within init does not revive session");
        Rig failed=new Rig(null,100);failed.call(Gpm.INIT,failed.status,failed.initArgs(),false);failed.state(1);failed.worker.run();check(Cluster.current.calls.isEmpty(),"false init not accepted");
        final RuntimeException failure=new RuntimeException("original failure");
        try{ModContext.dispatch(Gpm.INIT,failed.status,failed.initArgs(),new ModContext.Original(){public Object run(){throw failure;}});throw new AssertionError();}catch(RuntimeException e){check(e==failure,"original init exception retained");}
        Rig partial=new Rig(Gpm.DISTANCE,100);Object[] pa=partial.initArgs();partial.call(Gpm.INIT,partial.status,pa,true);partial.state(1);partial.worker.run();
        check(pa[3]==ClusterType.IMAGE&&partial.worker.closed&&Cluster.current.calls.isEmpty(),"partial installation leaves original cluster type and closes worker");
        Rig stock=new Rig(null,999);check(stock.gpm==null&&!stock.mod.hooks.containsKey(Gpm.INIT)&&stock.mod.hooks.containsKey(Gpm.USB),"stock TBT unaffected while USB guard remains independent");
        check(!Gpm.notPhone(null)&&!Gpm.notPhone(new UsbDevice())&&!Gpm.notPhone(new UsbDevice(8,255)),"null zero-interface compositephone unchanged");
        check(Gpm.notPhone(new UsbDevice(8))&&Gpm.notPhone(new UsbDevice(9,8))&&!Gpm.notPhone(new UsbDevice(255)),"only all-storage/hub interfaces match");
        Intent intent=new Intent();intent.component=new ComponentName("com.google.android.projection.service.GalReceiverService");intent.mode=1;intent.device=new UsbDevice(8);
        Object sentinel=new Object();int called=stock.originals;
        check(stock.call(Gpm.USB,new Services(),new Object[]{intent},sentinel)==null&&stock.originals==called,"massstorage wired-AA start alone suppressed");
        intent.mode=2;check(stock.call(Gpm.USB,new Services(),new Object[]{intent},sentinel)==sentinel,"wireless service start preserved");
        intent.mode=1;intent.device=new UsbDevice(8,255);check(stock.call(Gpm.USB,new Services(),new Object[]{intent},sentinel)==sentinel,"compositephone service start preserved");
        intent.device=new UsbDevice(8);intent.component=new ComponentName("other.Service");check(stock.call(Gpm.USB,new Services(),new Object[]{intent},sentinel)==sentinel,"unrelated service preserved");
        check(r.mod.hookErrors.isEmpty()&&nested.mod.hookErrors.isEmpty()&&partial.mod.hookErrors.isEmpty(),"no hook exception changes OEM result");
        NativeProofFixture.reset();NativeRecipeProof proof=new NativeRecipeProof(stock.mod);
        check(proof.eligible()&&!proof.applied(),"correct full-file hash alone is not patch application proof");
        NativeProofFixture.add("other-mod",NativeRecipeProof.RECIPE);check(!proof.applied(),"other mod ownership not accepted");
        NativeProofFixture.add(stock.mod.id,"other-recipe");check(!proof.applied(),"different native recipe not accepted");
        NativeProofFixture.add(stock.mod.id,"aa-tbt-48ee793a");check(!proof.applied(),"old mask protocol recipe cannot authorize new decoder");
        NativeProofFixture.add(stock.mod.id,NativeRecipeProof.RECIPE);check(proof.applied(),"exact mod and recipe must appear in successful native ownership record");
        ModContext.digest="wrong";check(!proof.eligible(),"full-file mismatch is ineligible before hooking");ModContext.digest=NativeRecipeProof.MD5;
        Cluster cacheCluster=new Cluster();AaTurns cache=new AaTurns(new android.content.Context(),cacheCluster,new Handler());
        String lanes="0201aabbcc02000000";cache.status(1);cache.nextTurn("same",2,4,90,0);cache.nextTurnDistance(100,20,100000,1);cache.nextTurn(lanes,0,34,0,0);
        cacheCluster.calls.clear();cache.forget();check(cacheCluster.calls.isEmpty(),"forget does not clear new owner's screen");
        cache.status(1);cache.nextTurn("same",2,4,90,0);cache.nextTurn(lanes,0,34,0,0);
        boolean hud=false,lane=false,oldDistance=false;for(String line:cacheCluster.calls){hud|=line.startsWith("hud f201");lane|=line.startsWith("lanes f102");oldDistance|=line.startsWith("tbt ")&&line.contains(" 100.0 ");}
        check(hud&&lane&&!oldDistance,"same HUD and lanes resend after ownership reset without reusing old distance");
        cache.nextTurnDistance(100,20,100000,1);cache.status(2);cacheCluster.calls.clear();cache.status(1);cache.nextTurn("new route",2,4,90,0);
        oldDistance=false;for(String line:cacheCluster.calls)oldDistance|=line.startsWith("tbt ")&&line.contains(" 100.0 ");
        check(!oldDistance,"route stop/start cannot reuse previous turn distance");
        System.out.println("GpmTest PASS checks="+checks);
    }
}
