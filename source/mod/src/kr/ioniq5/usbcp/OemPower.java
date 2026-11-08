package kr.ioniq5.usbcp;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/** Queue authenticated power observations without IPC on an OEM callback thread. */
public final class OemPower {
    public interface Sink {void offer(boolean off);}
    public static void install(final ModContext mod,LoadedApp app) {
        final PowerQueue queue=new PowerQueue();
        install(mod,app,sink(queue));
        startSender(mod,queue);
    }
    /** Called only after the exact WelcomeService sender hook has been verified. */
    public static Sink createAnimationSink(final ModContext mod) {
        PowerQueue queue=new PowerQueue(PowerLatch.WELCOME_COMMAND);
        startSender(mod,queue);
        return sink(queue);
    }
    private static Sink sink(final PowerQueue queue) {
        return new Sink(){public void offer(boolean off){queue.offer(off,SystemClock.elapsedRealtimeNanos());}};
    }
    private static void startSender(final ModContext mod,final PowerQueue queue) {
        Thread sender=new Thread(new Runnable(){public void run(){
            PowerQueue.Pending seen=null;int failures=0;
            while(true) {
                PowerQueue.Pending event=queue.current();
                if(event==null){try{queue.await(null,2000);}catch(InterruptedException stopped){return;}continue;}
                String source=PowerLatch.source(event.command);
                if(event!=seen){seen=event;failures=0;mod.log("USB_CP_POWER event=queued state="+(event.off?"OFF":"ON")+" source="+source);}
                try {
                    int result=send(event.command,new PowerLatch.Event(PowerLatch.bootId(),event.nanos,event.off));
                    if(result==PowerLatch.FAILED)throw new java.io.IOException("power commit failed");
                    queue.delivered(event);
                    mod.log("USB_CP_POWER event="+(result==PowerLatch.REJECTED?"superseded":"delivered")+" state="+(event.off?"OFF":"ON")+" source="+source);
                }catch(Exception unavailable){
                    if(++failures==1||failures%60==0)mod.log("USB_CP_POWER event=retry state="+(event.off?"OFF":"ON")+" source="+source);
                    try{queue.await(event,1000);}catch(InterruptedException stopped){return;}
                }
            }
        }},"ioniq5-power-events");sender.setDaemon(true);sender.start();
    }
    static void install(final ModContext mod,LoadedApp app,final Sink sink) {
        Points.method(app.classLoader,"dipo.mode_power_listener.power_off");
        Points.method(app.classLoader,"dipo.mode_power_listener.power_on");
        hook(mod,app,"dipo.mode_power_listener.power_off",true,sink);
        hook(mod,app,"dipo.mode_power_listener.power_on",false,sink);
    }
    private static void hook(final ModContext mod,LoadedApp app,String point,final boolean off,final Sink sink) {
        mod.hook(app.classLoader,point,new MethodHook(){
            // The callback itself is the OEM notification. Observe its entry so
            // nested ON or delayed original completion cannot reorder events.
            // Original return values and exceptions are never suppressed.
            @Override protected void beforeHookedMethod(Param p){if(mod.enabled())sink.offer(off);}
        });
    }
    private static int send(int command,PowerLatch.Event event)throws Exception {
        if(!PowerLatch.powerCommand(command))throw new java.io.IOException("invalid power source");
        LocalSocket socket=new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(Provider.SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(1000);
            if(socket.getPeerCredentials().getUid()!=0)throw new java.io.IOException("wrong provider");
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(Snapshot.MAGIC);out.writeByte(command);event.write(out);out.flush();
            int result=new DataInputStream(socket.getInputStream()).readUnsignedByte();
            if(result<0||result>PowerLatch.FAILED)throw new java.io.IOException("bad power response");return result;
        }finally{socket.close();}
    }
}
