"""Exercise actual fixed wire/state classes and production Bridge control methods.
No sockets to a car, no native Bluetooth calls, no radio or power changes.
"""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin
SOURCE=Path(__file__).resolve().parents[1]
BRIDGE=(SOURCE/'mod/src/hmgapi/mods/wireless/dipo/Bridge.java').read_text()
def method(signature):
    start=BRIDGE.index('    '+signature)
    brace=BRIDGE.index('{',start);end=brace+1;depth=1
    while depth:
        depth+=(BRIDGE[end]=='{')-(BRIDGE[end]=='}');end+=1
    return BRIDGE[start:end]
methods='\n'.join(method(sig) for sig in [
 'static BluetoothDevice currentHfpBonded(String addr)',
 'static BluetoothDevice currentBondedPhone(String addr)',
 'static int appleIdentity(BluetoothDevice d)',
 'static boolean isApple(BluetoothDevice d)',
 'static void scheduleSppConnect(final String addr)',
 'static synchronized void scheduleSppConnect(final String addr, final int attempt)',
 'static void attemptSppConnect(final String addr, final int attempt, final long epoch)',
 'static ManualWire.Result manualControl(String address, int action, int gate)',
 'static ManualWire.Result manualControl(String address, int action, int gate, long deadline)',
 'static void manualFailure(String address, int status)',
 'static void manualAttempt(String address, long epoch, long request, int gate, long observed, Runnable work)',
 'static void onSppUp(final String addr)',
 'static void onSppUpAuthorized(final String addr)'])
