package hmgapi.mods.wireless.dipo;

import android.net.*;
import android.os.SystemClock;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** DiPO-only authenticated control. Socket/gate waits never run on a UI or bridge handler. */
final class ManualConnectServer {
    private static boolean started;
    private static final ThreadPoolExecutor gateWorker=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(2),new ThreadFactory(){public Thread newThread(Runnable r){
            Thread t=new Thread(r,"usbcp-manual-gate");t.setDaemon(true);return t;
        }},new ThreadPoolExecutor.AbortPolicy());
    private final long hi,lo;
    private final ManualWire.ReplayWindow replay=new ManualWire.ReplayWindow();
    private ManualConnectServer(){UUID id=UUID.randomUUID();hi=id.getMostSignificantBits();lo=id.getLeastSignificantBits();}
    static synchronized void start() {
        if(started)return;started=true;
        Thread t=new Thread(new Runnable(){public void run(){
            try{new ManualConnectServer().serve();}
            catch(Throwable failure){ManualDiagnostics.failure("server_failed",failure);}
        }},"usbcp-manual-control");t.setDaemon(true);t.start();
    }
    private void serve()throws Exception {
        if(android.os.Process.myUid()!=1000)throw new IOException("manual server uid");
        final ManualGate.AcceptedOwner owner=new ManualGate.AcceptedOwner();
        final LocalServerSocket server=new LocalServerSocket(ManualWire.SOCKET);
        try {
          Bridge.log("USB_CP_MANUAL event=server_ready");
          while(true) {
            LocalSocket socket=server.accept();
            try {
                owner.take(socket);socket.setSoTimeout(600);
                Credentials peer=socket.getPeerCredentials();
                if(!ManualWire.peer(peer.getUid(),peer.getPid(),ManualWire.UI))continue;
                DataInputStream in=new DataInputStream(socket.getInputStream());
                DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                if(in.readInt()!=ManualWire.MAGIC)continue;
                out.writeInt(ManualWire.MAGIC);out.writeLong(hi);out.writeLong(lo);out.flush();
                ManualWire.Request request=ManualWire.Request.read(in,hi,lo);
                long now=SystemClock.elapsedRealtimeNanos();
                if(!replay.accept(request.sequence,now))continue;
                final String address=ManualWire.format(request.address);
                int gate;
                try{gate=request.action==ManualWire.RETRY?ManualGate.prepare():ManualGate.gate();}
                catch(Throwable failure){ManualDiagnostics.failure("gate_failed",failure);gate=ManualWire.FAILED;}
                final int authorizedGate=gate;
                final long authorizedAt=SystemClock.elapsedRealtime();
                final AtomicInteger phase=new AtomicInteger();
                final CountDownLatch finished=new CountDownLatch(1);
                final ManualWire.Result[] reply=new ManualWire.Result[1];
                final int action=request.action;
                Bridge.handler().post(new Runnable(){public void run(){
                    if(!phase.compareAndSet(0,1))return;
                    try {
                        if(SystemClock.elapsedRealtime()-authorizedAt>350)reply[0]=new ManualWire.Result(ManualWire.TIMED_OUT,0);
                        else reply[0]=Bridge.manualControl(address,action,authorizedGate,authorizedAt+350);
                    }catch(Throwable failure){ManualDiagnostics.failure("dispatch_failed",failure);reply[0]=new ManualWire.Result(ManualWire.FAILED,0);}
                    finally{finished.countDown();}
                }});
                if(!finished.await(700,TimeUnit.MILLISECONDS)) {
                    phase.compareAndSet(0,2);
                    new ManualWire.Result(ManualWire.TIMED_OUT,0).write(out);
                }else reply[0].write(out);
                out.flush();
            }catch(Throwable failure){ManualDiagnostics.failure("request_failed",failure);}
            finally{try{socket.close();}catch(Throwable failure){ManualDiagnostics.failure("request_failed",failure);}}
          }
        }finally{server.close();}
    }
    /** Gate each delayed manual attempt again. Never touch AP/power/session state here. */
    static void guard(final String address,final long epoch,final long request,final Runnable attempt) {
        try{gateWorker.execute(new Runnable(){public void run(){
          try {
            int result;
            try{result=ManualGate.gate();}
            catch(Throwable failure){ManualDiagnostics.failure("gate_failed",failure);result=ManualWire.FAILED;}
            final int status=result;final long observed=SystemClock.elapsedRealtime();
            Bridge.handler().post(new Runnable(){public void run(){
                try{Bridge.manualAttempt(address,epoch,request,status,observed,attempt);}
                catch(Throwable failure){ManualDiagnostics.failure("dispatch_failed",failure);}
            }});
          }catch(Throwable failure){ManualDiagnostics.failure("dispatch_failed",failure);}
        }});}catch(Throwable failure){
            ManualDiagnostics.failure("dispatch_failed",failure);
            try{Bridge.manualFailure(address,failure instanceof RejectedExecutionException?ManualWire.BUSY:ManualWire.FAILED);}
            catch(Throwable ignored){ManualDiagnostics.failure("dispatch_failed",ignored);}
        }
    }
}
