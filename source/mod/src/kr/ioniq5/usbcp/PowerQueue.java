package kr.ioniq5.usbcp;

/** One pending event, replacing older work while an IPC attempt is in flight. */
public final class PowerQueue {
    public static final class Pending {
        public final long nanos;public final boolean off;public final int command;
        Pending(long nanos,boolean off,int command){this.nanos=nanos;this.off=off;this.command=command;}
    }
    private final int command;
    public PowerQueue(){this(PowerLatch.DIPO_COMMAND);}
    public PowerQueue(int command){
        if(!PowerLatch.powerCommand(command))throw new IllegalArgumentException("invalid power source");
        this.command=command;
    }
    private Pending pending;
    private long last, lastInput;
    public synchronized void offer(boolean off,long nanos) {
        if(nanos<=0)return;
        if(nanos<lastInput)return;
        lastInput=nanos;
        if(nanos<=last)nanos=last+1;
        last=nanos;pending=new Pending(nanos,off,command);notifyAll();
    }
    public synchronized Pending current(){return pending;}
    public synchronized void delivered(Pending sent){if(pending==sent)pending=null;}
    public synchronized void await(Pending expected,long millis)throws InterruptedException {if(pending==expected)wait(millis);}
}
