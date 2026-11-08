package hmgapi.mods.wireless.dipo;

/** Numeric speed samples and monotonic timings only; never retains NMEA text or coordinates. */
final class LocationTraceWindow {
    static final int SPEED = 0, GGA_IN = 1, RMC_IN = 2, GGA_OUT = 3,
            RMC_OUT = 4, PASCD_OUT = 5, SEND_OK = 6, SEND_FALSE = 7, SEND_ERROR = 8;
    private static final String[] NAMES = { "speed_in", "gga_in", "rmc_in", "gga_out",
            "rmc_out", "pascd_out", "send_ok", "send_false", "send_error" };
    private final int[] counts = new int[NAMES.length];
    private final long[] last = new long[NAMES.length];
    private final long[] gaps = new long[NAMES.length];
    private long windowStart;
    private long maxSpeedAge = -1, maxGgaAge = -1, maxRmcAge = -1, maxSendDuration = -1;
    private int micomKmh=-1,rmcMilliKmh=-1,rmcValid=-1,rmcInvalid;
    private long micomAt=-1,rmcAt=-1;

    LocationTraceWindow(long now) {
        windowStart = now;
        for (int i = 0; i < last.length; i++) last[i] = -1;
    }

    void event(int kind, long now) {
        if (kind < 0 || kind >= counts.length) return;
        if (last[kind] >= 0) gaps[kind] = Math.max(gaps[kind], Math.max(0, now - last[kind]));
        last[kind] = now;
        if (counts[kind] < Integer.MAX_VALUE) counts[kind]++;
        if (kind == PASCD_OUT) maxSpeedAge = Math.max(maxSpeedAge, age(SPEED, now));
        if (kind == GGA_OUT) maxGgaAge = Math.max(maxGgaAge, age(GGA_IN, now));
        if (kind == RMC_OUT) maxRmcAge = Math.max(maxRmcAge, age(RMC_IN, now));
    }

    void sent(long duration) { maxSendDuration = Math.max(maxSendDuration, Math.max(0, duration)); }

    void micomSpeed(Object value,long now) {
        if(!(value instanceof Integer))return;
        int speed=((Integer)value).intValue();
        micomKmh=speed>=0&&speed<511?speed:-1;micomAt=now;
    }

    void rmcSpeed(Object value,long now) {
        rmcAt=now;rmcMilliKmh=-1;rmcValid=0;
        if(value instanceof String){
            String text=(String)value;
            if(text.length()<=256&&text.startsWith("$GPRMC,")&&"A".equals(field(text,2))){
                String speed=field(text,7);
                if(speed!=null&&speed.length()>0&&speed.length()<=12){
                    boolean decimal=true,dot=false;
                    for(int i=0;i<speed.length();i++){
                        char c=speed.charAt(i);
                        if(c=='.'&&!dot){dot=true;continue;}
                        if(c<'0'||c>'9'){decimal=false;break;}
                    }
                    if(decimal)try{
                        double kmh=Double.parseDouble(speed)*1.852;
                        if(!Double.isNaN(kmh)&&!Double.isInfinite(kmh)&&kmh>=0&&kmh<511){
                            rmcMilliKmh=(int)Math.round(kmh*1000);rmcValid=1;
                        }
                    }catch(NumberFormatException ignored){}
                }
            }
        }
        if(rmcValid==0&&rmcInvalid<Integer.MAX_VALUE)rmcInvalid++;
    }

    // Only status and speed substrings are materialized; the payload is never retained.
    private static String field(String text,int wanted){
        int from=0,index=0;
        for(int i=0;i<=text.length();i++){
            if(i==text.length()||text.charAt(i)==','||text.charAt(i)=='*'||text.charAt(i)=='\0'){
                if(index==wanted)return text.substring(from,i);
                if(i==text.length()||text.charAt(i)!=',')return null;
                index++;from=i+1;
            }
        }
        return null;
    }

    private long age(int kind, long now) {
        return last[kind] < 0 ? -1 : Math.max(0, now - last[kind]);
    }

    String summary(long now) {
        StringBuilder out = new StringBuilder("window_ms=").append(Math.max(0, now - windowStart));
        for (int i = 0; i < counts.length; i++) {
            out.append(' ').append(NAMES[i]).append('=').append(counts[i]);
            if (i < SEND_OK) out.append(' ').append(NAMES[i]).append("_max_gap_ms=").append(gaps[i]);
            counts[i] = 0;
            gaps[i] = 0;
        }
        out.append(" speed_callback_age_ms=").append(age(SPEED, now))
                .append(" gga_callback_age_ms=").append(age(GGA_IN, now))
                .append(" rmc_callback_age_ms=").append(age(RMC_IN, now))
                .append(" max_speed_age_at_pascd_ms=").append(maxSpeedAge)
                .append(" max_gga_age_at_send_ms=").append(maxGgaAge)
                .append(" max_rmc_age_at_send_ms=").append(maxRmcAge)
                .append(" max_binder_ms=").append(maxSendDuration)
                .append(" micom_kmh=").append(micomKmh)
                .append(" micom_observed_age_ms=").append(micomAt<0?-1:Math.max(0,now-micomAt))
                .append(" rmc_kmh_milli=").append(rmcMilliKmh)
                .append(" rmc_fix_valid=").append(rmcValid)
                .append(" rmc_speed_age_ms=").append(rmcAt<0?-1:Math.max(0,now-rmcAt))
                .append(" rmc_speed_invalid=").append(rmcInvalid);
        windowStart = now;
        maxSpeedAge = maxGgaAge = maxRmcAge = maxSendDuration = -1;
        rmcInvalid=0;
        return out.toString();
    }
}
