package kr.ioniq5.usbcp;

import android.net.*;
import android.os.SystemClock;
import java.io.*;
import java.util.UUID;

/** Authenticated local IPC; no TCP listener and no credentials on disk or in logs. */
public final class Provider {
    public static final String SOCKET="ioniq5.cp.ap.v1";
    public static final String PRODUCT="NEEV.KOR.0000.V039.001.260902";
    private final String epoch=UUID.randomUUID().toString();
    private ApState state;
    private PowerControl power;
    private DecisionLog decisions;
    public static String property(String key) throws Exception {
        return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,key);
    }
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=0 || !PRODUCT.equals(property("ro.build.product"))
            || !"tcc803x".equals(property("ro.board.platform")))throw new IOException("wrong target or uid");
        if(args.length==1 && args[0].equals("probe")) {
            try {LiveAp ap=new LiveAp(); Snapshot s=ap.observe("probe",SystemClock.elapsedRealtime());
                System.out.println("AP_READY iface="+s.iface+" ifindex="+s.ifindex+" frequency="+s.frequency+" credential_source="+ap.credentialEvidence);
            }catch(Exception e){System.out.println("AP_UNAVAILABLE reason="+safeReason(e));System.exit(2);}return;
        }
        if(args.length==1 && args[0].equals("selftest")){selftest();return;}
        new Provider().run();
    }
    static String safeReason(Exception e) {
        // All diagnostics are fixed categories. Never print throwable messages from sockets/parsers.
        return e instanceof IOException ? "observation_failed" : "unsupported_runtime";
    }
    // This exact KitKat framework forgets ownership on accepted descriptors.
    // Set ownership only on sockets just accepted by this process; close() then
    // executes Libcore.os.close instead of merely discarding the Java fd reference.
    static final class AcceptedOwner {
        final java.lang.reflect.Field impl, owns;
        public AcceptedOwner() throws Exception {
            impl=LocalSocket.class.getDeclaredField("impl");impl.setAccessible(true);
            owns=impl.getType().getDeclaredField("mFdCreatedInternally");owns.setAccessible(true);
            if(owns.getType()!=boolean.class)throw new IOException("unsupported socket ownership");
        }
        public void take(LocalSocket socket)throws Exception {owns.setBoolean(impl.get(socket),true);}
    }
    private final java.util.concurrent.atomic.AtomicBoolean recoveryQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final ApServiceRecovery recovery=new ApServiceRecovery();
    private int manualGate() {
        try {
            if(!ExternalDongleSetting.read(new File(ExternalDongleSetting.PATH)))return ManualWire.DISABLED;
            if("OFF".equals(power.state()))return ManualWire.POWER_OFF;
            if(!ManualPowerGate.allowed(SystemClock.elapsedRealtimeNanos()))return ManualWire.AP_UNAVAILABLE;
            return state.current(SystemClock.elapsedRealtime()).ready?ManualWire.READY:ManualWire.AP_UNAVAILABLE;
        }catch(Exception unavailable){return ManualWire.DISABLED;}
    }
    private void queueApRecovery() {
        if(!recoveryQueued.compareAndSet(false,true))return;
        Thread worker=new Thread(new Runnable(){public void run(){
            try {
                int result=recovery.recover(new ApServiceRecovery.Gate(){public int blocked(){
                    try {
                        if(!ExternalDongleSetting.read(new File(ExternalDongleSetting.PATH)))return ApServiceRecovery.DISABLED;
                        String value=power.state();
                        return "OFF".equals(value)?ApServiceRecovery.POWER_OFF:0;
                    }catch(Exception unavailable){return ApServiceRecovery.DISABLED;}
                }},new HmgApSupervisor(),SystemClock.elapsedRealtime());
                decisions.event("CP_MANUAL_AP recovery="+result);
            }catch(Throwable failure){decisions.event("CP_MANUAL_AP recovery=failed");}
            finally{recoveryQueued.set(false);}
        }},"usbcp-manual-ap");worker.setDaemon(true);worker.start();
    }

    static void selftest()throws Exception {
        final AcceptedOwner owner=new AcceptedOwner();
        final String address="ioniq5-fd-test-"+UUID.randomUUID();
        final LocalServerSocket server=new LocalServerSocket(address);
        final Throwable[] error=new Throwable[1];
        int before=new File("/proc/self/fd").list().length;
        Thread worker=new Thread(new Runnable(){public void run(){try {
            for(int i=0;i<2048;i++) {
                LocalSocket s=server.accept();owner.take(s);
                try {s.setSoTimeout(1500);if(s.getInputStream().read()!=7)throw new IOException("test request");s.getOutputStream().write(8);}
                finally{s.close();}
            }
        }catch(Throwable e){error[0]=e;}}});worker.setDaemon(true);worker.start();
        try {
            for(int i=0;i<2048;i++) {
                LocalSocket c=new LocalSocket();
                try {c.connect(new LocalSocketAddress(address,LocalSocketAddress.Namespace.ABSTRACT));c.setSoTimeout(1500);c.getOutputStream().write(7);if(c.getInputStream().read()!=8)throw new IOException("test reply");}
                finally{c.close();}
            }
            worker.join(3000);
            if(worker.isAlive() || error[0]!=null)throw new IOException("accepted socket test failed");
            int after=new File("/proc/self/fd").list().length;
            if(after>before+4)throw new IOException("descriptor growth");
            System.out.println("FD_SELFTEST_PASS requests=2048 before="+before+" after="+after);
        }finally{server.close();}
    }
    private void run() throws Exception {
        final AcceptedOwner owner=new AcceptedOwner();
        boolean externalEnabled=false;String externalStatus="INVALID";
        try {externalEnabled=ExternalDongleSetting.read(new File(ExternalDongleSetting.PATH));externalStatus=externalEnabled?"ON":"OFF";}
        catch(IOException invalid) { /* Malformed settings keep the owned radio unavailable until restart. */ }
        state=new ApState(epoch,SystemClock.elapsedRealtime(),externalEnabled);
        power=new PowerControl(new PowerLatch(new File("/data/local/tmp/neev_usbcp/power-event"),PowerLatch.bootId(),SystemClock.elapsedRealtimeNanos()),state,SystemClock.elapsedRealtime());
        final LocalServerSocket server=new LocalServerSocket(SOCKET); // bind before starting worker: singleton
        decisions=new DecisionLog(new File("/data/nemesis/neev-usbcp-logs/provider.log"));
        decisions.event("CP_PROVIDER version=1.0.3 event=started");
        decisions.event("CP_CONFIG external_dongle="+externalStatus);
        decisions.event("CP_POWER event=restored state="+power.state());
        Thread poll=new Thread(new Runnable(){public void run(){
            LiveAp ap=new LiveAp();boolean wasReady=false;long heartbeat=0;
            while(true) {
                long version=state.revision(),now=SystemClock.elapsedRealtime();Snapshot before=state.current(now);
                boolean failed=false;
                if(state.demanded())try{
                    Snapshot next=ap.observe(epoch,now);
                    int recovered=state.failures();state.publish(version,next);
                    if(recovered>0)decisions.event("CP_OBSERVER event=recovered failures="+recovered);
                }catch(Exception e){
                    failed=true;
                    boolean timeout=e instanceof LiveAp.ControlFailure && "receive".equals(((LiveAp.ControlFailure)e).step) && "timeout".equals(((LiveAp.ControlFailure)e).kind);
                    boolean identity=timeout&&ap.sameIdentity(before);
                    boolean retain=state.failed(version,SystemClock.elapsedRealtime(),identity);
                    // Phase/kind are fixed categories, never raw exception text or credentials.
                    int count=state.failures();
                    if(count<=3 || count%60==0) {
                        if(e instanceof UsbIds.LookupFailure) {
                            UsbIds.LookupFailure f=(UsbIds.LookupFailure)e;
                            decisions.event("CP_USB_ID reason="+f.reason+" id="+f.id);
                        } else if(!"method=none id=none".equals(ap.usbCandidateDiagnostic())) {
                            decisions.event("CP_USB_ID event=seen "+ap.usbCandidateDiagnostic());
                        }
                    }
                    if(count<=3||count%60==0)decisions.event("CP_OBSERVER event="+(retain?"retry":"unavailable")+" phase="+ap.phase+" kind="+(e instanceof LiveAp.ControlFailure?((LiveAp.ControlFailure)e).kind:LiveAp.errorKind(e))+" failures="+count+" timeout="+(timeout?1:0)+" identity="+(identity?1:0)+" age_ms="+(before.ready?SystemClock.elapsedRealtime()-before.observed:0));
                }
                now=SystemClock.elapsedRealtime();boolean ready=state.current(now).ready;
                if(ready!=wasReady){decisions.event("CP_PROVIDER event="+(ready?"ready":"down")+" age_ms="+(before.ready?now-before.observed:0));
                    if(ready)decisions.event("CP_USB_ID event=ready "+ap.usbDiagnostic());wasReady=ready;}
                if(now-heartbeat>=60000){decisions.event("CP_PROVIDER event=heartbeat ready="+(ready?1:0));heartbeat=now;}
                try{Thread.sleep(failed&&state.failures()<=2?350:1000);}catch(InterruptedException e){return;}
            }
        }},"ioniq5-ap-observer");poll.setDaemon(true);poll.start();
        while(true) {
            LocalSocket s=server.accept();
            owner.take(s);
            try {
                s.setSoTimeout(600);
                int uid=s.getPeerCredentials().getUid();
                if(uid!=0 && uid!=1000)continue;
                DataInputStream in=new DataInputStream(s.getInputStream());
                if(in.readInt()!=Snapshot.MAGIC)continue;
                int command=in.readUnsignedByte();
                if(command==ManualWire.PROVIDER_GATE || command==ManualWire.PROVIDER_PREPARE) {
                    if(!ManualWire.peer(uid,s.getPeerCredentials().getPid(),ManualWire.DIPO))continue;
                    int result=manualGate();
                    if(command==ManualWire.PROVIDER_PREPARE && result==ManualWire.AP_UNAVAILABLE)
                        queueApRecovery();
                    s.getOutputStream().write(result);s.getOutputStream().flush();continue;
                }
                if(PowerLatch.powerCommand(command)) {
                    if(!PowerLatch.powerPeer(command,uid,s.getPeerCredentials().getPid()))continue;
                    PowerLatch.Event event=PowerLatch.Event.read(in);
                    int result=power.accept(event,SystemClock.elapsedRealtimeNanos(),SystemClock.elapsedRealtime());
                    decisions.event("CP_POWER event="+(result==PowerLatch.ACCEPTED?"accepted":result==PowerLatch.DUPLICATE?"duplicate":result==PowerLatch.REJECTED?"rejected":"failed")+" state="+power.state()+" source="+PowerLatch.source(command));
                    s.getOutputStream().write(result);s.getOutputStream().flush();continue;
                }
                if(command!=0)continue;
                Snapshot v=state.current(SystemClock.elapsedRealtime());
                DataOutputStream out=new DataOutputStream(s.getOutputStream());v.write(out);out.flush();
            }catch(IOException e) { /* bounded failed request, next client */ }
            finally{s.close();}
        }
    }
}
