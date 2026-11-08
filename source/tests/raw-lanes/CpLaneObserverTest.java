package hmgapi.mods.wireless.dipo;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

public final class CpLaneObserverTest {
    static int checks;
    static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
    static final class Queue implements CpLaneObserver.Runner {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<Runnable>();
        public boolean post(Runnable task) { tasks.add(task); return true; }
        void drain() { int count=0; while(!tasks.isEmpty()){if(++count>10000)throw new AssertionError("unbounded work");tasks.removeFirst().run();} }
    }
    static class Sink implements CpLaneObserver.Sink {
        final ArrayList<CpLaneDecoder.Info> received=new ArrayList<CpLaneDecoder.Info>();
        Object currentOwner="session"; int problems; boolean fail;
        final LinkedHashMap<String,Integer> metrics=new LinkedHashMap<String,Integer>();
        public boolean current(Object owner){return owner.equals(currentOwner);}
        public void accept(Object owner,CpLaneDecoder.Info info){if(fail)throw new IllegalStateException();received.add(info);}
        public void problem(String kind,int count){Integer old=metrics.get(kind);metrics.put(kind,(old==null?0:old)+count);if(kind.equals("decode")||kind.endsWith("failed"))problems+=count;}
    }
    static final class Test {
        final Queue parse=new Queue(),deliver=new Queue(); final Sink sink=new Sink();
        final CpLaneObserver observer=new CpLaneObserver(parse,deliver,sink);
        void offer(byte[] b){observer.offer(sink.currentOwner,b,b.length);}
        void drain(){parse.drain();deliver.drain();}
    }
    static byte[] join(byte[]... parts) {ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p,0,p.length);return b.toByteArray();}
    static byte[] words(int... values){ByteArrayOutputStream b=new ByteArrayOutputStream();for(int v:values){b.write(v>>8);b.write(v);}return b.toByteArray();}
    static byte[] tlv(int id,byte[] v){return join(words(v.length+4,id),v);}
    static byte[] control(int index){byte[] lane=tlv(2,join(tlv(0,words(0)),tlv(1,new byte[]{2}),tlv(2,words(0,90)),tlv(3,words(90))));byte[] body=join(tlv(0,words(7)),tlv(1,words(index)),lane);return join(words(0x4040,body.length+6,0x5204),body);}
    static byte[] frame(byte[] payload){byte[] b=new byte[payload.length+10];b[0]=(byte)255;b[1]=0x5a;b[2]=(byte)(b.length>>8);b[3]=(byte)b.length;b[7]=10;int sum=0;for(int i=0;i<8;i++)sum+=b[i];b[8]=(byte)-sum;System.arraycopy(payload,0,b,9,payload.length);sum=0;for(byte x:payload)sum+=x;b[b.length-1]=(byte)-sum;return b;}
    public static void main(String[] args)throws Exception {
        byte[] good=frame(control(9));
        Test readiness=new Test();readiness.observer.start();check(readiness.sink.metrics.isEmpty(),"readiness logging is not inline");readiness.parse.drain();
        check(readiness.sink.metrics.get("ready")==1&&readiness.sink.received.isEmpty(),"ready evidence without input");
        Test t=new Test();byte[] copy=good.clone();t.offer(copy);Arrays.fill(copy,(byte)0);
        check(t.sink.received.isEmpty()&&t.parse.tasks.size()==1,"offer does not parse or deliver inline");
        t.parse.drain();check(t.sink.received.isEmpty()&&t.deliver.tasks.size()==1,"delivery is independently queued");t.deliver.drain();
        check(t.sink.received.size()==1&&t.sink.received.get(0).index==9,"immutable private input copy");
        t.parse.drain();check(t.sink.metrics.get("decoded")==1&&t.sink.metrics.get("delivered")==1,"numeric decode and delivery evidence from worker");
        for(int split=1;split<good.length;split++) {
            t=new Test();t.offer(Arrays.copyOfRange(good,0,split));t.drain();check(t.sink.received.isEmpty(),"partial frame waits");
            t.offer(Arrays.copyOfRange(good,split,good.length));t.drain();check(t.sink.received.size()==1,"fragment continuation");
        }
        byte[] ctl=control(10);int middle=ctl.length/2;t=new Test();t.offer(frame(Arrays.copyOfRange(ctl,0,middle)));t.drain();t.offer(frame(Arrays.copyOfRange(ctl,middle,ctl.length)));t.drain();check(t.sink.received.size()==1,"control fragments across link frames");
        t=new Test();t.offer(join(frame(control(1)),frame(control(2))));t.drain();check(t.sink.received.size()==2,"coalesced frames retain distinct indices");
        t=new Test();t.offer(Arrays.copyOfRange(good,0,12));t.parse.drain();for(int i=0;i<65;i++)t.offer(new byte[]{0});t.offer(good);
        check(!t.sink.metrics.containsKey("overflow"),"offer does not emit overflow logs");
        check(t.observer.pendingChunks()<=CpLaneObserver.MAX_CHUNKS&&t.observer.pendingBytes()<=CpLaneObserver.MAX_BYTES&&t.parse.tasks.size()==1,"chunk queue and tasks bounded");t.drain();check(t.sink.received.size()==1,"queue overflow resets partial decoder and resyncs");check(t.sink.metrics.get("overflow")==1,"overflow count emitted by parser worker");
        t=new Test();for(int i=0;i<8;i++)t.offer(new byte[60000]);t.offer(good);check(t.observer.pendingBytes()<=CpLaneObserver.MAX_BYTES,"byte queue bounded");t.drain();check(t.sink.received.size()==1,"byte overflow resync");
        t=new Test();t.offer(Arrays.copyOfRange(good,0,12));t.parse.drain();check(!t.observer.offer("session",new byte[65536],65536),"oversized observer input dropped");t.offer(good);t.drain();check(t.sink.received.size()==1,"oversized drop resets partial decoder");
        t=new Test();t.offer(Arrays.copyOfRange(good,0,12));t.parse.drain();t.sink.currentOwner="next-session";t.offer(good);t.drain();check(t.sink.received.size()==1,"new epoch never joins old fragment");
        t=new Test();t.offer(good);t.parse.drain();t.sink.currentOwner="next-session";t.deliver.drain();check(t.sink.received.isEmpty(),"queued output rechecks session");
        t=new Test();t.offer(good);t.parse.drain();t.observer.close();t.drain();check(t.sink.received.isEmpty()&&!t.observer.offer("session",good,good.length)&&t.observer.pendingBytes()==0&&t.observer.pendingResults()==0,"shutdown drops queue, output, and future input");check(t.sink.metrics.get("closed")==1,"close evidence emitted by parser worker");
        t=new Test();byte[] bad=good.clone();bad[bad.length-1]++;t.offer(join(bad,good));t.drain();check(t.sink.problems>0&&t.sink.received.size()==1,"malformed checksum isolates and recovers");
        t=new Test();byte[] invalid=control(3);invalid[6]=invalid[7]=0;t.offer(join(frame(invalid),good));t.drain();check(t.sink.problems>0&&t.sink.received.size()==1,"malformed TLV isolates and recovers");
        t=new Test();for(int i=0;i<100;i++){t.offer(frame(control(i)));t.parse.drain();}
        check(t.deliver.tasks.size()==1&&t.observer.pendingResults()==32,"latest-key output cache and handler task bounded");
        t.deliver.tasks.removeFirst().run();check(t.sink.received.size()==1&&t.deliver.tasks.size()==1,"one record per destination handler turn");t.deliver.drain();check(t.sink.received.size()==32&&t.sink.received.get(0).index==68&&t.sink.received.get(31).index==99,"newest evidence survives bounded eviction");
        t=new Test();for(int i=0;i<100;i++){t.offer(good);t.parse.drain();}check(t.observer.pendingResults()==1&&t.deliver.tasks.size()==1,"same key coalesces");
        t.sink.fail=true;t.deliver.drain();t.sink.fail=false;t.offer(good);t.drain();check(t.sink.received.size()==1,"optional delivery exception does not stop later packets");
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final ExecutorService service=Executors.newSingleThreadExecutor();final Queue out=new Queue();
        Sink blocking=new Sink(){public boolean current(Object owner){entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return true;}};
        CpLaneObserver concurrent=new CpLaneObserver(new CpLaneObserver.Runner(){public boolean post(Runnable task){service.execute(task);return true;}},out,blocking);
        try {
            concurrent.offer("session",good,good.length);check(entered.await(2,TimeUnit.SECONDS),"parser worker entered blocking fixture");
            long begin=System.nanoTime();for(int i=0;i<1000;i++)concurrent.offer("session",good,good.length);concurrent.close();
            check(System.nanoTime()-begin<500000000L,"offer/close never wait for blocked parser or sink");
            release.countDown();service.shutdown();check(service.awaitTermination(2,TimeUnit.SECONDS),"worker drains after shutdown");out.drain();check(blocking.received.isEmpty(),"in-flight parse cannot emit after shutdown");
        } finally {release.countDown();service.shutdownNow();}
        System.out.println("CpLaneObserverTest PASS checks="+checks);
    }
}
