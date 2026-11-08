"""Exercise unchanged bridge unbond lifecycle methods extracted verbatim.

The harness models handler dispatch and iAP2 broadcasts, not native CarPlay teardown.
"""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source=Path(__file__).resolve().parents[1]
text=(source/'mod/src/hmgapi/mods/wireless/dipo/Bridge.java').read_text()
def method(signature):
    begin=text.index('    '+signature)
    at=text.index('{',begin);depth=1;end=at+1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[begin:end]
methods='\n'.join(method(s) for s in [
    'static synchronized long invalidatePending()',
    'static void onBondChanged(final BluetoothDevice device, final int state)',
    'static void resetPhone(String reason)',
    'static void sendDetach()'])
head=r'''import java.util.*;
public class BridgeForgetTest {
 static String sAddr,sScheduledAddr,sLastBtId;static Object sCp;
 static boolean sCpClient,sCpConnected,sCpWireless,sSession,sUsb;
 static long sEpoch,sRequestedBtEpoch,sConfirmedBtEpoch,sCpBtEpoch;
 static class CpRawLanes {static void stop(){}}
 static final class Decoder {void reset(){}}
 static final Decoder laneDecoder=new Decoder();
 static Object laneDecoderSession;
 static final ArrayList<byte[]> sPendingIn=new ArrayList<byte[]>();
 static int schedules;static Context context=new Context();
 static final String ACTION_DETACH="com.ev6.cpwl.BT_DETACHED",IAP2_PKG="com.tcc.iap2server";
 static final ArrayDeque<Runnable> jobs=new ArrayDeque<Runnable>();
 static class Handler {void post(Runnable r){jobs.add(r);}}
 static Handler handler(){return new Handler();}static void drain(){while(!jobs.isEmpty())jobs.remove().run();}
 static class BluetoothDevice {static final int BOND_NONE=10,BOND_BONDED=12;final String address;BluetoothDevice(String a){address=a;}String getAddress(){return address;}}
 static BluetoothDevice currentHfpBonded(String a){return null;}
 static void scheduleSppConnect(String a){schedules++;}
 static class Intent {static final int FLAG_RECEIVER_FOREGROUND=0x10000000;final String action;String pkg;int flags;Intent(String a){action=a;}void setPackage(String p){pkg=p;}void addFlags(int f){flags|=f;}}
 static class Context {ArrayList<Intent> sent=new ArrayList<Intent>();void sendBroadcast(Intent i){sent.add(i);}}
 static class CarPlayMedia {static int resets;static void reset(){resets++;}}
 static void log(String s){}static void error(String s,Throwable t){throw new AssertionError(s,t);}
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static void active(){jobs.clear();context=new Context();sAddr=sScheduledAddr=sLastBtId="phone-A";sCp=new Object();sCpClient=sCpConnected=sCpWireless=sSession=true;sUsb=false;sEpoch=sRequestedBtEpoch=sConfirmedBtEpoch=sCpBtEpoch=10;sPendingIn.clear();sPendingIn.add(new byte[]{1});CarPlayMedia.resets=schedules=0;}
'''
tail=r'''
 public static void main(String[] args){
  active();onBondChanged(new BluetoothDevice("phone-A"),BluetoothDevice.BOND_NONE);ok(sCpWireless);drain();
  ok(!sSession&&!sCpClient&&!sCpConnected&&!sCpWireless&&sCp==null&&sAddr==null&&sLastBtId==null&&sScheduledAddr==null);ok(sPendingIn.isEmpty()&&sEpoch==11&&sRequestedBtEpoch==-1&&sConfirmedBtEpoch==-1&&sCpBtEpoch==-1&&CarPlayMedia.resets==1&&schedules==0);ok(context.sent.size()==1);Intent sent=context.sent.get(0);ok(sent.action.equals(ACTION_DETACH)&&sent.pkg.equals(IAP2_PKG));
  onBondChanged(new BluetoothDevice("phone-A"),BluetoothDevice.BOND_NONE);drain();ok(context.sent.size()==1); // no repeated detach
  active();onBondChanged(new BluetoothDevice("other-phone"),BluetoothDevice.BOND_NONE);drain();ok(sCpWireless&&sEpoch==10&&context.sent.isEmpty()&&CarPlayMedia.resets==0);
  active();sScheduledAddr="next-phone";sLastBtId="next-phone";onBondChanged(new BluetoothDevice("next-phone"),BluetoothDevice.BOND_NONE);drain();ok(sCpWireless&&"phone-A".equals(sAddr)&&sScheduledAddr==null&&sLastBtId==null&&sEpoch==11&&context.sent.isEmpty());
  active();onBondChanged(null,BluetoothDevice.BOND_NONE);drain();ok(sCpWireless&&sEpoch==10&&context.sent.isEmpty());
  active();onBondChanged(new BluetoothDevice("phone-A"),BluetoothDevice.BOND_NONE);jobs.remove().run();ok(jobs.size()==1);invalidatePending();drain();ok(context.sent.isEmpty()); // stale detach cannot hit new epoch
  active();sUsb=true;onBondChanged(new BluetoothDevice("phone-A"),BluetoothDevice.BOND_NONE);drain();ok(sUsb&&context.sent.isEmpty()); // queued wireless detach never targets USB
  System.out.println("PASS 7 unchanged bridge unbond cases; state/pending work cleared, scoped detach requested, native teardown not simulated");
 }
}
'''
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-forget-lifecycle-') as tmp:
    java=Path(tmp)/'BridgeForgetTest.java';java.write_text(head+methods+tail)
    subprocess.run([jdk/'javac','--release','8','-d',tmp,java],check=True)
    subprocess.run([jdk/'java','-cp',tmp,'BridgeForgetTest'],check=True)
