package kr.ioniq5.usbcp;
import java.util.Arrays;

public final class TrapBarStateTest {
    static int checks;
    static void ok(boolean b,String why) { checks++; if(!b) throw new AssertionError(why); }
    static int[] stock() { int[] x=new int[17]; x[2]=3; x[11]=47; x[12]=80; return x; }
    static void preserves(int[] got,int[] stock,int bar) {
        ok(got!=null,"output exists");
        for(int i=0;i<17;i++) ok(got[i]==(i==11?bar:stock[i]),"preserves index "+i);
    }
    public static void main(String[] unused) {
        TrapBarState s=new TrapBarState();
        ok(s.request(true,2,10,true)==null,"no fabricated neutral snapshot");
        int[] a=stock(); int[] overlay=s.capture(a,false,11,true);
        preserves(overlay,a,2); ok(a[11]==47,"caller record is not mutated");
        s.applied(true);
        preserves(s.request(false,0,12,true),a,47);
        preserves(s.request(false,0,12,true),a,47); // A failed writer has not yet committed the restore.
        s.applied(false);
        ok(s.request(false,0,13,true)==null,"repeat release has no output");
        s.request(true,7,100,true); preserves(s.capture(a,false,101,true),a,7);
        ok(s.expire(3599,true)==null,"lease has not expired");
        preserves(s.expire(3600,true),a,47);
        s.applied(false);
        int[] camera=stock();camera[0]=80;
        s.request(true,3,4000,true); preserves(s.capture(camera,false,4001,true),camera,47);
        ok(s.request(true,4,4002,true)==null,"camera owns bar");
        preserves(s.capture(a,true,4003,true),a,47);
        ok(s.request(true,4,4004,true)==null,"extended warning owns bar");
        preserves(s.capture(a,false,4005,true),a,4);
        s.applied(true);
        ok(s.request(false,0,4006,false)==null,"do not write after losing ownership");
        ok(s.request(true,101,5000,true)==null && s.request(true,-1,5000,true)==null,"invalid bounds");
        ok(s.request(true,5,5000,false)==null,"no lease for another owner");
        s.invalid();ok(s.request(true,5,5001,true)==null,"invalid snapshot cannot be replayed");
        ok(!TrapBarState.hasAlert(stock()),"real idle overspeedAlarm=3 does not count as a camera");
        int[] warning=stock(); warning[5]=1;
        ok(TrapBarState.hasAlert(warning),"active warning blocks overlay");
        System.out.println("TrapBarStateTest: "+checks+" checks passed");
    }
}
