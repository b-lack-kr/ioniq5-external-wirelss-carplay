// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

/** One short-lived user request, never a persistent preference or a power override. */
final class RowConnectState {
    static final int IDLE=0, CHECKING=1, NATIVE=2, CONNECTING=3, DONE=4, FAILED=5;
    static final long LIMIT_MS=35000;
    private long serial,deadline;
    private String target;
    private int phase=IDLE;

    synchronized long begin(String address,long now) {
        expire(now);
        if(busy()||address==null||!address.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))return 0;
        target=address.toUpperCase(java.util.Locale.US);deadline=now+LIMIT_MS;
        phase=CHECKING;return ++serial;
    }
    synchronized boolean live(long id,long now) {expire(now);return id==serial&&busy();}
    synchronized boolean same(long id){return id==serial;}
    synchronized long generation(){return serial;}
    synchronized boolean owns(String address){return target!=null&&address!=null&&target.equalsIgnoreCase(address);}
    synchronized int phase(){return phase;}
    synchronized boolean busy(){return phase==CHECKING||phase==NATIVE||phase==CONNECTING;}
    synchronized void phase(long id,int next) {
        if(id==serial&&busy()&&(next==NATIVE||next==CONNECTING||next==DONE||next==FAILED))phase=next;
    }
    synchronized void cancel(){serial++;target=null;deadline=0;phase=IDLE;}
    private void expire(long now){if(busy()&&now>=deadline)phase=FAILED;}
    /** A target's native HFP success is required; another phone never satisfies it. */
    synchronized boolean hfpReady(long id,String address,long now) {
        return live(id,now)&&phase==NATIVE&&owns(address);
    }
    static byte[] bytes(String address) {
        if(address==null||!address.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))return null;
        String[] parts=address.split(":");byte[] out=new byte[6];
        for(int i=0;i<6;i++)out[i]=(byte)Integer.parseInt(parts[i],16);
        return out;
    }
}