head=r'''
package hmgapi.mods.wireless.dipo;
import java.io.*;import java.nio.file.*;import java.util.*;
public class ManualCarplayTest {
 static int checks;static void ok(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static final String A="12:34:56:78:9A:BC",B="24:34:56:78:9A:BC",IAP2_PKG="com.tcc.iap2server",ACTION_ATTACH="BT_ATTACHED";
 static final int APPLE_VENDOR_ID=76,COMPONENT_BT_CARPLAY=4;
 static final long SPP_CONNECT_DELAY_MS=3000;
 static class Bridge {}
 static class SystemClock {static long now=100000;static long elapsedRealtime(){return now;}}
 static class Job {long at;Runnable r;Job(long a,Runnable x){at=a;r=x;}}
 static final List<Job> jobs=new ArrayList<Job>();
 static class Handler {void post(Runnable r){postDelayed(r,0);}void postDelayed(Runnable r,long ms){jobs.add(new Job(SystemClock.now+ms,r));}}
 static Handler handler(){return new Handler();}
 static void advance(long delta){SystemClock.now+=delta;for(int n=0;n<100;n++){Job found=null;for(Job j:jobs)if(j.at<=SystemClock.now){found=j;break;}if(found==null)return;jobs.remove(found);found.r.run();}throw new AssertionError("loop");}
 public static class BluetoothDevice {
  static final int BOND_BONDED=12;String address;int bond=12,vendor=76;
  BluetoothDevice(String a){address=a;}String getAddress(){return address;}int getBondState(){return bond;}public int getVendor(){return vendor;}String getName(){return null;}
 }
 static class HfDeviceBTCMManager {
  BluetoothDevice phone;boolean spp,accept=false;int connects;
  BluetoothDevice IbtcmGetHfpConnectedDevice(){return phone;}
  boolean IbtcmIsSppConnectedDevice(String a){return spp;}
  boolean IbtcmSppConnect(String a){connects++;return accept;}
 }
 static class Intent {
  static final int FLAG_RECEIVER_FOREGROUND=1;
  Intent(String a){}void setPackage(String p){}void addFlags(int f){}void putExtra(String k,int v){}void putExtra(String k,String v){}
 }
 static class Context {int attached;void sendBroadcast(Intent i){attached++;}}
 static Context context;
 static HfDeviceBTCMManager sBtcm;
 static String sAddr,sScheduledAddr,manualSppAddress;
 static long sEpoch,sRequestedBtEpoch,manualSppRequest,manualAuthorizationUntil,btAttachStartedAt;
 static final long STALE_BT_ATTACH_MS=30000;
 static Object sCp,sIap2;
 static boolean sUsb,sCpWireless,sCpClient,sCpConnected,sSession,on,ap;
 static ManualRetryState manual;
 static int resets,errors;
 static boolean enabled(){return on;}static boolean apReady(){return ap;}
 static synchronized long invalidatePending(){sScheduledAddr=null;return ++sEpoch;}
 static void resetPhone(String reason){resets++;sSession=false;sAddr=null;invalidatePending();}
 static void log(String line){ok(!line.contains(A)&&!line.contains(B),"no identity in new diagnostics");}
 static void error(String label,Throwable e){errors++;throw new AssertionError(label,e);}
 static final class Guard {String address;long epoch,request;Runnable task;Guard(String a,long e,long r,Runnable t){address=a;epoch=e;request=r;task=t;}}
 static class ManualConnectServer {
  static final List<Guard> gates=new ArrayList<Guard>();
  static void guard(String a,long e,long r,Runnable t){gates.add(new Guard(a,e,r,t));}
  static void complete(int status){Guard g=gates.remove(0);manualAttempt(g.address,g.epoch,g.request,status,SystemClock.now,g.task);}
 }
 static void fresh(){jobs.clear();ManualConnectServer.gates.clear();SystemClock.now+=40000;sBtcm=new HfDeviceBTCMManager();sBtcm.phone=new BluetoothDevice(A);sAddr=sScheduledAddr=manualSppAddress=null;sEpoch++;sRequestedBtEpoch=-1;sUsb=sCpWireless=sCpClient=sCpConnected=sSession=false;btAttachStartedAt=0;sCp=null;sIap2=new Object();on=ap=true;manual=new ManualRetryState();context=new Context();resets=errors=0;}
 static ManualWire.Result query(String a){return manualControl(a,ManualWire.QUERY,ManualWire.READY);}
 static ManualWire.Result retry(String a){return manualControl(a,ManualWire.RETRY,ManualWire.READY);}
'''
tail=r'''
 static void bridgeCases(){
  fresh();ok(query(A).status==ManualWire.READY,"current selected phone ready");
  ok(query(B).status==ManualWire.PHONE_REQUIRED,"different phone requires OEM HFP selection");
  for(int gate:new int[]{4,5,10}){ok(manualControl(A,1,gate).status==gate,"read-only gate fail closed");ok(jobs.isEmpty(),"gate never schedules");}
  on=false;ok(retry(A).status==ManualWire.DISABLED,"disabled mod");on=true;
  sBtcm.phone.bond=10;ok(retry(A).status==ManualWire.PHONE_REQUIRED,"unbonded denied");sBtcm.phone.bond=12;
  sBtcm.phone.vendor=75;ok(retry(A).status==ManualWire.NOT_APPLE,"other vendor denied");sBtcm.phone.vendor=76;
  sUsb=true;ok(retry(A).status==ManualWire.BUSY,"USB session preserved");sUsb=false;
  sCp=new Object();sCpWireless=true;sAddr=A;
  ok(retry(A).status==ManualWire.CONNECTED,"same actual CP already connected");
  ok((query(B).flags&ManualWire.ACTIVE_CP)!=0&&query(B).status==ManualWire.BUSY,"other active CP blocks before HFP switch");
  ok(resets==0&&jobs.isEmpty(),"active CP never reset");
  fresh();ok(retry(A).status==ManualWire.ACCEPTED,"request accepted");
  ok((query(A).flags&ManualWire.PENDING_TARGET)!=0&&query(A).status==1,"pending is not connected");
  ok(retry(A).status==1&&jobs.size()==1,"duplicate click deduplicated");
  advance(3000);ok(sBtcm.connects==0&&ManualConnectServer.gates.size()==1,"SPP waits for fresh gate");
  ManualConnectServer.complete(ManualWire.POWER_OFF);ok(sBtcm.connects==0&&query(A).status==10,"past OFF cannot impersonate current closed gate");
  ok(resets==0,"OFF does not manufacture teardown or ON");
  ok(manualControl(A,0,4).status==4,"actual OFF gate remains blocked");
  ok(retry(A).status==1,"after actual gate recovery new click can retry");
  fresh();retry(A);advance(3000);ManualConnectServer.complete(6);
  ok(query(A).status==1,"AP preparation remains pending while unavailable");
  fresh();retry(A);advance(3000);sBtcm.phone=new BluetoothDevice(B);ManualConnectServer.complete(0);
  ok(sBtcm.connects==0&&query(A).status==7,"selected HFP changed before attempt");
  fresh();retry(A);advance(3000);sCp=new Object();sCpWireless=true;sAddr=B;ManualConnectServer.complete(0);
  ok(sBtcm.connects==0&&resets==0,"new active session race preserved");
  fresh();retry(A);advance(3000);sEpoch++;ManualConnectServer.complete(0);ok(sBtcm.connects==0,"old bridge epoch cannot start");
  fresh();retry(A);advance(3000);Guard delayed=ManualConnectServer.gates.remove(0);manualAttempt(A,delayed.epoch,delayed.request,0,SystemClock.now-351,delayed.task);
  ok(sBtcm.connects==0&&query(A).status==9,"stalled handler authorization expires");
  fresh();retry(A);advance(30001);ok(sBtcm.connects==0&&ManualConnectServer.gates.isEmpty(),"expired delayed retry never becomes automatic");
  ok(query(A).status==9,"timeout result retained");ok(retry(A).status==1,"new user click may retry timeout");
  fresh();retry(A);for(int n=0;n<5;n++){advance(3000);ok(ManualConnectServer.gates.size()==1,"one attempt gate");ManualConnectServer.complete(0);}
  ok(sBtcm.connects==5&&query(A).status==10&&jobs.isEmpty(),"bounded five rejected attempts");
  fresh();sBtcm.accept=true;retry(A);advance(3000);ManualConnectServer.complete(0);
  ok(sBtcm.connects==1,"one SPP accepted");onSppUp(A);ok(context.attached==0,"SPP callback waits for fresh gate");
  ManualConnectServer.complete(4);advance(0);ok(context.attached==0&&resets==0,"OFF at SPP completion blocks iAP2 attach");
  fresh();sBtcm.accept=true;retry(A);advance(3000);ManualConnectServer.complete(0);
  onSppUp(A);onSppUp(A);ok(ManualConnectServer.gates.size()==2,"duplicate completion can race");
  ManualConnectServer.complete(0);ManualConnectServer.complete(0);advance(0);
  ok(query(A).status==1&&context.attached==1,"duplicate completion keeps same in-flight request");
  fresh();sBtcm.spp=true;retry(A);advance(3000);ManualConnectServer.complete(0);
  ok(ManualConnectServer.gates.size()==1&&context.attached==0,"already connected SPP still checks callback gate");
  ManualConnectServer.complete(0);advance(0);ok(context.attached==1&&sSession,"authorized original iAP2 path");
  ok(query(A).status==1,"SPP/iAP2 acceptance not reported as actual CP");
  onSppUp(A);ok(ManualConnectServer.gates.isEmpty(),"duplicate SPP after attach ignored");
  sCp=new Object();sCpWireless=true;ok(query(A).status==2,"actual CP proof completes");
  sCp=null;sCpWireless=sCpClient=sSession=false;ok(query(A).status==0,"old completed request cannot fake live CP");
  fresh();sBtcm.accept=true;retry(A);advance(3000);ManualConnectServer.complete(0);advance(30000);onSppUp(A);
  ok(ManualConnectServer.gates.isEmpty()&&context.attached==0,"late callback after timeout ignored");
  fresh();sIap2=null;
  ok(retry(A).status==ManualWire.ACCEPTED,"cold service waits for iAP binding");
  ManualConnectServer.complete(0);advance(1000);ManualConnectServer.complete(0);
  ok(query(A).status==ManualWire.ACCEPTED&&sBtcm.connects==0,"iAP binding delay never issues SPP early");
  sIap2=new Object();advance(1000);ManualConnectServer.complete(0);advance(3000);ManualConnectServer.complete(0);
  ok(sBtcm.connects==1,"cold service continues same click after iAP binds");
  fresh();ap=false;
  ok(manualControl(A,0,6).status==6&&ManualConnectServer.gates.isEmpty(),"passive unavailable AP never prepares");
  ok(manualControl(A,1,6).status==1&&ManualConnectServer.gates.size()==1,"click prepares unavailable AP");
  ok(manualControl(A,0,6).status==1,"preparing query remains accepted");
  ManualConnectServer.complete(6);advance(1000);
  ok(sBtcm.connects==0&&ManualConnectServer.gates.size()==1,"wait for AP without SPP request");
  ap=true;ManualConnectServer.complete(0);advance(3000);ManualConnectServer.complete(0);
  ok(sBtcm.connects==1,"ready AP resumes original SPP path");
  fresh();ap=false;manualControl(A,1,6);ManualConnectServer.complete(0);
  ok(manualControl(A,0,0).status==1&&sBtcm.connects==0,"provider ready waits for local AP cache");
  advance(1000);ManualConnectServer.complete(0);ap=true;advance(1000);ManualConnectServer.complete(0);
  advance(3000);ManualConnectServer.complete(0);
  ok(sBtcm.connects==1,"same request survives local snapshot propagation delay");
  fresh();ap=false;manualControl(A,1,6);ManualConnectServer.complete(4);advance(1000);
  ok(sBtcm.connects==0&&ManualConnectServer.gates.isEmpty(),"OFF cancels preparation");
  fresh();ap=false;manualControl(A,1,6);ManualConnectServer.complete(6);advance(31000);
  ok(sBtcm.connects==0&&ManualConnectServer.gates.isEmpty(),"preparation expires without looping");
  fresh();ap=false;
  ok(manualControl(B,0,6).status==ManualWire.PHONE_REQUIRED,"unavailable AP does not block OEM HFP selection");
  ok(manualControl(B,1,6).status==ManualWire.PHONE_REQUIRED&&ManualConnectServer.gates.isEmpty(),"unselected phone cannot queue bridge preparation");
  fresh();sSession=true;sAddr=A;btAttachStartedAt=SystemClock.now-30001;
  ok(query(A).status==ManualWire.READY&&resets==0,"passive query offers retry without resetting stale attach");
  ok(retry(B).status==ManualWire.BUSY&&resets==0,"other selected phone cannot reset attach");
  ok(retry(A).status==ManualWire.ACCEPTED&&resets==1&&jobs.size()==1,"explicit same phone retries stale incomplete attach");
  fresh();sSession=true;sAddr=A;btAttachStartedAt=SystemClock.now-29999;
  ok(retry(A).status==ManualWire.BUSY&&resets==0,"initial handshake still in progress");
  fresh();sSession=true;sAddr=A;btAttachStartedAt=SystemClock.now-40000;sCpConnected=true;
  ok(retry(A).status==ManualWire.BUSY&&resets==0,"confirmed iAP session protected");
  fresh();sSession=true;sAddr=A;btAttachStartedAt=SystemClock.now-40000;sIap2=null;
  ok(retry(A).status==ManualWire.BUSY&&resets==0,"no teardown without iAP bridge");
  fresh();scheduleSppConnect(A);advance(3000);ok(sBtcm.connects==1&&ManualConnectServer.gates.isEmpty(),"ordinary automatic path unchanged");
  fresh();scheduleSppConnect(A);retry(A);advance(3000);ok(ManualConnectServer.gates.size()==1&&sBtcm.connects==0,"manual adopts existing scheduled attempt safely");
 }
 static void wireCases()throws Exception {
  byte[] phone=ManualWire.address(A);ok(A.equals(ManualWire.format(phone)),"six-byte roundtrip");
  for(String bad:new String[]{"","12:34:56:78:9A","00:00:00:00:00:00","FF:FF:FF:FF:FF:FF","12:34:56:78:9A:BX"}){
   try{ManualWire.address(bad);throw new AssertionError("bad address accepted");}catch(IllegalArgumentException expected){checks++;}
  }
  ByteArrayOutputStream out=new ByteArrayOutputStream();new ManualWire.Request(1,123,phone).write(new DataOutputStream(out),4,5);
  byte[] raw=out.toByteArray();ok(raw.length==31,"fixed request length");
  ManualWire.Request decoded=ManualWire.Request.read(new DataInputStream(new ByteArrayInputStream(raw)),4,5);
  ok(decoded.action==1&&decoded.sequence==123&&Arrays.equals(phone,decoded.address),"request exact payload");
  for(int n=0;n<raw.length;n++)try{ManualWire.Request.read(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(raw,n))),4,5);throw new AssertionError("truncation accepted");}catch(IOException expected){checks++;}
  try{ManualWire.Request.read(new DataInputStream(new ByteArrayInputStream(raw)),4,6);throw new AssertionError("old server nonce");}catch(IOException expected){checks++;}
  raw[16]=2;try{ManualWire.Request.read(new DataInputStream(new ByteArrayInputStream(raw)),4,5);throw new AssertionError("power command reuse");}catch(IOException expected){checks++;}
  ManualWire.ReplayWindow window=new ManualWire.ReplayWindow();
  ok(window.accept(100,100),"first request sequence");
  ok(!window.accept(100,101)&&!window.accept(99,101),"replay/out-of-order denied");
  ok(!window.accept(102,101),"future sequence denied");
  ok(!window.accept(101,3000000102L),"stale delayed request denied");
  ok(window.accept(3000000102L,3000000103L),"later fresh request allowed");
  Path proc=Files.createTempDirectory("manual-peers");Path process=proc.resolve("42");Files.createDirectory(process);
  try {
   Files.write(process.resolve("cmdline"),(ManualWire.UI+"\0").getBytes("US-ASCII"));
   ok(ManualWire.peer(1000,42,ManualWire.UI,proc.toFile()),"exact UI UID1000");
   ok(!ManualWire.peer(0,42,ManualWire.UI,proc.toFile()),"root is not UI authority");
   ok(!ManualWire.peer(1000,42,ManualWire.DIPO,proc.toFile()),"UI cannot be DiPO");
   Files.write(process.resolve("cmdline"),(ManualWire.UI+":child\0").getBytes("US-ASCII"));
   ok(!ManualWire.peer(1000,42,ManualWire.UI,proc.toFile()),"suffix process rejected");
   Files.write(process.resolve("cmdline"),ManualWire.UI.getBytes("US-ASCII"));
   ok(!ManualWire.peer(1000,42,ManualWire.UI,proc.toFile()),"unterminated cmdline rejected");
  }finally{Files.deleteIfExists(process.resolve("cmdline"));Files.delete(process);Files.delete(proc);}
  ManualRetryState state=new ManualRetryState();ok(state.begin(A,1000),"begin");ok(!state.begin(B,1001),"single pending");
  long token=state.generation();ok(state.status(A,31000)==9&&state.generation()!=token,"timeout fences callbacks");
  ok(state.begin(B,31001),"future selection allowed");state.finish(B,10);ok(!state.begin(B,31002),"retry rate limited");
 }
 public static void main(String[] a)throws Exception{wireCases();bridgeCases();System.out.println("PASS "+checks+" manual wire/state/production bridge checks");}
}
'''
jdk=java_bin(SOURCE)
with tempfile.TemporaryDirectory(prefix='neev-manual-control-') as tmp:
    root=Path(tmp);f=root/'ManualCarplayTest.java';f.write_text(head+methods+tail)
    sources=[SOURCE/'mod/src/hmgapi/mods/wireless/dipo'/n for n in ['ManualWire.java','ManualRetryState.java']]
    subprocess.run([jdk/'javac','--release','8','-d',root,*sources,f],check=True)
    subprocess.run([jdk/'java','-cp',root,'hmgapi.mods.wireless.dipo.ManualCarplayTest'],check=True)
