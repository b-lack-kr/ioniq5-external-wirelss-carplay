package kr.ioniq5.usbcp;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.os.Looper;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

/** Blocking API: callers must use a worker. No power event or radio restart command. */
public final class ManualCarplay {
    public static final int READY=0, ACCEPTED=1, CONNECTED=2, BUSY=3, POWER_OFF=4,
        DISABLED=5, AP_UNAVAILABLE=6, PHONE_REQUIRED=7, NOT_APPLE=8, TIMED_OUT=9, FAILED=10;
    public static final int CURRENT_HFP=1, ACTIVE_CP=2, ACTIVE_TARGET=4, PENDING_TARGET=8;
    private static final AtomicLong sequence=new AtomicLong();
    private ManualCarplay() {}
    public static byte[] address(String value){return ManualWire.address(value);}
    public static String format(byte[] value){return ManualWire.format(value);}
    public static final class Result extends ManualWire.Result {public Result(int s,int f){super(s,f);}}
    public static Result query(byte[] address)throws IOException {return exchange(ManualWire.QUERY,address);}
    public static Result retry(byte[] address)throws IOException {return exchange(ManualWire.RETRY,address);}
    private static Result exchange(int action,byte[] address)throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("manual IPC requires worker");
        ManualWire.requireAddress(address);
        LocalSocket socket=new LocalSocket();
        try {
            // KitKat creates its fd lazily. Bind first, then bound both receive
            // and send/connect waits with the exact framework's SO_TIMEOUT path.
            socket.bind(new LocalSocketAddress("usbcp-manual-"+UUID.randomUUID(),LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(1800);
            socket.connect(new LocalSocketAddress(ManualWire.SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            android.net.Credentials peer=socket.getPeerCredentials();
            if(!ManualWire.peer(peer.getUid(),peer.getPid(),ManualWire.DIPO))throw new IOException("untrusted manual server");
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            DataInputStream in=new DataInputStream(socket.getInputStream());
            out.writeInt(ManualWire.MAGIC);out.flush();
            if(in.readInt()!=ManualWire.MAGIC)throw new IOException("manual protocol version");
            long hi=in.readLong(),lo=in.readLong();
            long before,next;
            do {before=sequence.get();next=Math.max(SystemClock.elapsedRealtimeNanos(),before+1);}
            while(!sequence.compareAndSet(before,next));
            new ManualWire.Request(action,next,address).write(out,hi,lo);out.flush();
            ManualWire.Result result=ManualWire.Result.read(in);return new Result(result.status,result.flags);
        }finally{socket.close();}
    }
    /** DiPO worker only; root Provider independently authenticates the caller. */
    public static int gate()throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("manual gate requires worker");
        LocalSocket socket=new LocalSocket();
        try {
            socket.bind(new LocalSocketAddress("usbcp-manual-gate-"+UUID.randomUUID(),LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(600);
            socket.connect(new LocalSocketAddress(Provider.SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            if(socket.getPeerCredentials().getUid()!=0)throw new IOException("untrusted AP provider");
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(Snapshot.MAGIC);out.writeByte(ManualWire.PROVIDER_GATE);out.flush();
            int status=socket.getInputStream().read();
            if(status!=READY&&status!=POWER_OFF&&status!=DISABLED&&status!=AP_UNAVAILABLE)throw new IOException("invalid manual gate");
            return status;
        }finally{socket.close();}
    }
}
