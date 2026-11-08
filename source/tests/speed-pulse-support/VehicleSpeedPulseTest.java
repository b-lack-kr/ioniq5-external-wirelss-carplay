package hmgapi.mods.wireless.dipo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class VehicleSpeedPulseTest {
    static int checks;
    static void check(boolean pass,String why){checks++;if(!pass)throw new AssertionError(why);}
    static final class Job {final Runnable run;final long due;Job(Runnable r,long d){run=r;due=d;}}
    static final class Worker implements VehicleSpeedPulse.Worker {
        long now=100;boolean reject;int maximum;
        final List<Job> queue=new ArrayList<Job>();
        public long now(){return now;}
        public boolean post(Runnable r,long delay){
            if(reject)return false;
            check(delay==0,"actual speed changes have no artificial delay");queue.add(new Job(r,now+delay));maximum=Math.max(maximum,queue.size());return true;
        }
        public void cancel(Runnable r){for(int i=queue.size()-1;i>=0;i--)if(queue.get(i).run==r)queue.remove(i);}
        void due(){List<Job> ready=new ArrayList<Job>();for(Job j:queue)if(j.due<=now)ready.add(j);queue.removeAll(ready);for(Job j:ready)j.run.run();}
        void advance(long amount){now+=amount;due();}
    }
    static final class Owner {volatile int latest;}
    static final class Target implements VehicleSpeedPulse.Target {
        boolean allowed=true,fail;int failures;
        final List<Integer> values=new ArrayList<Integer>();final List<Object> owners=new ArrayList<Object>();
        Runnable during;
        public Object identity(Object owner){return owner;}
        public boolean allowed(Object owner){return allowed;}
        public void send(Object owner,VehicleSpeedPulse.Validity current)throws Exception{
            if(!current.valid())return;
            owners.add(owner);values.add(((Owner)owner).latest);
            if(during!=null){Runnable r=during;during=null;r.run();}
            if(fail)throw new Exception("private");
        }
        public void failed(){failures++;}
    }
    static void change(VehicleSpeedPulse p,Owner owner,int value){owner.latest=value;p.changed(owner,value);}
    public static void main(String[] args)throws Exception{
        final Worker w=new Worker();final Target t=new Target();final VehicleSpeedPulse p=new VehicleSpeedPulse(w,t);final Owner a=new Owner();
        change(p,a,0);check(w.queue.isEmpty(),"no request, no extra send");
        p.start(a);p.changed(new Owner(),0);check(w.queue.isEmpty(),"foreign manager ignored");
        change(p,a,0);check(t.values.isEmpty(),"callback never calls target synchronously");
        for(int i=0;i<1000;i++)change(p,a,i%511);
        check(w.queue.size()==1&&w.maximum==1,"burst stores one pending latest-state request");
        w.due();check(t.values.size()==1&&t.values.get(0)==488,"formatter reads latest data at execution");
        change(p,a,2);check(w.queue.get(0).due==w.now,"second send eligible at the same clock instant");
        change(p,a,3);w.due();check(t.values.size()==2&&t.values.get(1)==3,"next worker turn sends newest value without advancing time");
        for(int i=0;i<1000;i++)change(p,a,3);
        check(w.queue.isEmpty(),"same raw value adds no pending task after send");
        w.advance(10000);check(t.values.size()==2&&w.queue.isEmpty(),"no new periodic sender");
        change(p,a,4);Runnable stale=w.queue.get(0).run;p.stop(a);check(w.queue.isEmpty(),"stop removes queued work");
        p.start(a);stale.run();check(t.values.size()==2,"old runnable cannot send after restart");
        change(p,a,4);t.allowed=false;w.due();check(t.values.size()==2,"disabled/request-ended before worker is respected");
        // A disallowed callback must not consume its raw value for later recovery.
        change(p,a,5);t.allowed=true;w.advance(1000);check(t.values.size()==2,"enable alone creates no invented change");
        change(p,a,5);w.due();check(t.values.size()==3,"same value first seen while disabled is sent when allowed");
        p.stop(new Owner());change(p,a,7);w.advance(250);check(t.values.size()==4,"foreign stop cannot end current request");
        t.during=new Runnable(){public void run(){for(int i=0;i<1000;i++)change(p,a,i%511);}};
        change(p,a,8);w.advance(250);check(w.queue.size()==1,"changes during send coalesce to one next task");
        int n=t.values.size();check(w.queue.get(0).due==w.now,"inflight coalesced work has no cooldown");
        w.due();check(t.values.size()==n+1&&t.values.get(t.values.size()-1)==488,"inflight latest state delivered once on next worker turn");
        t.during=new Runnable(){public void run(){for(int i=0;i<1000;i++)change(p,a,10);}};
        change(p,a,10);w.due();check(w.queue.isEmpty(),"same value during in-flight send does not request another send");
        final Owner b=new Owner();b.latest=42;
        t.during=new Runnable(){public void run(){p.start(b);change(p,b,42);}};
        change(p,a,11);w.advance(250);n=t.values.size();check(w.queue.size()==1,"new request gets its own task after old in-flight call");
        w.advance(250);check(t.values.size()==n+1&&t.owners.get(t.owners.size()-1)==b,"old data never relabelled as new owner");
        t.during=new Runnable(){public void run(){p.stop(b);change(p,b,44);}};
        change(p,b,43);w.advance(250);check(w.queue.isEmpty(),"stop during accepted call schedules no follow-up");
        p.start(a);t.fail=true;change(p,a,11);w.advance(250);n=t.values.size();check(t.failures==1,"one failure reported");
        for(int i=0;i<1000;i++)change(p,a,i%511);w.advance(5000);check(t.values.size()==n&&t.failures==1,"failed extra path disables until a new request; stock path untouched");
        t.fail=false;p.start(a);change(p,a,11);w.due();check(t.values.size()==n+1,"new request resets raw value and can retry supplemental path");
        w.reject=true;p.start(b);change(p,b,42);check(t.failures==2&&w.queue.isEmpty(),"worker rejection is bounded and disables current extras");
        w.reject=false;change(p,b,43);check(w.queue.isEmpty(),"rejected session does not flood scheduler");
        p.start(b);change(p,b,42);w.advance(250);check(t.owners.get(t.owners.size()-1)==b,"new request recovers after worker rejection");
        p.start(null);change(p,b,43);check(w.queue.isEmpty(),"null start removes current request");
        p.start(a);change(p,a,1);Field session=VehicleSpeedPulse.class.getDeclaredField("session");session.setAccessible(true);Object s=session.get(p);
        Field owner=s.getClass().getDeclaredField("owner");owner.setAccessible(true);((WeakReference<?>)owner.get(s)).clear();
        n=t.values.size();w.advance(250);check(t.values.size()==n,"collected owner cannot send");
        check(w.maximum==1,"queue remains bounded for entire lifecycle");
        System.out.println("VehicleSpeedPulseTest PASS "+checks+" checks");
    }
}
