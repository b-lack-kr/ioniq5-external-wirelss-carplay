package hmgapi.mods.wireless.dipo;

/** Ten-second bounded numeric summaries. Never retains any sentence. */
final class RmcSpeedStats {
    private long began;private int seen,replaced,unchanged,gate,source,invalid,noFix;
    private int input=-1,output=-1,kmh=-1;private long age=-1;
    RmcSpeedStats(long now){began=now;}
    private static int inc(int x){return x==Integer.MAX_VALUE?x:x+1;}
    synchronized void event(int reason,RmcSpeedRewrite.Result result,RmcVehicleSpeedSource.Sample sample){
        seen=inc(seen);input=output=kmh=-1;age=-1;
        if(reason==1)gate=inc(gate);else if(reason==2)source=inc(source);
        else if(result!=null){
            input=result.inputKmhMilli;output=result.outputKmhMilli;
            if(result.status==RmcSpeedRewrite.INVALID)invalid=inc(invalid);
            else if(result.status==RmcSpeedRewrite.NO_FIX)noFix=inc(noFix);
            else if(result.status==RmcSpeedRewrite.REPLACED)replaced=inc(replaced);else unchanged=inc(unchanged);
        }
        if(sample!=null){kmh=sample.kmh;age=sample.age;}
    }
    synchronized String summary(long now){
        if(now<began||now-began<10000)return null;
        String line="rmc_window window_ms="+(now-began)+" seen="+seen+" replaced="+replaced+" unchanged="+unchanged
                +" skip_gate="+gate+" skip_source="+source+" skip_invalid="+invalid+" skip_no_fix="+noFix
                +" original_kmh_milli="+input+" output_kmh_milli="+output+" micom_kmh="+kmh+" micom_age_ms="+age;
        began=now;seen=replaced=unchanged=gate=source=invalid=noFix=0;return line;
    }
}
