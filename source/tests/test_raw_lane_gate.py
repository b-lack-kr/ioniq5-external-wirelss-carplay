"""Production Android adapter gates raw input immediately during native ownership takeover."""
from pathlib import Path
import subprocess,tempfile
from jvm_tools import java_bin
source=Path(__file__).resolve().parents[1]
files={
'android/os/Handler.java':r'''package android.os;
import java.util.*;
public class Handler {
 static final Map<String,ArrayDeque<Runnable>> queues=new HashMap<String,ArrayDeque<Runnable>>(); final String name;
 public Handler(Object looper){name=(String)looper;if(!queues.containsKey(name))queues.put(name,new ArrayDeque<Runnable>());}
 public boolean post(Runnable task){queues.get(name).add(task);return true;}
 public static void drain(String name){ArrayDeque<Runnable> q=queues.get(name);if(q!=null)while(!q.isEmpty())q.removeFirst().run();}
 public static int size(String name){ArrayDeque<Runnable> q=queues.get(name);return q==null?0:q.size();}
}''',
'android/os/HandlerThread.java':r'''package android.os;
public class HandlerThread {final String name; public HandlerThread(String n){name=n;}public void start(){}public Object getLooper(){return name;}}''',
'hmgapi/mods/wireless/dipo/RawGateTest.java':r'''package hmgapi.mods.wireless.dipo;
import android.os.Handler;
class Bridge {static Object sCp;static long epoch;static boolean sUsb,on=true;static long rawLaneEpoch(){return epoch;}static boolean enabled(){return on;}}
class CpTurns {boolean outputAllowed=true,auxiliaryAllowed=true;long generation;int received;
 boolean acceptsRawLanes(){return outputAllowed&&auxiliaryAllowed;}long laneGeneration(){return generation;}
 void fallbackLanes(CpLaneDecoder.Info info,long expected){if(acceptsRawLanes()&&generation==expected)received++;}}
class Dipo {static CpTurns turns;static Handler turnsHandler=new Handler("turns");static void log(String s){}}
public class RawGateTest {
 static int checks;static final String PARSER="hmgapi-cp-lanes";
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static void drain(){Handler.drain(PARSER);Handler.drain("turns");Handler.drain(PARSER);}
 static void active(){CpRawLanes.stop();drain();Bridge.sCp=new Object();Bridge.epoch++;Bridge.sUsb=false;Bridge.on=true;Dipo.turns=new CpTurns();CpRawLanes.start();drain();}
 public static void main(String[] args){byte[] packet=CpLaneObserverTest.frame(CpLaneObserverTest.control(9));
 active();CpRawLanes.observe(Bridge.sCp,packet,packet.length);check(Handler.size(PARSER)==1,"input queued");drain();check(Dipo.turns.received==1,"allowed input reaches fallback");
 active();Dipo.turns.auxiliaryAllowed=false;CpRawLanes.observe(Bridge.sCp,packet,packet.length);check(Dipo.turns.outputAllowed&&Handler.size(PARSER)==0,"immediate revoke suppresses input before handler end");drain();check(Dipo.turns.received==0,"revoked input not cached");
 active();CpRawLanes.observe(Bridge.sCp,packet,packet.length);Dipo.turns.auxiliaryAllowed=false;drain();check(Dipo.turns.received==0,"revoke suppresses queued parser input");
 active();CpRawLanes.observe(Bridge.sCp,packet,packet.length);Handler.drain(PARSER);check(Handler.size("turns")==1,"decoded result queued");Dipo.turns.auxiliaryAllowed=false;Handler.drain("turns");check(Dipo.turns.received==0,"revoke suppresses decoded queued output");
 Dipo.turns.generation++;Dipo.turns.auxiliaryAllowed=true;CpRawLanes.observe(Bridge.sCp,packet,packet.length);drain();check(Dipo.turns.received==1,"new route generation can resume");
 active();CpRawLanes.observe(Bridge.sCp,packet,packet.length);Handler.drain(PARSER);Dipo.turns.generation++;drain();check(Dipo.turns.received==0,"old generation cannot cross reset");
 active();CpRawLanes.observe(Bridge.sCp,packet,packet.length);Handler.drain(PARSER);Bridge.sUsb=true;drain();check(Dipo.turns.received==0,"USB takeover discards output");
 active();CpRawLanes.observe(Bridge.sCp,packet,packet.length);Handler.drain(PARSER);CpRawLanes.stop();drain();check(Dipo.turns.received==0,"stop invalidates all output");
 System.out.println("RawGateTest PASS checks="+checks);
 }
}'''}
jdk=java_bin(source)
with tempfile.TemporaryDirectory(prefix='neev-raw-lane-gate-') as scratch:
    root=Path(scratch)
    for name,body in files.items():
        dest=root/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(body)
    sources=[*root.rglob('*.java'),*[source/'mod/src/hmgapi/mods/wireless/dipo'/name for name in ('CpLaneDecoder.java','CpLaneObserver.java','CpRawLanes.java')],source/'tests/raw-lanes/CpLaneObserverTest.java']
    subprocess.run([jdk/'javac','--release','8','-encoding','UTF-8','-d',root,*sources],check=True)
    subprocess.run([jdk/'java','-cp',root,'hmgapi.mods.wireless.dipo.RawGateTest'],check=True)
