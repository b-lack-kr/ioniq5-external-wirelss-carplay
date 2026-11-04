package kr.ioniq5.usbcp;

/** Generation and request fence shared by the observer and IPC threads. */
public final class ApState {
    private final String epoch;
    private long revision,generation;
    private boolean demanded=true;
    private Snapshot value, measured;
    private int failures;
    public ApState(String epoch,long now){this.epoch=epoch;value=Snapshot.down(epoch+"-0",now);}
    public synchronized long revision(){return revision;}
    public synchronized boolean demanded(){return demanded;}
    public synchronized void demand(boolean on,long now){
        if(on==demanded)return;
        demanded=on;failures=0;revision++;generation++;measured=null;value=Snapshot.down(epoch+"-"+generation,now);
    }
    public synchronized void publish(long expected,Snapshot next){
        if(expected!=revision || !demanded)return; // discard observations that straddled RELEASE/ACQUIRE
        failures=0;
        if(measured==null || !next.sameAp(measured) || !next.generation.equals(measured.generation))generation++;
        measured=next;
        value=new Snapshot(epoch+"-"+generation,next.observed,next.ready,next.iface,next.ifindex,
                next.ip,next.bssid,next.ssid,next.password,next.frequency);
    }
    /** Only verified-identity control timeouts qualify. Preserve the original observation timestamp. */
    public synchronized boolean failed(long expected,long now,boolean transientFailure) {
        if(expected!=revision || !demanded)return false;
        failures++;
        if(transientFailure && failures<=2 && value.fresh(now) && now-value.observed<=3000)return true;
        // Do not call publish: it would reset the consecutive-failure counter.
        if(value.ready)generation++;
        measured=null;value=Snapshot.down(epoch+"-"+generation,now);return false;
    }
    public synchronized int failures(){return failures;}
    public synchronized Snapshot current(long now){
        return demanded && value.fresh(now) ? value : Snapshot.down(value.generation,now);
    }
}
