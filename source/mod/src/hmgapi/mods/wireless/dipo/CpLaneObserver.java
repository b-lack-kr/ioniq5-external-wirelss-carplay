package hmgapi.mods.wireless.dipo;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;

/** Optional raw lane input. Never parses, renders, or waits for either on offer(). */
final class CpLaneObserver {
    static final int MAX_CHUNKS=64, MAX_BYTES=131072, MAX_CHUNK_BYTES=65535, MAX_RESULTS=32;
    /** Each runner queues work asynchronously and serially; it must not run tasks inline. */
    interface Runner { boolean post(Runnable task); }
    interface Sink {
        boolean current(Object owner);
        void accept(Object owner,CpLaneDecoder.Info info);
        void problem(String kind,int count);
    }
    private static final class Chunk {
        final Object owner; final long generation; final byte[] bytes;
        Chunk(Object owner,long generation,byte[] bytes) {
            this.owner=owner; this.generation=generation; this.bytes=bytes;
        }
    }
    private static final class Result {
        final Object owner; final long generation; final CpLaneDecoder.Info info;
        Result(Object owner,long generation,CpLaneDecoder.Info info) {
            this.owner=owner; this.generation=generation; this.info=info;
        }
    }
    private final Object lock=new Object();
    private final Runner parser, delivery;
    private final Sink sink;
    private final CpLaneDecoder decoder=new CpLaneDecoder(); // Accessed only by parser runner.
    private final ArrayDeque<Chunk> chunks=new ArrayDeque<Chunk>();
    private final LinkedHashMap<Long,Result> results=new LinkedHashMap<Long,Result>();
    private Object owner;
    private long generation, decoderGeneration=-1;
    private int queuedBytes;
    private boolean parsing, delivering, closed, reportedReady, reportedClosed;
    private static final String[] METRICS={"decoded","delivered","overflow","decode","decode-failed","delivery-failed","queue-failed"};
    private final int[] pendingMetrics=new int[METRICS.length];
    private final Runnable parseTask=new Runnable(){public void run(){parse();}};
    private final Runnable deliverTask=new Runnable(){public void run(){deliverOne();}};

