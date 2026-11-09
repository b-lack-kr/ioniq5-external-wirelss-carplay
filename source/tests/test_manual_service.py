"""Actual DiPO manual socket service: wire parity, auth, FD ownership and Error containment.
Android IPC and /proc are scripted fixtures; no vehicle or host sockets are contacted.
"""
from pathlib import Path
import json, subprocess, tempfile
from jvm_tools import java_bin
SOURCE=Path(__file__).resolve().parents[1]
DIPO=SOURCE/'mod/src/hmgapi/mods/wireless/dipo'
MAIN=SOURCE/'mod/src/kr/ioniq5/usbcp'
def body(text, signature):
    start=text.index(signature);a=text.index('{',start);end=a+1;depth=1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[a:end]
for name in ('ManualWire','ManualRetryState'):
    expected=(MAIN/(name+'.java')).read_text().replace('package kr.ioniq5.usbcp;','package hmgapi.mods.wireless.dipo;',1)
    assert expected==(DIPO/(name+'.java')).read_text(),name+' app-part copy drift'
main_gate=body((MAIN/'ManualCarplay.java').read_text(),'int gate()')
main_gate=main_gate.replace('Provider.SOCKET','SOCKET').replace('Snapshot.MAGIC','MAGIC')
for status in ('READY','POWER_OFF','DISABLED','AP_UNAVAILABLE'):
    main_gate=main_gate.replace('status!='+status,'status!=ManualWire.'+status)
