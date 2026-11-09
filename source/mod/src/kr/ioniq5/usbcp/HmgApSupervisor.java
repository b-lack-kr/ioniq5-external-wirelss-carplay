package kr.ioniq5.usbcp;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import java.io.*;
import java.util.UUID;

/** Root Provider worker adapter for the existing HMG-API 1.0.1/1.0.2 service API. */
public final class HmgApSupervisor implements ApServiceRecovery.Supervisor {
    public String status()throws IOException {return call("services","neev-usb-carplay");}
    public boolean start()throws IOException {
        // service-start also restarts a live service. Recheck before arming;
        // ordinary running APs must never receive a manual-recovery marker.
        if(ApServiceRecovery.classify(status())!=ApServiceRecovery.START_REQUESTED)return false;
        ManualApRecovery.arm();
        return "start neev-usb-carplay/usb-ap: asked\n".equals(call("service-start","neev-usb-carplay","usb-ap"));
    }
    private String call(String... args)throws IOException {
        if(android.os.Process.myUid()!=0||(Looper.myLooper()!=null&&Looper.myLooper()==Looper.getMainLooper()))
            throw new IOException("root worker required");
        LocalSocket socket=new LocalSocket();
        try {
            socket.bind(new LocalSocketAddress("usbcp-supervisor-"+UUID.randomUUID(),LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(600);
            socket.connect(new LocalSocketAddress("hmgapid",LocalSocketAddress.Namespace.ABSTRACT));
            if(socket.getPeerCredentials().getUid()!=0)throw new IOException("untrusted supervisor");
            OutputStream out=socket.getOutputStream();out.write(new byte[]{'L','F','D','1'});u32(out,args.length);
            for(String arg:args){byte[] b=arg.getBytes("US-ASCII");u32(out,b.length);out.write(b);}out.flush();
            return response(new DataInputStream(socket.getInputStream()));
        }finally{socket.close();}
    }
    static String response(DataInputStream in)throws IOException {
        ByteArrayOutputStream text=new ByteArrayOutputStream();
        for(int frame=0;frame<64;frame++) {
            int kind=in.readUnsignedByte(),size=u32(in);
            if(kind=='O') {
                if(size<0||size>8192-text.size())throw new IOException("invalid supervisor output size");
                byte[] data=new byte[size];in.readFully(data);text.write(data);
            }else if(kind=='X') {
                if(size!=4||u32(in)!=0)throw new IOException("supervisor request failed");
                return text.toString("US-ASCII");
            }else throw new IOException("invalid supervisor frame");
        }
        throw new IOException("supervisor frame limit");
    }
    private static int u32(DataInputStream in)throws IOException {
        return in.readUnsignedByte()|in.readUnsignedByte()<<8|in.readUnsignedByte()<<16|in.readUnsignedByte()<<24;
    }
    private static void u32(OutputStream out,int n)throws IOException {
        out.write(n);out.write(n>>>8);out.write(n>>>16);out.write(n>>>24);
    }
}
