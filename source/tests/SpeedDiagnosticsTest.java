package hmgapi.mods.wireless.dipo;

/** Synthetic speed diagnostics; not a vehicle-speed calibration fixture. */
public final class SpeedDiagnosticsTest {
    static int checks;
    static void yes(boolean condition,String detail){checks++;if(!condition)throw new AssertionError(detail);}
    static void has(String line,String field){yes(line.contains(field),field+" in "+line);}
    static String rmc(String status,String speed){return "$GPRMC,120000,"+status+",PRIVATE_LAT,N,PRIVATE_LON,E,"+speed+",0,010100,,,A*00";}
    public static void main(String[] args){
        LocationTraceWindow window=new LocationTraceWindow(0);
        window.micomSpeed(Integer.valueOf(21),100);
        window.rmcSpeed(rmc("A","10.0"),200);
        String first=window.summary(1000);
        has(first,"micom_kmh=21");has(first,"micom_observed_age_ms=900");
        has(first,"rmc_kmh_milli=18520");has(first,"rmc_fix_valid=1");has(first,"rmc_speed_age_ms=800");
        yes(!first.contains("PRIVATE")&&!first.contains("GPRMC")&&!first.contains("120000"),"no payload/position/time of day in numeric log");
        window.rmcSpeed(rmc("V","12.0"),1500);
        String invalid=window.summary(2000);
        has(invalid,"rmc_kmh_milli=-1");has(invalid,"rmc_fix_valid=0");has(invalid,"rmc_speed_invalid=1");
        has(invalid,"micom_kmh=21");has(invalid,"micom_observed_age_ms=1900");
        window.rmcSpeed(rmc("A","0.0"),2100);window.micomSpeed(Integer.valueOf(0),2200);
        String stopped=window.summary(3000);has(stopped,"micom_kmh=0");has(stopped,"rmc_kmh_milli=0");has(stopped,"rmc_fix_valid=1");
        for(String bad:new String[]{"", "-1", "+1", ".", "NaN", "Infinity", "1e2", "999999", "00000000000001"}){
            window.rmcSpeed(rmc("A",bad),3100);has(window.summary(3200),"rmc_kmh_milli=-1");
        }
        window.rmcSpeed("$GPRMC,"+new String(new char[300]),3300);has(window.summary(3400),"rmc_fix_valid=0");
        window.micomSpeed(Integer.valueOf(511),3500);has(window.summary(3600),"micom_kmh=-1");
        LocationTraceWindow replaced=new LocationTraceWindow(4000);String fresh=replaced.summary(5000);
        has(fresh,"micom_kmh=-1");has(fresh,"rmc_kmh_milli=-1");has(fresh,"rmc_fix_valid=-1");
        SpeedPulseStats stats=new SpeedPulseStats(0);stats.changed();stats.gate(0);stats.gate(4);stats.invoked();stats.invalid();stats.failed();
        yes(stats.summary(9999,false)==null,"bounded reporting");
        String sample=stats.summary(10000,false);has(sample,"change_callbacks=1");has(sample,"invoke_ok=1");has(sample,"invoke_error=1");has(sample,"format_invalid=1");has(sample,"gate_allow=1");has(sample,"gate_inactive=1");
        yes(stats.summary(10001,false)==null,"no duplicate report");
        String cleared=stats.summary(20000,false);has(cleared,"change_callbacks=0");has(cleared,"invoke_ok=0");has(cleared,"gate_inactive=0");
        System.out.println("SpeedDiagnosticsTest PASS "+checks+" checks");
    }
}
