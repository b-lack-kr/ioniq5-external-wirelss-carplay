package kr.ioniq5.usbcp;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.io.*;
import java.nio.file.Files;

/** Actual hook wrappers -> pending event -> wire decoder -> durable root consumer. */
public final class PowerLifecycleTest {
    static final String BOOT="12345678-1234-1234-1234-123456789abc";
    static final String NEXT="22345678-1234-1234-1234-123456789abc";
    static final long N=1234567890123456L;
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static final class Listener {
        int off,on;Runnable insideOff;boolean throwOff;
        public void onPowerOff(){off++;if(insideOff!=null)insideOff.run();if(throwOff)throw new IllegalStateException("OEM error");}
        public void onPowerOn(){on++;}
    }
    static final class Rig {
        final ModContext mod=new ModContext();final Listener listener=new Listener();
        final PowerQueue queue=new PowerQueue();final long[] clock={N};
        final File file;final ApState ap=new ApState("test",0);
        final PowerControl root;
        Rig(File dir)throws Exception {
            file=new File(dir,"power-event");
            Points.methods.clear();
            Points.methods.put("dipo.mode_power_listener.power_off",Listener.class.getMethod("onPowerOff"));
            Points.methods.put("dipo.mode_power_listener.power_on",Listener.class.getMethod("onPowerOn"));
            OemPower.install(mod,new LoadedApp(),new OemPower.Sink(){public void offer(boolean off){queue.offer(off,++clock[0]);}});
            root=new PowerControl(new PowerLatch(file,BOOT,N+1000),ap,1);
        }
        void callback(final boolean off)throws Throwable {
            ModContext.dispatch("dipo.mode_power_listener.power_"+(off?"off":"on"),listener,new Object[0],new ModContext.Original(){
                public Object run(){if(off)listener.onPowerOff();else listener.onPowerOn();return null;}
            });
        }
        int deliver()throws Exception {
            PowerQueue.Pending pending=queue.current();check(pending!=null,"event queued");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            new PowerLatch.Event(BOOT,pending.nanos,pending.off).write(new DataOutputStream(bytes));
            check(bytes.size()==45,"bounded wire payload");
            PowerLatch.Event read=PowerLatch.Event.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            int result=root.accept(read,N+1000,2);queue.delivered(pending);return result;
        }
    }
    static File dir(File root,String name){File d=new File(root,name);d.mkdirs();return d;}
    static Snapshot ready(long now){return new Snapshot("live",now,true,"rtwlan0",35,"192.168.43.1","02:11:22:33:44:55","Hyundai_123456","synthetic-test-key",5180);}
    static void animationLifecycle(File work)throws Exception {
        PowerQueue animation=new PowerQueue(PowerLatch.WELCOME_COMMAND);
        PowerQueue dipo=new PowerQueue();
        animation.offer(true,N+100);PowerQueue.Pending goodbye=animation.current();
        check(goodbye.command==2&&goodbye.off,"Goodbye queue pins the dedicated animation command");
        dipo.offer(false,N+50);check(dipo.current().command==1,"DiPO queue retains its own command");
        File eventFile=new File(dir(work,"animation"),"power-event");
        PowerLatch latch=new PowerLatch(eventFile,BOOT,N+1000);
        ApState ap=new ApState("animation",0);PowerControl root=new PowerControl(latch,ap,0);
        ap.publish(ap.revision(),ready(1));long oldObservation=ap.revision();
        check(root.accept(new PowerLatch.Event(BOOT,goodbye.nanos,goodbye.off),N+1000,2)==0,"Goodbye uses the existing durable OFF consumer");
        ap.publish(oldObservation,ready(2));check(!ap.current(2).ready&&!ap.demanded(),"Goodbye fences in-flight readiness");
        check(root.accept(new PowerLatch.Event(BOOT,dipo.current().nanos,false),N+1000,3)==2&&!ap.demanded(),"late stale DiPO ON cannot undo Goodbye");
        PowerLatch restored=new PowerLatch(eventFile,BOOT,N+1000);
        ApState restarted=new ApState("restarted",3);PowerControl afterRestart=new PowerControl(restored,restarted,3);
        check(!restarted.demanded(),"Goodbye remains latched across same-boot provider restart");
        animation.offer(false,N+200);animation.delivered(goodbye);
        check(animation.current()!=null&&!animation.current().off&&animation.current().command==2,"Welcome replaces pending Goodbye and survives late acknowledgement");
        PowerQueue.Pending welcome=animation.current();
        check(afterRestart.accept(new PowerLatch.Event(BOOT,welcome.nanos,false),N+1000,4)==0&&restarted.demanded(),"explicit Welcome restores Provider demand as well as the AP file");
        check(!restarted.current(4).ready,"Welcome does not fabricate AP readiness");
        restarted.publish(restarted.revision(),ready(5));check(restarted.current(5).ready,"fresh observation after Welcome restores readiness");
        check(afterRestart.accept(new PowerLatch.Event(BOOT,N+300,true),N+1000,6)==0,"later normal OFF retained");
        check(afterRestart.accept(new PowerLatch.Event(BOOT,N+400,false),N+1000,7)==0&&restarted.demanded(),"later explicit DiPO ON remains a valid wake fallback");
        try{new PowerQueue(0);throw new AssertionError("snapshot command accepted by queue");}catch(IllegalArgumentException expected){checks++;}
        try{new PowerQueue(3);throw new AssertionError("unknown command accepted by queue");}catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args)throws Throwable {
        File work=new File(args[0]);
        Rig r=new Rig(dir(work,"lifecycle"));
        r.ap.publish(r.ap.revision(),ready(1));check(r.ap.current(1).ready,"AP initially ready");
        long before=r.ap.revision();r.callback(true);
        check(r.listener.off==1,"OEM off runs");check(!r.file.exists(),"OEM callback performs no disk/IPC");
        check(r.deliver()==PowerLatch.ACCEPTED,"OFF accepted");check(!r.ap.current(2).ready&&!r.ap.demanded(),"OFF fences provider");
        r.ap.publish(before,ready(2));check(!r.ap.current(2).ready,"pre-OFF observer cannot republish AP");
        PowerLatch restored=new PowerLatch(r.file,BOOT,N+2000);check(restored.paused(),"OFF survives root restart");
        ApState restartedAp=new ApState("restart",2);new PowerControl(restored,restartedAp,2);
        check(!restartedAp.demanded(),"provider restart keeps OFF");
        r.callback(false);check(r.deliver()==PowerLatch.ACCEPTED,"ON accepted");check(r.ap.demanded(),"explicit ON resumes demand");
        r.ap.publish(r.ap.revision(),ready(3));check(r.ap.current(3).ready,"fresh AP observation after ON");
        check(!new PowerLatch(r.file,BOOT,N+2000).paused(),"ON survives restart");
        check(r.mod.hookErrors.isEmpty(),"hooks preserve original outcome");

        Rig nested=new Rig(dir(work,"nested"));nested.listener.insideOff=new Runnable(){public void run(){try{nested.callback(false);}catch(Throwable e){throw new AssertionError(e);}}};
        nested.callback(true);check(nested.queue.current()!=null&&!nested.queue.current().off,"nested ON supersedes earlier OFF entry");
        nested.deliver();check(nested.ap.demanded(),"late OFF completion cannot reverse ON");
        Rig throwing=new Rig(dir(work,"throw"));throwing.listener.throwOff=true;
        try{throwing.callback(true);throw new AssertionError("original exception suppressed");}catch(IllegalStateException expected){}
        check(throwing.queue.current().off,"OEM notification retained even original throws");throwing.deliver();
        check(!throwing.ap.demanded(),"throwing original still pauses owned AP");
        Rig disabled=new Rig(dir(work,"disabled"));disabled.mod.on=false;disabled.callback(true);
        check(disabled.listener.off==1&&disabled.queue.current()==null,"disabled hooks leave OEM only");

        PowerQueue queue=new PowerQueue();queue.offer(true,N+100);PowerQueue.Pending old=queue.current();
        queue.offer(false,N+200);queue.delivered(old);check(!queue.current().off,"late ACK cannot clear newer ON");
        queue.offer(true,N+150);check(!queue.current().off&&queue.current().nanos==N+200,"delayed older OFF rejected");
        PowerQueue.Pending latest=queue.current();queue.delivered(latest);check(queue.current()==null,"ACK drains exact pending event");
        queue.offer(true,N+300);check(queue.current()!=null,"unavailable provider retains OFF until ACK");
        queue.offer(false,N+400);check(!queue.current().off,"wake replaces unsent OFF");
        PowerQueue ties=new PowerQueue();ties.offer(true,N);ties.offer(false,N);ties.offer(true,N);
        check(ties.current().off&&ties.current().nanos==N+2,"three same-tick events preserve entry order");
        ties.offer(false,N-1);check(ties.current().off,"older raw timestamp cannot override tied events");
        ties.offer(false,N);check(!ties.current().off&&ties.current().nanos==N+3,"another same-tick ON is retained");
        long start=System.nanoTime();queue.await(null,500);check(System.nanoTime()-start<200000000L,"arrival before await does not lose wakeup");

        File orderFile=new File(dir(work,"order"),"power-event");PowerLatch order=new PowerLatch(orderFile,BOOT,N+1000);
        check(order.accept(new PowerLatch.Event(BOOT,N+500,true),N+1000)==0,"baseline OFF");
        check(order.accept(new PowerLatch.Event(BOOT,N+400,false),N+1000)==2&&order.paused(),"old ON cannot clear OFF");
        check(order.accept(new PowerLatch.Event(BOOT,N+500,true),N+1000)==1,"lost ACK duplicate idempotent");
        check(order.accept(new PowerLatch.Event(BOOT,N+500,false),N+1000)==2,"equal-stamp conflicting event rejected");
        check(order.accept(new PowerLatch.Event(NEXT,N+600,false),N+1000)==2,"foreign boot rejected");
        check(order.accept(new PowerLatch.Event(BOOT,N+2000,false),N+1000)==2,"future ON rejected");
        PowerLatch sameBoot=new PowerLatch(orderFile,BOOT,N+1000);
        check(sameBoot.accept(new PowerLatch.Event(BOOT,N+499,false),N+1000)==2&&sameBoot.paused(),"old ON rejected after root restart");
        PowerLatch nextBoot=new PowerLatch(orderFile,NEXT,1000);check(!nextBoot.paused(),"previous boot OFF not replayed");
        check(nextBoot.accept(new PowerLatch.Event(BOOT,N+600,true),2000)==2,"old boot wire not replayed");
        check(nextBoot.accept(new PowerLatch.Event(NEXT,1500,false),2000)==0,"new boot explicit event allowed");

        File bad=new File(dir(work,"bad"),"power-event");Files.write(bad.toPath(),"garbage\n".getBytes("US-ASCII"));
        PowerLatch invalid=new PowerLatch(bad,BOOT,N+1000);check(invalid.paused(),"malformed checkpoint fails closed");
        check(invalid.accept(new PowerLatch.Event(BOOT,N+600,false),N+1000)==0&&!invalid.paused(),"explicit ON repairs ordinary malformed checkpoint");
        File symlink=new File(dir(work,"link"),"power-event");Files.createSymbolicLink(symlink.toPath(),bad.toPath());
        PowerLatch linked=new PowerLatch(symlink,BOOT,N+1000);check(linked.paused(),"linked checkpoint fails closed");
        check(linked.accept(new PowerLatch.Event(BOOT,N+700,false),N+1000)==3&&linked.paused(),"linked checkpoint never written");

        File proc=dir(work,"proc");File process=dir(proc,"42");
        Files.write(new File(process,"cmdline").toPath(),("com.daudio.app.dipo\0"+new String(new char[500])).getBytes("US-ASCII"));
        check(PowerLatch.dipoPeer(1000,42,proc),"trusted UID and exact process name");
        check(!PowerLatch.dipoPeer(0,42,proc)&&!PowerLatch.dipoPeer(2000,42,proc),"wrong UID rejected");
        Files.write(new File(process,"cmdline").toPath(),"com.daudio.app.dipo.evil\0".getBytes("US-ASCII"));
        check(!PowerLatch.dipoPeer(1000,42,proc),"process prefix is not authentication");
        Files.write(new File(process,"cmdline").toPath(),"com.mobis.welcome\0".getBytes("US-ASCII"));
        check(PowerLatch.powerPeer(2,1000,42,proc),"exact system Welcome process may use animation command");
        check(!PowerLatch.powerPeer(1,1000,42,proc),"Welcome may not impersonate DiPO command");
        check(!PowerLatch.powerPeer(2,0,42,proc)&&!PowerLatch.powerPeer(2,2000,42,proc),"animation command rejects root/shell UID impersonation");
        check(!PowerLatch.powerPeer(0,1000,42,proc)&&!PowerLatch.powerPeer(3,1000,42,proc),"only the two fixed commands are power events");
        check(!PowerLatch.powerPeer(2,1000,1,proc)&&!PowerLatch.powerPeer(2,1000,999,proc),"invalid/missing animation process rejected");
        Files.write(new File(process,"cmdline").toPath(),"com.mobis.welcome.evil\0".getBytes("US-ASCII"));
        check(!PowerLatch.powerPeer(2,1000,42,proc),"animation process prefix rejected");
        Files.write(new File(process,"cmdline").toPath(),"com.daudio.app.dipo\0".getBytes("US-ASCII"));
        check(PowerLatch.powerPeer(1,1000,42,proc)&&!PowerLatch.powerPeer(2,1000,42,proc),"DiPO remains restricted to its original command");
        check(PowerLatch.source(1).equals("DIPO")&&PowerLatch.source(2).equals("WELCOME"),"numeric commands map to fixed log source enums");
        try{PowerLatch.Event.read(new DataInputStream(new ByteArrayInputStream(new byte[44])));throw new AssertionError("short wire accepted");}catch(IOException expected){}
        animationLifecycle(work);
        System.out.println("PowerLifecycleTest PASS checks="+checks);
    }
}