    CpLaneObserver(Runner parser,Runner delivery,Sink sink) {
        this.parser=parser; this.delivery=delivery; this.sink=sink;
    }
    void start() { requestParser(); }
    private void metricLocked(int index,int count) {
        pendingMetrics[index]=(int)Math.min(Integer.MAX_VALUE,(long)pendingMetrics[index]+count);
    }
    private void requestParser() {
        boolean schedule;
        synchronized(lock) { schedule=!parsing; parsing=true; }
        if(schedule) postParser();
    }
    private void metric(int index,int count) { synchronized(lock) { metricLocked(index,count); } }
    private void discardLocked() {
        generation++; chunks.clear(); results.clear(); queuedBytes=0;
    }
    /** Caller-owned arrays are never retained. Copies are bounded and made outside the queue lock. */
    boolean offer(Object nextOwner,byte[] bytes,int length) {
        if(nextOwner==null || bytes==null || length<=0 || length>bytes.length) return false;
        if(length>MAX_CHUNK_BYTES) {
            boolean schedule;
            synchronized(lock) {
                if(closed) return false;
                metricLocked(2,1); discardLocked(); owner=nextOwner; schedule=!parsing; parsing=true;
            }
            if(schedule) postParser();
            return false;
        }
        byte[] copy=new byte[length];
        System.arraycopy(bytes,0,copy,0,length);
        boolean schedule;
        synchronized(lock) {
            if(closed) return false;
            if(owner==null || !owner.equals(nextOwner)) { discardLocked(); owner=nextOwner; }
            if(chunks.size()>=MAX_CHUNKS || queuedBytes>MAX_BYTES-length) { metricLocked(2,1); discardLocked(); }
            chunks.addLast(new Chunk(nextOwner,generation,copy)); queuedBytes+=length;
            schedule=!parsing; parsing=true;
        }
        if(schedule) postParser();
        return true;
    }
    void close() {
        boolean schedule;
        synchronized(lock) {
            closed=true; owner=null; discardLocked(); schedule=!parsing; parsing=true;
        }
        // The worker resets partial protocol state, never the transport callback.
        if(schedule) postParser();
    }
    private void postParser() {
        try { if(parser.post(parseTask)) return; } catch(Throwable ignored) {}
        synchronized(lock) { parsing=false; metricLocked(6,1); discardLocked(); }
    }
    private void postDelivery() {
        try { if(delivery.post(deliverTask)) return; } catch(Throwable ignored) {}
        synchronized(lock) { delivering=false; metricLocked(6,1); results.clear(); }
        requestParser();
    }
    private boolean active(Object value,long serial) {
        synchronized(lock) { return !closed && generation==serial && owner!=null && owner.equals(value); }
    }
    private boolean current(Object value) {
        try { return sink.current(value); } catch(Throwable ignored) { return false; }
    }
    private void problem(String kind,int count) {
        try { sink.problem(kind,count); } catch(Throwable ignored) {}
    }
    /** Metrics leave the queue only on the parser worker, never on offer or the TBT handler. */
    private void flushMetrics() {
        int[] counts; boolean ready,ended;
        synchronized(lock) {
            counts=pendingMetrics.clone(); java.util.Arrays.fill(pendingMetrics,0);
            ready=!reportedReady; reportedReady=true;
            ended=closed&&!reportedClosed; if(ended)reportedClosed=true;
        }
        if(ready)problem("ready",1);
        for(int i=0;i<counts.length;i++)if(counts[i]>0)problem(METRICS[i],counts[i]);
        if(ended)problem("closed",1);
    }
    private void parse() {
        for(;;) {
            Chunk chunk; long serial;
            synchronized(lock) {
                serial=generation; chunk=chunks.pollFirst();
                if(chunk!=null) queuedBytes-=chunk.bytes.length;
                else parsing=false;
            }
            flushMetrics();
            if(decoderGeneration!=serial) { decoder.reset(); decoderGeneration=serial; }
            if(chunk==null) return;
            if(!active(chunk.owner,chunk.generation) || !current(chunk.owner)) {
                decoder.reset(); continue;
            }
            CpLaneDecoder.Info[] decoded;
            try {
                decoded=decoder.feed(chunk.bytes,chunk.bytes.length);
                int errors=decoder.takeErrors(); if(errors>0) metric(3,errors);
                if(decoded.length>0)metric(0,decoded.length);
            } catch(Throwable failed) { decoder.reset(); metric(4,1); continue; }
            if(!current(chunk.owner)) { decoder.reset(); continue; }
            boolean schedule=false;
            synchronized(lock) {
                if(closed || generation!=chunk.generation || owner==null || !owner.equals(chunk.owner)) continue;
                for(CpLaneDecoder.Info info:decoded) {
                    long key=((long)info.component<<32)|(info.index&0xffffffffL);
                    results.remove(key);
                    if(results.size()>=MAX_RESULTS) results.remove(results.keySet().iterator().next());
                    results.put(key,new Result(chunk.owner,chunk.generation,info));
                }
                if(!results.isEmpty() && !delivering) { delivering=true; schedule=true; }
            }
            if(schedule) postDelivery();
        }
    }
    /** One cached record per handler turn allows primary route callbacks to run between records. */
    private void deliverOne() {
        Result result;
        synchronized(lock) {
            if(results.isEmpty()) { delivering=false; return; }
            Long key=results.keySet().iterator().next(); result=results.remove(key);
        }
        if(active(result.owner,result.generation) && current(result.owner)) {
            try { sink.accept(result.owner,result.info); metric(1,1); } catch(Throwable failed) { metric(5,1); }
            requestParser();
        }
        boolean again;
        synchronized(lock) { again=!results.isEmpty(); if(!again) delivering=false; }
        if(again) postDelivery();
    }
    int pendingBytes() { synchronized(lock) { return queuedBytes; } }
    int pendingChunks() { synchronized(lock) { return chunks.size(); } }
    int pendingResults() { synchronized(lock) { return results.size(); } }
}
