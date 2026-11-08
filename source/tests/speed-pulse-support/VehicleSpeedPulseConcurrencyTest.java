package hmgapi.mods.wireless.dipo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real producer/worker threads; fake OEM reads, with no vehicle or network I/O. */
public final class VehicleSpeedPulseConcurrencyTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void maximum(AtomicInteger maximum,int value){
        int old;do{old=maximum.get();if(value<=old)return;}while(!maximum.compareAndSet(old,value));
    }
    static final class Owner {volatile int latest;}
    static final class Worker implements VehicleSpeedPulse.Worker,AutoCloseable {
        final LinkedBlockingQueue<Runnable> queue=new LinkedBlockingQueue<Runnable>();
        final AtomicInteger maximumQueued=new AtomicInteger(),outstanding=new AtomicInteger();
        volatile boolean closed,delayed;
        volatile Throwable failure;
        final Thread thread=new Thread(new Runnable(){public void run(){
            try {
                while(!closed){
                    Runnable next=queue.take();
                    try{next.run();}finally{outstanding.decrementAndGet();}
                }
            }catch(InterruptedException end){if(!closed)failure=end;}
            catch(Throwable bad){failure=bad;}
        }},"speed-test-worker");
        Worker(){thread.start();}
        public boolean post(Runnable work,long delay){
            if(delay!=0)delayed=true;
            if(closed)return false;
            outstanding.incrementAndGet();queue.add(work);maximum(maximumQueued,queue.size());return true;
        }
        public void cancel(Runnable work){if(queue.remove(work))outstanding.decrementAndGet();}
        void idle()throws Exception{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline){
                if(failure!=null)throw new AssertionError(failure);
                if(outstanding.get()==0)return;
                Thread.sleep(1);
            }
            throw new AssertionError("worker did not drain");
        }
        public void close()throws Exception{closed=true;thread.interrupt();thread.join(5000);check(!thread.isAlive(),"worker terminates");}
    }
    static final class Target implements VehicleSpeedPulse.Target {
        final AtomicInteger sends=new AtomicInteger(),active=new AtomicInteger(),maximumActive=new AtomicInteger(),failures=new AtomicInteger();
        final CountDownLatch firstEntered=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        volatile boolean holdFirst,failFirst;
        volatile int lastValue;
        volatile Object lastOwner;
        public Object identity(Object owner){return owner;}
        public boolean allowed(Object owner){return true;}
        public void send(Object owner,VehicleSpeedPulse.Validity current)throws Exception{
            if(!current.valid())return;
            maximum(maximumActive,active.incrementAndGet());
            try{
                // The formatter reads current OEM state at send time, not a queued sample.
                lastValue=((Owner)owner).latest;lastOwner=owner;
                int count=sends.incrementAndGet();
                if(count==1){firstEntered.countDown();if(holdFirst&&!releaseFirst.await(5,TimeUnit.SECONDS))throw new AssertionError("release timeout");if(failFirst)throw new Exception("old send failed");}
                if(count%17==0)Thread.yield();
            }finally{active.decrementAndGet();}
        }
        public void failed(){failures.incrementAndGet();}
    }
    static void change(VehicleSpeedPulse pulse,Owner owner,int value){owner.latest=value;pulse.changed(owner,value);}
    static Thread produce(final VehicleSpeedPulse pulse,final Owner owner,final boolean same){
        Thread producer=new Thread(new Runnable(){public void run(){
            for(int i=0;i<100000;i++){
                int value=same?50:i%511;
                change(pulse,owner,value);
                change(pulse,owner,value);
                change(pulse,owner,value);
            }
        }},"speed-test-producer");producer.start();return producer;
    }
    static void joined(Thread thread)throws Exception{thread.join(10000);check(!thread.isAlive(),"producer completes without sender lock contention");}
    static void bounded(Worker worker,Target target){
        check(worker.failure==null,"worker has no asynchronous failure");
        check(!worker.delayed,"all callbacks use zero artificial delay");
        check(worker.maximumQueued.get()<=1,"real queue never exceeds one job");
        check(target.maximumActive.get()==1,"at most one send in flight");
    }
    public static void main(String[] args)throws Exception{
        try(Worker worker=new Worker()){
            Target target=new Target();target.holdFirst=true;
            VehicleSpeedPulse pulse=new VehicleSpeedPulse(worker,target);Owner owner=new Owner();pulse.start(owner);
            change(pulse,owner,50);check(target.firstEntered.await(5,TimeUnit.SECONDS),"first send enters");
            Thread producer=produce(pulse,owner,false);joined(producer);
            check(target.sends.get()==1&&worker.queue.isEmpty(),"blocked send coalesces 300000 callbacks without queue growth");
            target.releaseFirst.countDown();worker.idle();
            check(target.sends.get()==2&&target.lastValue==99999%511,"only latest real value follows blocked send");
            pulse.stop(owner);bounded(worker,target);
        }
        try(Worker worker=new Worker()){
            Target target=new Target();VehicleSpeedPulse pulse=new VehicleSpeedPulse(worker,target);Owner owner=new Owner();pulse.start(owner);
            Thread producer=produce(pulse,owner,false);joined(producer);worker.idle();
            check(target.lastValue==99999%511,"concurrent stream drains to newest OEM value");
            check(target.sends.get()>0&&target.sends.get()<=100000,"no callback-to-send amplification");
            int total=target.sends.get();Thread.sleep(20);worker.idle();check(target.sends.get()==total,"finished producer leaves no self-sustaining send loop");
            pulse.stop(owner);bounded(worker,target);
            System.out.println("Concurrent changing stream: 300000 callbacks, "+total+" sends");
        }
        try(Worker worker=new Worker()){
            Target target=new Target();VehicleSpeedPulse pulse=new VehicleSpeedPulse(worker,target);Owner owner=new Owner();pulse.start(owner);
            Thread producer=produce(pulse,owner,true);joined(producer);worker.idle();
            check(target.sends.get()==1&&target.lastValue==50,"300000 identical callbacks send first value once");
            pulse.stop(owner);pulse.start(owner);change(pulse,owner,50);worker.idle();
            check(target.sends.get()==2,"new request sends first value even when raw speed is unchanged");
            pulse.stop(owner);bounded(worker,target);
        }
        try(Worker worker=new Worker()){
            Target target=new Target();target.holdFirst=true;target.failFirst=true;
            VehicleSpeedPulse pulse=new VehicleSpeedPulse(worker,target);Owner old=new Owner(),next=new Owner();pulse.start(old);
            change(pulse,old,10);check(target.firstEntered.await(5,TimeUnit.SECONDS),"old send enters before request switch");
            pulse.stop(old);pulse.start(next);change(pulse,next,20);target.releaseFirst.countDown();worker.idle();
            check(target.failures.get()==1&&target.lastOwner==next&&target.lastValue==20,"late old-session failure cannot disable new request");
            pulse.stop(next);bounded(worker,target);
        }
        System.out.println("VehicleSpeedPulseConcurrencyTest PASS "+checks+" checks");
    }
}
