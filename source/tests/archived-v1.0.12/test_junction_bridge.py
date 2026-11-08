"""Execute the production bridge tap and real lane decoder with queued lifecycle changes."""
from pathlib import Path
import subprocess
import tempfile
from jvm_tools import java_bin

source = Path(__file__).resolve().parents[1]
code = (source / 'mod/src/hmgapi/mods/wireless/dipo/Bridge.java').read_text()
def method(signature):
    begin = code.index('    ' + signature)
    end = code.index('{', begin) + 1
    depth = 1
    while depth:
        depth += (code[end] == '{') - (code[end] == '}')
        end += 1
    return code[begin:end]
methods = '\n'.join(method(s) for s in [
    'static synchronized long invalidatePending()',
    'static void onCpIapIn(Object cp, byte[] data, int len)',
    'private static void decodeJunctionLanes(final Object cp, byte[] bytes)'])
head = r'''package hmgapi.mods.wireless.dipo;
import java.util.*;
import java.io.*;
public class Bridge {
 static int checks,forwarded;static Object sCp,laneDecoderSession;static boolean sUsb,sCpClient,sCpConnected,on;
 static long sEpoch,sRequestedBtEpoch,sConfirmedBtEpoch,sCpBtEpoch,laneErrorAt;
 static String sScheduledAddr;static final int MAX_PENDING=64;
 static final CpLaneDecoder laneDecoder=new CpLaneDecoder();
 static final ArrayList<byte[]> sPendingIn=new ArrayList<byte[]>(),received=new ArrayList<byte[]>();
 static final ArrayDeque<Runnable> jobs=new ArrayDeque<Runnable>();
 static Runnable duringForward;
 static class CpTurns {final ArrayList<CpLaneDecoder.Info> evidence=new ArrayList<CpLaneDecoder.Info>();void rawLanes(CpLaneDecoder.Info i){evidence.add(i);}}
 static class Dipo {static CpTurns turns;static Object turnsHandler=new Object();static void post(Runnable r){jobs.add(r);}static void log(String s){}}
 interface IIap2Service {void Iap2SvcTransportReadDone(byte[] bytes,int len);}
 static IIap2Service sIap2=new IIap2Service(){public void Iap2SvcTransportReadDone(byte[] b,int len){
  forwarded++;received.add(b.clone());if(duringForward!=null){Runnable r=duringForward;duringForward=null;r.run();}}};
 static boolean enabled(){return on;}static void startCpClient(String reason){sCpClient=true;}
 static void flushPending(IIap2Service service){}static void log(String s){}
 static void error(String s,Throwable t){throw new AssertionError(s,t);}
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static void drain(){while(!jobs.isEmpty())jobs.remove().run();}
 static Object active(){invalidatePending();sCp=new Object();sUsb=false;sCpConnected=sCpClient=on=true;
  forwarded=0;received.clear();jobs.clear();sPendingIn.clear();Dipo.turns=new CpTurns();duringForward=null;return sCp;}
 static byte[] join(byte[]...parts){ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] p:parts)o.write(p,0,p.length);return o.toByteArray();}
 static byte[] words(int...values){ByteArrayOutputStream o=new ByteArrayOutputStream();for(int v:values){o.write(v>>8);o.write(v);}return o.toByteArray();}
 static byte[] tlv(int id,byte[] v){return join(words(v.length+4,id),v);}
 static byte[] frame(){
  byte[] lane=tlv(2,join(tlv(0,words(0)),tlv(1,new byte[]{1}),tlv(2,words(90)),tlv(3,words(90))));
  byte[] body=join(tlv(0,words(7)),tlv(1,words(9)),lane);
  byte[] msg=join(words(0x4040,body.length+6,0x5204),body), f=new byte[msg.length+10];
  f[0]=(byte)255;f[1]=0x5a;f[2]=(byte)(f.length>>8);f[3]=(byte)f.length;f[7]=10;
  int sum=0;for(int i=0;i<8;i++)sum+=f[i];f[8]=(byte)-sum;
  System.arraycopy(msg,0,f,9,msg.length);sum=0;for(byte b:msg)sum+=b;f[f.length-1]=(byte)-sum;return f;
 }
'''
tail = r'''
 public static void main(String[] ignored){
  byte[] f=frame(),original=f.clone();Object cp=active();onCpIapIn(cp,f,f.length);
  check(forwarded==1&&Arrays.equals(received.get(0),f)&&Arrays.equals(f,original),"transport forwards original bytes unchanged");
  check(Dipo.turns.evidence.isEmpty(),"observation runs only on queued TBT handler");drain();
  check(Dipo.turns.evidence.size()==1&&Dipo.turns.evidence.get(0).index==9,"one decoded event");
  for(int split=1;split<f.length;split++){
   cp=active();onCpIapIn(cp,Arrays.copyOfRange(f,0,split),split);drain();check(Dipo.turns.evidence.isEmpty(),"partial produces no event");
   onCpIapIn(cp,Arrays.copyOfRange(f,split,f.length),f.length-split);drain();
   check(Dipo.turns.evidence.size()==1&&forwarded==2,"split packets forward twice, decode once");
  }
  cp=active();onCpIapIn(cp,f,f.length);CpTurns old=Dipo.turns;invalidatePending();drain();check(old.evidence.isEmpty(),"queued old epoch discarded");
  cp=active();onCpIapIn(cp,f,f.length);old=Dipo.turns;sCp=new Object();drain();check(old.evidence.isEmpty(),"queued replaced session discarded without changing transport epoch");
  cp=active();onCpIapIn(cp,f,f.length);old=Dipo.turns;Dipo.turns=new CpTurns();drain();check(old.evidence.isEmpty()&&Dipo.turns.evidence.isEmpty(),"queued old TBT target discarded");
  cp=active();onCpIapIn(cp,f,f.length);sUsb=true;drain();check(Dipo.turns.evidence.isEmpty(),"queued data cannot cross USB takeover");
  cp=active();onCpIapIn(cp,f,f.length);on=false;drain();check(Dipo.turns.evidence.isEmpty(),"queued data cannot cross feature off");
  cp=active();sUsb=true;onCpIapIn(cp,f,f.length);check(forwarded==0&&jobs.isEmpty(),"USB data not tapped or forwarded as dongle");
  cp=active();int split=f.length/2;onCpIapIn(cp,Arrays.copyOfRange(f,0,split),split);
  sCp=new Object();Object replacement=sCp;onCpIapIn(cp,Arrays.copyOfRange(f,split,f.length),f.length-split);
  onCpIapIn(replacement,f,f.length);drain();check(Dipo.turns.evidence.size()==1,"new object resets old incomplete parser state");
  cp=active();duringForward=new Runnable(){public void run(){invalidatePending();sCp=new Object();}};
  onCpIapIn(cp,Arrays.copyOfRange(f,0,split),split);replacement=sCp;onCpIapIn(replacement,f,f.length);drain();
  check(Dipo.turns.evidence.size()==1,"session replacement during Binder forward cannot contaminate new parser");
  cp=active();byte[] bad=f.clone();bad[bad.length-1]++;onCpIapIn(cp,bad,bad.length);drain();
  check(forwarded==1&&Dipo.turns.evidence.isEmpty(),"bad observation still forwards exact transport packet");
  System.out.println("JunctionBridgeTest PASS checks="+checks);
 }
}
'''
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-junction-bridge-') as temp:
    root=Path(temp)
    java=root/'Bridge.java';java.write_text(head+methods+tail)
    clock=root/'android/os/SystemClock.java';clock.parent.mkdir(parents=True)
    clock.write_text('package android.os;public final class SystemClock {public static long elapsedRealtime(){return 30000L;}}')
    subprocess.run([jdk/'javac','--release','8','-d',root,java,clock,
        source/'mod/src/hmgapi/mods/wireless/dipo/CpLaneDecoder.java'],check=True)
    subprocess.run([jdk/'java','-cp',root,'hmgapi.mods.wireless.dipo.Bridge'],check=True)
