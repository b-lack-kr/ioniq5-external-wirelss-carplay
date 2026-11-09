package kr.ioniq5.usbcp;

import java.io.IOException;

/** Explicit-request policy; never restarts a running or automatically retrying AP. */
public final class ApServiceRecovery {
    public static final int ALREADY_RUNNING=0, WAITING=1, START_REQUESTED=2,
        POWER_OFF=3, DISABLED=4, MISSING=5, INVALID=6, FAILED=7, THROTTLED=8;
    public interface Gate { int blocked(); } // 0 permits; POWER_OFF/DISABLED deny.
    public interface Supervisor { String status()throws IOException; boolean start()throws IOException; }
    private long lastRequest=-1;
    public synchronized int recover(Gate gate,Supervisor supervisor,long now) {
        int blocked=gate.blocked();
        if(blocked!=0)return blocked==POWER_OFF?POWER_OFF:DISABLED;
        if(now<0)return INVALID;
        try {
            int status=classify(supervisor.status());
            if(status!=START_REQUESTED)return status;
            // The source can go OFF while the bounded status call is in flight.
            blocked=gate.blocked();
            if(blocked!=0)return blocked==POWER_OFF?POWER_OFF:DISABLED;
            if(lastRequest>=0&&(now<lastRequest||now-lastRequest<10000))return THROTTLED;
            lastRequest=now;
            return supervisor.start()?START_REQUESTED:FAILED;
        }catch(IOException e){return FAILED;}
    }
    static int classify(String text) {
        if(text==null||text.length()>8192)return INVALID;
        int result=MISSING;boolean seen=false;
        for(String row:text.split("\n",-1)) {
            if(row.length()==0)continue;
            String[] f=row.split(" ",-1);
            if(f.length<2||!f[0].equals("neev-usb-carplay")||!f[1].equals("usb-ap"))continue;
            if(seen||f.length!=5)return INVALID;
            seen=true;
            if(!f[3].matches("0|[1-9][0-9]{0,8}")||!f[4].matches("0|[1-9][0-9]{0,8}"))return INVALID;
            boolean noPid=f[3].equals("0");
            if(f[2].equals("running"))result=noPid?INVALID:ALREADY_RUNNING;
            else if(f[2].equals("waiting"))result=WAITING;
            else if(f[2].equals("stopped")||f[2].equals("failed")||f[2].equals("exited"))
                result=noPid?START_REQUESTED:WAITING;
            else return INVALID;
        }
        return result;
    }
}
