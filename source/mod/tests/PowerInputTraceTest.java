package kr.ioniq5.usbcp;

public final class PowerInputTraceTest {
    static int checks;
    static void yes(boolean v,String msg){checks++;if(!v)throw new AssertionError(msg);}
    static void has(String s,String v){yes(s!=null&&s.contains(v),v+" in "+s);}
    static long ms(long t){return t*1000000L;}
    public static void main(String[] args){
        PowerInputTrace t=new PowerInputTrace();
        String empty=t.summary(0,"UNKNOWN","UNKNOWN",0);
        has(empty,"ignition=-1");has(empty,"driver_door=-1");has(empty,"acc_raw=-1");has(empty,"getter_off=-1");
        t.poll(0,false,false);t.ignition(true,false,ms(10));t.acc(5,false,ms(10));t.door(1,0,ms(10));t.micom(0);
        yes(t.summary(ms(500),"ON","INITIAL_READING",0)==null,"rate limit also bounds changed decision");
        String first=t.summary(ms(1000),"ON","INITIAL_READING",0);
        has(first,"ignition=1 ignition_live=0");has(first,"driver_door=0");has(first,"micom_raw=0 getter_off=0 preparing=0");
        has(first,"polls=1 poll_failures=0");has(first,"micom_events=1");
        t.ignition(false,true,ms(1100));t.door(2,1,ms(1150));
        yes(t.summary(ms(2000),"ON","INITIAL_READING",0)==null,"unchanged decision aggregates for ten seconds");
        t.door(1,1,ms(2500));
        String off=t.summary(ms(2500),"OFF","IGN_OFF_DRIVER_DOOR",ms(2500));
        has(off,"state=OFF reason=IGN_OFF_DRIVER_DOOR");has(off,"ignition=0 ignition_live=1");has(off,"driver_door=1 driver_door_age_ms=0");
        has(off,"door_events=2");has(off,"ignition_events=1");has(off,"polls=0");
        for(int i=0;i<20;i++)t.pollFailed();
        t.systemOff(true);t.systemOff(false);
        yes(t.summary(ms(12499),"OFF","SYSTEM_OFF",ms(2600))==null,"no unchanged-state log flood");
        String error=t.summary(ms(12500),"OFF","SYSTEM_OFF",ms(2600));
        has(error,"micom_raw=-1 getter_off=-1 preparing=-1");has(error,"poll_failures=20");has(error,"prepare_events=1 off_events=1");
        has(error,"driver_door=1");has(error,"ignition_age_ms=11400");
        String reset=t.summary(ms(22500),"OFF","SYSTEM_OFF",ms(2600));
        has(reset,"poll_failures=0");has(reset,"prepare_events=0 off_events=0");
        System.out.println("PowerInputTraceTest: "+checks+" assertions passed");
    }
}
