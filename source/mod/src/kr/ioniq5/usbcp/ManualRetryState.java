package kr.ioniq5.usbcp;

/** Handler-owned one-request tracker. Contains no Bluetooth or network operations. */
public final class ManualRetryState {
    public static final long TIMEOUT_MS=30000, RETRY_GAP_MS=2000;
    private String target;
    private long began=-1,lastBegin=-1,generation;
    private int result=ManualWire.READY;
    public synchronized long generation(){return generation;}
    public synchronized boolean pending(String address,long now){expire(now);return address!=null&&address.equals(target)&&result==ManualWire.ACCEPTED;}
    public synchronized boolean tracks(String address){return address!=null&&address.equals(target);}
    public synchronized int status(String address,long now){expire(now);return tracks(address)?result:ManualWire.READY;}
    public synchronized boolean begin(String address,long now){
        expire(now);
        if(result==ManualWire.ACCEPTED || (lastBegin>=0 && now-lastBegin<RETRY_GAP_MS))return false;
        target=address;began=lastBegin=now;result=ManualWire.ACCEPTED;generation++;return true;
    }
    public synchronized void finish(String address,int status){
        if(tracks(address)&&result==ManualWire.ACCEPTED){result=status;generation++;}
    }
    private void expire(long now){
        if(result==ManualWire.ACCEPTED&&(now<began||now-began>=TIMEOUT_MS)){result=ManualWire.TIMED_OUT;generation++;}
    }
}
