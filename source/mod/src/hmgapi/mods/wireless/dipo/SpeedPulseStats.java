package hmgapi.mods.wireless.dipo;

/** Bounded counters only. Multiple validity checks per send are intentionally counted. */
final class SpeedPulseStats {
    private static final String[] NAMES={"allow","disabled","usb","not_wireless","inactive","not_requested","unreadable"};
    private final int[] gates=new int[NAMES.length];
    private long began;
    private int changes,invoked,errors,formatInvalid;
    SpeedPulseStats(long now){began=now;}
    private static int next(int value){return value==Integer.MAX_VALUE?value:value+1;}
    synchronized void gate(int reason){if(reason>=0&&reason<gates.length)gates[reason]=next(gates[reason]);}
    synchronized void changed(){changes=next(changes);}
    synchronized void invoked(){invoked=next(invoked);}
    synchronized void failed(){errors=next(errors);}
    synchronized void invalid(){formatInvalid=next(formatInvalid);}
    synchronized String summary(long now,boolean force){
        if(!force&&(now<began||now-began<10000))return null;
        StringBuilder s=new StringBuilder("pulse_window window_ms=").append(Math.max(0,now-began))
                .append(" change_callbacks=").append(changes).append(" invoke_ok=").append(invoked)
                .append(" invoke_error=").append(errors).append(" format_invalid=").append(formatInvalid);
        for(int i=0;i<gates.length;i++){s.append(" gate_").append(NAMES[i]).append('=').append(gates[i]);gates[i]=0;}
        changes=invoked=errors=formatInvalid=0;began=now;return s.toString();
    }
}
