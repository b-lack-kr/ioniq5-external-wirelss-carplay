package hmgapi.mods.wireless.dipo;

import android.os.Handler;
import android.os.HandlerThread;

/** Android scheduling adapter; no native parser patch and no periodic TBT refresh. */
final class CpRawLanes {
    private static volatile CpLaneObserver observer;
    private static Handler parser;
    private CpRawLanes() {}
    private static final class Owner {
        final Object cp;
        final CpTurns target;
        final long transportEpoch,laneGeneration;
        Owner(Object cp,CpTurns target) {
            this.cp=cp; this.target=target;
            transportEpoch=Bridge.rawLaneEpoch(); laneGeneration=target.laneGeneration();
        }
        public boolean equals(Object other) {
            if(!(other instanceof Owner)) return false;
            Owner o=(Owner)other;
            return cp==o.cp && target==o.target && transportEpoch==o.transportEpoch && laneGeneration==o.laneGeneration;
        }
        public int hashCode() { return System.identityHashCode(cp); }
        boolean current() {
            return cp==Bridge.sCp && transportEpoch==Bridge.rawLaneEpoch() && !Bridge.sUsb
                && target==Dipo.turns && target.acceptsRawLanes() && laneGeneration==target.laneGeneration()
                && Bridge.enabled();
        }
    }
    /** Per-observer cumulative numeric evidence; emitted only by its parser worker. */
    private static final class Metrics {
        final String[] names={"ready","decoded","delivered","overflow","decode","decode-failed","delivery-failed","queue-failed","closed"};
        final long[] totals=new long[names.length], last=new long[names.length];
        void record(String kind,int count) {
            int index=-1; for(int i=0;i<names.length;i++)if(names[i].equals(kind)){index=i;break;}
            if(index<0)return;
            totals[index]=Math.min(Long.MAX_VALUE-count,totals[index])+count;
            long now=System.nanoTime()/1000000L;
            if(kind.equals("closed")) {
                // Final totals are useful even for sessions shorter than the sample interval.
                for(int i=1;i<names.length-1;i++)emit(i,now);
                emit(index,now);
            } else if(last[index]==0 || now-last[index]>=30000L)emit(index,now);
        }
        void emit(int index,long now) {
            last[index]=now;
            Dipo.log("USB_CP_RAW_LANES "+names[index]+" count="+totals[index]);
        }
    }
    /** Prepare the worker at session creation, not inside the transport data callback. */
    static synchronized void start() {
        stop();
        if(parser==null) {
            HandlerThread thread=new HandlerThread("hmgapi-cp-lanes"); thread.start();
            parser=new Handler(thread.getLooper());
        }
        final Handler worker=parser;
        final Metrics metrics=new Metrics();
        observer=new CpLaneObserver(new CpLaneObserver.Runner() {
            public boolean post(Runnable task) { return worker.post(task); }
        },new CpLaneObserver.Runner() {
            public boolean post(Runnable task) {
                Handler destination=Dipo.turnsHandler; return destination!=null && destination.post(task);
            }
        },new CpLaneObserver.Sink() {
            public boolean current(Object owner) { return ((Owner)owner).current(); }
            public void accept(Object owner,CpLaneDecoder.Info info) {
                Owner o=(Owner)owner;
                if(o.current()) o.target.fallbackLanes(info,o.laneGeneration);
            }
            public void problem(String kind,int count) { metrics.record(kind,count); }
        });
        observer.start();
    }
    static synchronized void stop() {
        CpLaneObserver old=observer; observer=null;
        if(old!=null) old.close();
    }
    static void observe(Object cp,byte[] bytes,int length) {
        // This is called only after the unchanged original forwarding/buffering path.
        CpLaneObserver current=observer; CpTurns target=Dipo.turns;
        if(current==null || target==null || cp!=Bridge.sCp || Bridge.sUsb || !target.acceptsRawLanes()) return;
        current.offer(new Owner(cp,target),bytes,length);
    }
}
