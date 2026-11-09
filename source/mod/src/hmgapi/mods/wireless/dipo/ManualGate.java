package hmgapi.mods.wireless.dipo;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import java.io.*;
import java.util.UUID;

/** App-part-local transport: never link DiPO to classes defined by the main mod loader. */
final class ManualGate {
    static final String SOCKET="ioniq5.cp.ap.v1";
    static final int MAGIC=0x49413531;
    private ManualGate() {}

    /** Same root-provider protocol as ManualCarplay.gate; parity checked on the host. */
    static int gate()throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("manual gate requires worker");
        LocalSocket socket=new LocalSocket();
        try {
            socket.bind(new LocalSocketAddress("usbcp-manual-gate-"+UUID.randomUUID(),LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(600);
            socket.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            if(socket.getPeerCredentials().getUid()!=0)throw new IOException("untrusted AP provider");
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(MAGIC);out.writeByte(ManualWire.PROVIDER_GATE);out.flush();
            int status=socket.getInputStream().read();
            if(status!=ManualWire.READY&&status!=ManualWire.POWER_OFF&&status!=ManualWire.DISABLED&&status!=ManualWire.AP_UNAVAILABLE)throw new IOException("invalid manual gate");
            return status;
        }finally{socket.close();}
    }

    /** Explicit click only: queue recovery of a stopped AP service. No credentials. */
    static int prepare() throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("manual gate requires worker");
        LocalSocket socket=new LocalSocket();
        try {
            socket.bind(new LocalSocketAddress("usbcp-manual-prepare-"+UUID.randomUUID(),LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(600);
            socket.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            if(socket.getPeerCredentials().getUid()!=0)throw new IOException("untrusted AP provider");
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(MAGIC);out.writeByte(ManualWire.PROVIDER_PREPARE);out.flush();
            int status=socket.getInputStream().read();
            if(status!=ManualWire.READY&&status!=ManualWire.POWER_OFF&&status!=ManualWire.DISABLED&&status!=ManualWire.AP_UNAVAILABLE)throw new IOException("invalid manual gate");
            return status;
        }finally{socket.close();}
    }

    /** Exact KitKat accepted-FD ownership fix, isolated from the root Provider class. */
    static final class AcceptedOwner {
        final java.lang.reflect.Field impl, owns;
        public AcceptedOwner() throws Exception {
            impl=LocalSocket.class.getDeclaredField("impl");impl.setAccessible(true);
            owns=impl.getType().getDeclaredField("mFdCreatedInternally");owns.setAccessible(true);
            if(owns.getType()!=boolean.class)throw new IOException("unsupported socket ownership");
        }
        public void take(LocalSocket socket)throws Exception {owns.setBoolean(impl.get(socket),true);}
    }
}
