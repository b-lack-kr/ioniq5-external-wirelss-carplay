package hmgapi.mods.wireless.dipo;

import java.io.IOException;

/** Fixed categories only: at most one record per stage/kind in each DiPO process. */
final class ManualDiagnostics {
    private static long reported;
    private static final String[] STAGES={"server_failed","request_failed","gate_failed","dispatch_failed"};
    private static final String[] KINDS={"linkage","io","security","interrupted","runtime","other"};
    private ManualDiagnostics() {}
    static void failure(String stage,Throwable error) {
        try {
            int phase=-1;for(int i=0;i<STAGES.length;i++)if(STAGES[i].equals(stage)){phase=i;break;}
            if(phase<0)return;
            int kind=error instanceof LinkageError?0:error instanceof IOException?1:
                error instanceof SecurityException?2:error instanceof InterruptedException?3:
                error instanceof RuntimeException?4:5;
            long bit=1L<<(phase*KINDS.length+kind);
            synchronized(ManualDiagnostics.class){if((reported&bit)!=0)return;reported|=bit;}
            Bridge.log("USB_CP_MANUAL event="+STAGES[phase]+" kind="+KINDS[kind]);
        }catch(Throwable ignored){ /* optional diagnostics must never take down projection */ }
    }
}