assert main_gate==body((DIPO/'ManualGate.java').read_text(),'int gate()'), 'root gate protocol drift'
assert body((MAIN/'Provider.java').read_text(),'class AcceptedOwner')==body((DIPO/'ManualGate.java').read_text(),'class AcceptedOwner'), 'accepted fd ownership drift'
assert 'SOCKET="ioniq5.cp.ap.v1"' in (MAIN/'Provider.java').read_text()
assert 'MAGIC = 0x49413531' in (MAIN/'Snapshot.java').read_text()
FILES={
'android/os/Process.java':'''package android.os;public class Process{public static int myUid(){return 1000;}}''',
'android/os/SystemClock.java':'''package android.os;public class SystemClock{public static long elapsedRealtime(){return 1000;}public static long elapsedRealtimeNanos(){return 1000000000L;}}''',
'android/os/Looper.java':'''package android.os;public class Looper{static final Looper MAIN=new Looper();public static boolean ui;public static Looper myLooper(){return ui?MAIN:null;}public static Looper getMainLooper(){return MAIN;}}''',
'android/os/Handler.java':'''package android.os;public class Handler{public static boolean fail;public boolean post(Runnable r){if(fail)throw new NoClassDefFoundError("private detail");r.run();return true;}}''',
'android/net/LocalSocketAddress.java':'''package android.net;public class LocalSocketAddress{public enum Namespace{ABSTRACT}public final String name;public LocalSocketAddress(String n,Namespace space){name=n;}}''',
'android/net/Credentials.java':'''package android.net;public class Credentials{int uid;public Credentials(int u){uid=u;}public int getUid(){return uid;}public int getPid(){return 42;}}''',
'android/net/LocalSocket.java':r'''package android.net;
import java.io.*;import hmgapi.mods.wireless.dipo.ManualWire;
public class LocalSocket {
 public static volatile boolean failGate;public static int gateStatus=0,rootUid=0,gateRequests,gateCloses;
 public static class Impl{boolean mFdCreatedInternally;}
 private final Impl impl=new Impl();
 public final ByteArrayOutputStream sent=new ByteArrayOutputStream();
 public boolean accepted,closed,bound,readError;public int uid=1000,timeout;public String destination;
 public LocalSocket(){}
 public static LocalSocket accepted(boolean broken){LocalSocket s=new LocalSocket();s.accepted=true;s.readError=broken;return s;}
 public void bind(LocalSocketAddress a){if(a.name.length()>100)throw new AssertionError();bound=true;impl.mFdCreatedInternally=true;}
 public void setSoTimeout(int t){if(!bound&&!accepted)throw new AssertionError("lazy fd absent");timeout=t;}
 public void connect(LocalSocketAddress a){if(!bound||timeout!=600)throw new AssertionError("unbounded gate");destination=a.name;gateRequests++;if(failGate)throw new NoClassDefFoundError("secret identity");}
 public Credentials getPeerCredentials(){return new Credentials(accepted?uid:rootUid);}
 public InputStream getInputStream(){
  if(!accepted)return new ByteArrayInputStream(new byte[]{(byte)gateStatus});
  return new InputStream(){byte[] request;int pos;
   public int read()throws IOException{
    if(readError)throw new NoClassDefFoundError("private address");
    if(pos<4)return (ManualWire.MAGIC>>(24-8*pos++))&255;
    if(request==null){
     DataInputStream challenge=new DataInputStream(new ByteArrayInputStream(sent.toByteArray()));
     if(challenge.readInt()!=ManualWire.MAGIC)throw new AssertionError();long hi=challenge.readLong(),lo=challenge.readLong();
     ByteArrayOutputStream b=new ByteArrayOutputStream();new ManualWire.Request(ManualWire.RETRY,1000000000L,ManualWire.address("12:34:56:78:9A:BC")).write(new DataOutputStream(b),hi,lo);request=b.toByteArray();
    }
    return pos-4<request.length?request[pos++-4]&255:-1;
   }
  };
 }
 public OutputStream getOutputStream(){return sent;}
 public void close(){if(accepted&&!impl.mFdCreatedInternally)throw new AssertionError("accepted fd leaked");closed=true;if(!accepted)gateCloses++;}
}''',
'android/net/LocalServerSocket.java':'''package android.net;import java.io.*;import java.util.concurrent.*;public class LocalServerSocket {
 public static volatile boolean failBind,closed;public static final BlockingQueue<LocalSocket> pending=new LinkedBlockingQueue<LocalSocket>();
 public LocalServerSocket(String address){if(failBind)throw new NoClassDefFoundError("private identity");if(!address.equals("ioniq5.cp.manual.v1"))throw new AssertionError();}
 public LocalSocket accept()throws IOException{LocalSocket s=pending.poll();if(s==null)throw new IOException("fixture end");return s;}
 public void close(){closed=true;}
}''',
'hmgapi/mods/wireless/dipo/Bridge.java':'''package hmgapi.mods.wireless.dipo;import java.util.*;import android.os.Handler;
final class Bridge {
 static final List<String> logs=Collections.synchronizedList(new ArrayList<String>());static volatile int attempts,controls,lastStatus;static volatile boolean failControl,failAttempt,failLog;
 static Handler handler(){return new Handler();}
 static void log(String m){if(failLog)throw new NoClassDefFoundError();logs.add(m);}
 static ManualWire.Result manualControl(String a,int action,int gate,long expiry){controls++;if(failControl)throw new NoClassDefFoundError("private path");return new ManualWire.Result(gate==0?1:gate,0);}
 static void manualAttempt(String a,long epoch,long request,int gate,long at,Runnable task){lastStatus=gate;attempts++;if(failAttempt)throw new NoClassDefFoundError("secret");if(gate==0)task.run();}
 static void manualFailure(String a,int status){lastStatus=status;}
}''',
'hmgapi/mods/wireless/dipo/ManualServiceTest.java':r'''package hmgapi.mods.wireless.dipo;
import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.concurrent.atomic.AtomicInteger;import android.net.*;import android.os.*;
public class ManualServiceTest {
 static int checks;static AtomicInteger uncaught=new AtomicInteger();
 static void ok(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 static void reset()throws Exception{
  LocalServerSocket.failBind=LocalServerSocket.closed=false;LocalServerSocket.pending.clear();LocalSocket.failGate=false;LocalSocket.gateStatus=0;LocalSocket.rootUid=0;Handler.fail=false;
  Bridge.failControl=Bridge.failAttempt=Bridge.failLog=false;Bridge.controls=Bridge.attempts=0;Bridge.logs.clear();
  Field f=ManualConnectServer.class.getDeclaredField("started");f.setAccessible(true);f.setBoolean(null,false);
  f=ManualDiagnostics.class.getDeclaredField("reported");f.setAccessible(true);f.setLong(null,0);
 }
 static void waitDone()throws Exception{long deadline=System.nanoTime()+2000000000L;while(System.nanoTime()<deadline){boolean found=false;for(Thread t:Thread.getAllStackTraces().keySet())if(t.isAlive()&&t.getName().equals("usbcp-manual-control"))found=true;if(!found)return;Thread.sleep(2);}throw new AssertionError("server did not terminate");}
 static void run()throws Exception{ManualConnectServer.start();waitDone();ok(uncaught.get()==0,"optional worker did not terminate app");}
 static int status(LocalSocket socket)throws Exception{DataInputStream in=new DataInputStream(new ByteArrayInputStream(socket.sent.toByteArray()));ok(in.readInt()==ManualWire.MAGIC,"challenge");in.readLong();in.readLong();return ManualWire.Result.read(in).status;}
 static boolean has(String value){return Bridge.logs.contains(value);}
 public static void main(String[] argv)throws Exception{
  Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler(){public void uncaughtException(Thread t,Throwable x){uncaught.incrementAndGet();}});
  reset();LocalServerSocket.failBind=true;run();ok(has("USB_CP_MANUAL event=server_failed kind=linkage"),"startup linkage is contained");
  reset();LocalSocket bad=LocalSocket.accepted(true),good=LocalSocket.accepted(false);LocalServerSocket.pending.add(bad);LocalServerSocket.pending.add(good);run();
  ok(has("USB_CP_MANUAL event=server_ready"),"server ready");ok(has("USB_CP_MANUAL event=request_failed kind=linkage"),"request linkage categorized");
  ok(status(good)==1&&Bridge.controls==1,"next valid request still served");ok(bad.closed&&good.closed&&LocalServerSocket.closed,"all accepted fds and server released");
  ok(good.timeout==600&&LocalSocket.gateRequests>0,"bounded peer and gate sockets");
  reset();good=LocalSocket.accepted(false);good.uid=0;LocalServerSocket.pending.add(good);run();ok(good.sent.size()==0&&Bridge.controls==0&&good.closed,"wrong UID rejected before challenge");
  reset();good=LocalSocket.accepted(false);LocalServerSocket.pending.add(good);LocalSocket.failGate=true;run();ok(status(good)==10&&has("USB_CP_MANUAL event=gate_failed kind=linkage"),"failed gate cannot authorize retry");
  reset();good=LocalSocket.accepted(false);LocalServerSocket.pending.add(good);LocalSocket.gateStatus=4;run();ok(status(good)==4,"power off remains denied");
  reset();good=LocalSocket.accepted(false);LocalServerSocket.pending.add(good);LocalSocket.rootUid=1000;run();ok(status(good)==10,"nonroot provider fails closed");
  reset();good=LocalSocket.accepted(false);LocalServerSocket.pending.add(good);Bridge.failControl=true;run();ok(status(good)==10&&has("USB_CP_MANUAL event=dispatch_failed kind=linkage"),"handler callback linkage contained");
  reset();good=LocalSocket.accepted(false);LocalServerSocket.pending.add(good);Handler.fail=true;run();ok(good.closed&&has("USB_CP_MANUAL event=request_failed kind=linkage"),"handler submission failure contained");
  reset();LocalSocket.failGate=true;ManualConnectServer.guard("fixture",1,1,new Runnable(){public void run(){throw new AssertionError("failed gate work ran");}});
  long end=System.nanoTime()+2000000000L;while(Bridge.attempts==0&&System.nanoTime()<end)Thread.sleep(2);ok(Bridge.attempts==1&&Bridge.lastStatus==10,"delayed gate fails closed");
  reset();for(int n=0;n<100;n++)ManualDiagnostics.failure("request_failed",new NoClassDefFoundError("secret"));ok(Bridge.logs.size()==1,"diagnostic bounded per category");
  for(String text:Bridge.logs)ok(!text.contains("secret")&&!text.contains("private"),"no raw throwable data");
  Bridge.failLog=true;ManualDiagnostics.failure("server_failed",new IOException("secret"));ok(uncaught.get()==0,"logging failure contained");
  System.out.println("PASS "+checks+" actual manual service/auth/FD/Error-containment checks; scripted Android runtime");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='neev-manual-service-') as temp:
    root=Path(temp);proc=root/'proc';(proc/'42').mkdir(parents=True)
    (proc/'42/cmdline').write_bytes(b'com.android.btsettings\0')
    for name in ('ManualConnectServer','ManualDiagnostics','ManualGate','ManualWire','ManualRetryState'):
        text=(DIPO/(name+'.java')).read_text()
        if name=='ManualWire':text=text.replace('new File("/proc")','new File('+json.dumps(str(proc))+')')
        FILES['hmgapi/mods/wireless/dipo/'+name+'.java']=text
    for name,text in FILES.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    jdk=java_bin(SOURCE)
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',root,*sorted(root.rglob('*.java'))],check=True)
    subprocess.run([jdk/'java','-cp',root,'hmgapi.mods.wireless.dipo.ManualServiceTest'],check=True)
print('PASS app-part/main wire, retry, gate and accepted-FD parity')
