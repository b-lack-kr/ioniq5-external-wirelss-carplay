package kr.ioniq5.usbcp;

import android.net.*;
import android.os.SystemClock;
import java.io.*;

/** Only the worker uses IPC. Hook threads read an immutable, expiring snapshot. */
public final class ApClient {
    public interface Listener {void changed(Snapshot before,Snapshot after);}
    public interface Audit {void event(String detail);}
    public interface Port {Snapshot read(int command)throws IOException;long now();}
    private volatile Snapshot snapshot;
    private volatile boolean running=true;
    private int command;
    private boolean requested=true;
    private long revision;
    private Snapshot previous;
    private final Listener listener;
    private final Port port;
    private final Audit audit;
    private int failures;
    private volatile String reason="startup";
    public ApClient(Listener listener){this(listener,new Audit(){public void event(String ignored){}});}
    public ApClient(Listener listener,Audit audit){this(listener,new Port(){
        public Snapshot read(int command)throws IOException{return query(command);}
        public long now(){return SystemClock.elapsedRealtime();}
    },audit);}
    public ApClient(Listener listener,Port port){this(listener,port,new Audit(){public void event(String ignored){}});}
    public ApClient(Listener listener,Port port,Audit audit){this.listener=listener;this.port=port;this.audit=audit;}
    public String reason(){return reason;}
    private void note(String event){audit.event("CP_CLIENT "+event);}
    public synchronized void request(boolean on){
        if(on==requested && (command==(on?1:2) || !on || current()!=null))return;
        requested=on;command=on?1:2;revision++;snapshot=null;failures=0;reason=on?"acquire":"release";note("event="+reason);
    }
    public Snapshot current(){Snapshot s=snapshot;return s!=null && s.fresh(port.now()) ? s : null;}
    public synchronized void stop(){running=false;revision++;snapshot=null;}
    static Snapshot query(int command) throws IOException {
        LocalSocket s=new LocalSocket();
        try {
            // KitKat creates the descriptor lazily in connect/bind, not the constructor.
            s.connect(new LocalSocketAddress(Provider.SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(600);
            if(s.getPeerCredentials().getUid()!=0)throw new IOException("untrusted AP provider");
            DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeInt(Snapshot.MAGIC);out.writeByte(command);out.flush();
            return Snapshot.read(new DataInputStream(s.getInputStream()));
        }finally{s.close();}
    }
    void pollOnce(){
        int c;long version;synchronized(this){if(!running)return;c=command;version=revision;}
        Snapshot next=null;boolean received=false;String receivedReason="provider_down";
        try{Snapshot read=port.read(c);received=true;if(read.fresh(port.now())){next=read;receivedReason="ready";}else if(read.ready)receivedReason="provider_snapshot_expired";}
        catch(IOException e){
            failures++;
            boolean timeout="timeout".equals(LiveAp.errorKind(e));
            Snapshot old=snapshot;
            if(c==0 && timeout && failures<=2 && old!=null && old.fresh(port.now())){
                next=old;reason="ipc_retry";
            }else reason=timeout?"ipc_timeout":"ipc_failure";
            if(failures<=3 || failures%60==0)note("event="+reason+" failures="+failures);
        }
        if(received){
            if(failures>0)note("event=ipc_recovered failures="+failures);
            failures=0;reason=receivedReason;
        }
        synchronized(this){
            if(!running)return;
            if(version!=revision){next=null;reason="request_changed";}
            else if(received)command=0; // retry failed requests, never discard a newer one
            snapshot=next;
        }
        if(previous!=null && (next==null || !previous.generation.equals(next.generation)) || previous==null && next!=null)
            {if(next==null)note("event=down reason="+reason);else note("event=ready");listener.changed(previous,next);}
        previous=next;
    }
    public void start(){Thread t=new Thread(new Runnable(){public void run(){
        while(running){pollOnce();try{Thread.sleep(failures>0&&failures<=2?350:(snapshot!=null?1000:350));}catch(InterruptedException e){return;}}
    }},"ioniq5-aa-ipc");t.setDaemon(true);t.start();}
}
