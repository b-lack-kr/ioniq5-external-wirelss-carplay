"""Actual ManualCarplay client with scripted local sockets; no device or host IPC.
The only ManualWire remap is /proc -> a temporary credentials fixture.
"""
from pathlib import Path
import subprocess,tempfile,json
from jvm_tools import java_bin
SOURCE=Path(__file__).resolve().parents[1]
FILES={
'android/net/LocalSocketAddress.java':'''package android.net;public class LocalSocketAddress{public enum Namespace{ABSTRACT} public final String name;public LocalSocketAddress(String n,Namespace s){name=n;}}''',
'android/net/Credentials.java':'''package android.net;public class Credentials{public int getUid(){return LocalSocket.uid;}public int getPid(){return 42;}}''',
'android/net/LocalSocket.java':'''package android.net;import java.io.*;public class LocalSocket {
 public static byte[] response;public static int uid=1000,opened,closed,timeout;public static String destination;
 public static ByteArrayOutputStream sent;boolean bound;
 public LocalSocket(){opened++;sent=new ByteArrayOutputStream();}
 public void bind(LocalSocketAddress a){if(a.name.length()>100)throw new AssertionError();bound=true;}
 public void setSoTimeout(int ms){if(!bound)throw new AssertionError("timeout before lazy fd exists");timeout=ms;}
 public void connect(LocalSocketAddress a){if(!bound||timeout<=0)throw new AssertionError("unbounded connect");destination=a.name;}
 public Credentials getPeerCredentials(){return new Credentials();}
 public InputStream getInputStream(){return new ByteArrayInputStream(response);}
 public OutputStream getOutputStream(){return sent;}
 public void close(){closed++;}
}''',
'android/os/SystemClock.java':'''package android.os;public class SystemClock {public static long elapsedRealtimeNanos(){return 10000000000L;}}''',
'android/os/Looper.java':'''package android.os;public class Looper {static final Looper main=new Looper();public static boolean ui;public static Looper myLooper(){return ui?main:null;}public static Looper getMainLooper(){return main;}}''',
'kr/ioniq5/usbcp/Provider.java':'''package kr.ioniq5.usbcp;public class Provider {public static final String SOCKET="ioniq5.cp.ap.v1";}''',
'kr/ioniq5/usbcp/Snapshot.java':'''package kr.ioniq5.usbcp;public class Snapshot {public static final int MAGIC=0x49413531;}''',
'kr/ioniq5/usbcp/ClientTest.java':r'''package kr.ioniq5.usbcp;
import java.io.*;import java.nio.file.*;import android.net.*;import android.os.Looper;
public class ClientTest {
 static int checks;static void ok(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static void peer(Path proc,String name)throws Exception{Files.write(proc.resolve("42/cmdline"),(name+"\0").getBytes("US-ASCII"));}
 static void response(int status,int flags)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(ManualWire.MAGIC);out.writeLong(10);out.writeLong(20);new ManualWire.Result(status,flags).write(out);LocalSocket.response=bytes.toByteArray();}
 public static void main(String[] args)throws Exception{
  Path proc=Paths.get(args[0]);peer(proc,ManualWire.DIPO);
  byte[] address=ManualCarplay.address("12:34:56:78:9A:BC");
  response(ManualWire.ACCEPTED,ManualWire.CURRENT_HFP|ManualWire.PENDING_TARGET);
  ManualCarplay.Result got=ManualCarplay.retry(address);ok(got.status==1&&got.flags==9);ok(LocalSocket.timeout==1800&&LocalSocket.destination.equals(ManualWire.SOCKET));
  DataInputStream wire=new DataInputStream(new ByteArrayInputStream(LocalSocket.sent.toByteArray()));
  ok(wire.readInt()==ManualWire.MAGIC);ManualWire.Request decoded=ManualWire.Request.read(wire,10,20);ok(decoded.action==1&&wire.available()==0);
  ok(decoded.address.length==6&&decoded.sequence>0);long before=decoded.sequence;
  response(2,6);got=ManualCarplay.query(address);ok(got.status==2&&got.flags==6);
  wire=new DataInputStream(new ByteArrayInputStream(LocalSocket.sent.toByteArray()));wire.readInt();decoded=ManualWire.Request.read(wire,10,20);ok(decoded.action==0&&decoded.sequence>before);
  int opened=LocalSocket.opened;Looper.ui=true;try{ManualCarplay.query(address);throw new AssertionError("UI IPC");}catch(IOException expected){checks++;}ok(LocalSocket.opened==opened);Looper.ui=false;
  peer(proc,ManualWire.DIPO+":wrong");response(0,0);try{ManualCarplay.retry(address);throw new AssertionError("foreign peer");}catch(IOException expected){checks++;}
  peer(proc,ManualWire.DIPO);LocalSocket.uid=0;try{ManualCarplay.retry(address);throw new AssertionError("root is not DiPO");}catch(IOException expected){checks++;}
  LocalSocket.uid=1000;LocalSocket.response=new byte[0];try{ManualCarplay.query(address);throw new AssertionError("truncated challenge");}catch(IOException expected){checks++;}
  LocalSocket.uid=0;LocalSocket.response=new byte[]{0};ok(ManualCarplay.gate()==0);ok(LocalSocket.timeout==600&&LocalSocket.destination.equals(Provider.SOCKET));
  wire=new DataInputStream(new ByteArrayInputStream(LocalSocket.sent.toByteArray()));ok(wire.readInt()==Snapshot.MAGIC&&wire.readUnsignedByte()==3&&wire.available()==0);
  for(int code:new int[]{4,5,6}){LocalSocket.response=new byte[]{(byte)code};ok(ManualCarplay.gate()==code);}
  for(int bad:new int[]{1,2,7,255}){LocalSocket.response=new byte[]{(byte)bad};try{ManualCarplay.gate();throw new AssertionError("bad gate");}catch(IOException expected){checks++;}}
  LocalSocket.uid=1000;LocalSocket.response=new byte[]{0};try{ManualCarplay.gate();throw new AssertionError("non-root provider");}catch(IOException expected){checks++;}
  Looper.ui=true;opened=LocalSocket.opened;try{ManualCarplay.gate();throw new AssertionError("UI gate");}catch(IOException expected){checks++;}ok(LocalSocket.opened==opened);
  ok(LocalSocket.opened==LocalSocket.closed);
  System.out.println("PASS "+checks+" actual manual IPC client boundaries; scripted sockets only");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='neev-manual-ipc-') as tmp:
    root=Path(tmp);proc=root/'proc';(proc/'42').mkdir(parents=True)
    wire=(SOURCE/'mod/src/kr/ioniq5/usbcp/ManualWire.java').read_text().replace('new File("/proc")','new File('+json.dumps(str(proc))+')')
    FILES['kr/ioniq5/usbcp/ManualWire.java']=wire
    FILES['kr/ioniq5/usbcp/ManualCarplay.java']=(SOURCE/'mod/src/kr/ioniq5/usbcp/ManualCarplay.java').read_text()
    for name,text in FILES.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    jdk=java_bin(SOURCE)
    subprocess.run([jdk/'javac','--release','8','-d',root,*sorted(root.rglob('*.java'))],check=True)
    subprocess.run([jdk/'java','-cp',root,'kr.ioniq5.usbcp.ClientTest',proc],check=True)
