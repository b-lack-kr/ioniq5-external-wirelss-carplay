"""Execute the unchanged transport data path with a post-forward optional observer.

Actual observer scheduling/fragment/pressure behavior is tested separately.
"""
from pathlib import Path
import subprocess,tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
code=(source/'mod/src/hmgapi/mods/wireless/dipo/Bridge.java').read_text()
assert 'decodeJunctionLanes' not in code and 'CpLaneDecoder' not in code

def method(signature):
 begin=code.index('    '+signature);end=code.index('{',begin)+1;depth=1
 while depth:
  depth+=(code[end]=='{')-(code[end]=='}');end+=1
 return code[begin:end]
methods='\n'.join(method(s) for s in ['static synchronized long invalidatePending()','static void onCpIapIn(Object cp, byte[] data, int len)'])
head='''package hmgapi.mods.wireless.dipo;
import java.util.*;
public class Bridge{
 static int checks,forwards,observations;static boolean observerFail;static Object sCp;static boolean sUsb,sCpClient,sCpConnected;
 static long sEpoch,sRequestedBtEpoch,sConfirmedBtEpoch,sCpBtEpoch;static String sScheduledAddr;
 static final int MAX_PENDING=64;static final List<byte[]> sPendingIn=new ArrayList<byte[]>(),received=new ArrayList<byte[]>();
 interface IIap2Service{void Iap2SvcTransportReadDone(byte[] b,int len);}
 static IIap2Service sIap2=new IIap2Service(){public void Iap2SvcTransportReadDone(byte[] b,int len){forwards++;received.add(b.clone());}};
 static void startCpClient(String reason){sCpClient=true;}static void log(String s){}
 static void error(String s,Throwable t){throw new AssertionError(s,t);}
 static void flushPending(IIap2Service service){for(byte[] b:new ArrayList<byte[]>(sPendingIn))service.Iap2SvcTransportReadDone(b,b.length);sPendingIn.clear();}
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static class CpRawLanes {
  static void stop(){}
  static void observe(Object cp,byte[] b,int len){check(forwards>0||!sPendingIn.isEmpty(),"transport precedes observer");observations++;if(observerFail)throw new IllegalStateException("optional observer");}
 }
 static Object active(){observations=0;observerFail=false;sCp=new Object();sUsb=false;sCpConnected=sCpClient=true;forwards=0;received.clear();sPendingIn.clear();return sCp;}
'''
tail='''
 public static void main(String[] ignored){
 byte[] data={1,2,3,4,5};Object cp=active();onCpIapIn(cp,data,3);check(Arrays.equals(received.get(0),new byte[]{1,2,3}),"requested byte range forwarded exactly");
 check(Arrays.equals(data,new byte[]{1,2,3,4,5}),"caller input unchanged");data[0]=9;check(received.get(0)[0]==1,"transport owns copy");
 cp=active();onCpIapIn(cp,data,100);check(Arrays.equals(received.get(0),data),"oversized length clamped");
 cp=active();onCpIapIn(new Object(),data,5);check(forwards==0,"old session ignored");
 onCpIapIn(cp,null,5);onCpIapIn(cp,data,0);onCpIapIn(cp,data,-1);check(forwards==0,"invalid input ignored");
 sUsb=true;onCpIapIn(cp,data,5);check(forwards==0,"USB transport not duplicated");
 cp=active();sCpConnected=false;sCpClient=false;
 for(int i=0;i<100;i++)onCpIapIn(cp,new byte[]{(byte)i},1);
 check(sCpClient&&forwards==0&&sPendingIn.size()==64,"connection startup buffers bounded64");
 sCpConnected=true;onCpIapIn(cp,new byte[]{100},1);check(forwards==65&&sPendingIn.isEmpty(),"buffer flush precedes fresh packet");
 for(int i=0;i<64;i++)check(received.get(i)[0]==(byte)i,"buffer preserves order");
 check(received.get(64)[0]==100,"latest packet sent after buffered");
 cp=active();observerFail=true;onCpIapIn(cp,data,data.length);check(forwards==1&&observations==1,"observer exception cannot interrupt transport");
 long before=sEpoch;invalidatePending();check(sEpoch==before+1&&sScheduledAddr==null&&sRequestedBtEpoch==-1&&sConfirmedBtEpoch==-1&&sCpBtEpoch==-1,"epoch invalidation preserved");
 System.out.println("BaselineBridgeTest PASS checks="+checks+" (transport-first observer, no inline parser)");
 }
}
'''
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-baseline-bridge-') as tmp:
 root=Path(tmp);java=root/'Bridge.java';java.write_text(head+methods+tail)
 subprocess.run([jdk/'javac','--release','8','-d',root,java],check=True)
 subprocess.run([jdk/'java','-cp',root,'hmgapi.mods.wireless.dipo.Bridge'],check=True)
