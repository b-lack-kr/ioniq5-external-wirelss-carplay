package kr.ioniq5.usbcp;

/** Apply the durable power decision and fence any in-flight AP observation. */
public final class PowerControl {
    private final PowerLatch latch;
    private final ApState ap;
    public PowerControl(PowerLatch latch,ApState ap,long now){this.latch=latch;this.ap=ap;ap.demand(!latch.paused(),now);}
    public synchronized int accept(PowerLatch.Event event,long nanos,long millis){
        int result=latch.accept(event,nanos);ap.demand(!latch.paused(),millis);return result;
    }
    public String state(){return latch.state();}
}
