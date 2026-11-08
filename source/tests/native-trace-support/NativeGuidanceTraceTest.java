package kr.ioniq5.usbcp;
import java.util.ArrayList;
import java.util.List;

public final class NativeGuidanceTraceTest {
    static int checks;
    static void check(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
    static Object[] turn(int type, float angle) { return new Object[]{type,0,angle,200f,0,0,0,40,0}; }
    public static void main(String[] ignored) {
        final List<String> lines=new ArrayList<String>();
        NativeGuidanceTrace trace=new NativeGuidanceTrace(new NativeGuidanceTrace.Sink(){ public void event(String s){lines.add(s);} });
        Object[] original=turn(13,90f);
        trace.observe("update_tbt_info",original,0,true);
        check(lines.size()==1 && lines.get(0).equals("USB_CP_NATIVE_TBT writer=update_tbt_info values=13,0,90.0,200.0,0,0,0,40,0"),"exact numeric tuple");
        trace.observe("update_tbt_info",original,1000,true);
        check(lines.size()==1,"identical frame deduplicated");
        trace.observe("update_tbt_info",turn(31,270f),1000,true);
        check(lines.size()==2,"changed maneuver recorded");
        for(int n=1001;n<2000;n++) trace.observe("update_tbt_info",turn(n,90f),n,true);
        check(lines.size()==2,"rapid distance/type changes cannot exceed one event per second");
        trace.observe("update_tbt_info",turn(17,90f),2000,true);
        check(lines.size()==3,"next callback records latest accepted tuple");
        trace.observe("update_tbt_info",turn(99,90f),3000,false);
        check(lines.size()==3,"suppressed phone-owned writes excluded");
        trace.observe("update_tbt_info",turn(17,90f),3001,true);
        check(lines.size()==4,"return to native ownership establishes a fresh baseline");
        for(Object bad:new Object[]{"private road or coordinate",Double.valueOf(90),Float.NaN,Float.POSITIVE_INFINITY,null}) {
            Object[] args=turn(13,90f);args[2]=bad;
            trace.observe("update_tbt_info",args,5000,true);
            check(lines.size()==4,"reject unsupported/nonfinite angle value");
        }
        trace.observe("update_tbt_info",new Object[]{13},5000,true);
        trace.observe("update_tbt_info",null,5000,true);
        trace.observe("road_name",new Object[]{"private road"},5000,true);
        trace.observe("update_tbt_info",original,-1,true);
        check(lines.size()==4,"wrong arity/unknown writer/invalid clock rejected");
        trace.observe("update_tbt_sub_info",new Object[]{1f,0,1,3,30,1,1,0f,0},5000,true);
        trace.observe("update_tbt_sub_info_b",new Object[]{1f,0,1,3,30,1,1,0f,0,1},5000,true);
        trace.observe("update_tbt_sub_info_2c",new Object[]{0,0,200,0,0,0,0,0,0},5000,true);
        trace.observe("update_remain_dis",new Object[]{3,0,0,2},5000,true);
        check(lines.size()==8,"exact nine/ten/four argument signatures accepted independently");
        Object[] wrongInt=turn(13,90f);wrongInt[0]=Long.valueOf(13);
        trace.observe("update_tbt_info",wrongInt,6000,true);
        check(lines.size()==8,"integer signature cannot stringify arbitrary Number subclass");
        check(original[0].equals(13) && original[2].equals(90f),"observations never modify hook arguments");
        NativeGuidanceTrace throwing=new NativeGuidanceTrace(new NativeGuidanceTrace.Sink(){public void event(String s){throw new RuntimeException();}});
        throwing.observe("update_tbt_info",original,0,true);
        check(true,"recorder failure never throws into native navigation");
        System.out.println("NativeGuidanceTraceTest: "+checks+" checks passed");
    }
}
