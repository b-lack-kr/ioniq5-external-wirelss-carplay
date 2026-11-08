package hmgapi.mods.wireless.dipo;
import android.content.Context;
import hmgapi.hook.Points;
import java.lang.reflect.Method;
import java.util.Arrays;
public final class LaneDistanceWriterTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static class Target {
        int[] sent;int calls;boolean fail;
        public void write(int a,int b,int c,int d){calls++;if(fail)throw new IllegalStateException();sent=new int[]{a,b,c,d};}
        public static void stat(int a,int b,int c,int d){}
        public int result(int a,int b,int c,int d){return 0;}
        public void three(int a,int b,int c){}
        public void boxed(int a,int b,int c,Integer d){}
    }
    public static void main(String[] args)throws Exception {
        Target target=new Target();Points.receiver=target;
        Points.selected=Target.class.getMethod("write",int.class,int.class,int.class,int.class);
        LaneDistanceWriter writer=LaneDistanceWriter.create(new Context());check(writer!=null,"exact mapped API");
        for(int[] tuple:new int[][]{{150,0,0,0},{1,1,2,0},{65534,2,9,0},{65535,15,15,0}}){
            check(writer.send(tuple[0],tuple[1],tuple[2],tuple[3])&&Arrays.equals(tuple,target.sent),"normal/clear tuple exact");}
        int n=target.calls;
        for(int[] bad:new int[][]{{-1,0,0,0},{65535,0,0,0},{1,-1,0,0},{1,3,0,0},{1,0,10,0},{1,0,-1,0},{1,0,0,1},{65535,15,15,1}})
            check(!writer.send(bad[0],bad[1],bad[2],bad[3]),"invalid width/unit/toll refused");
        check(target.calls==n,"invalid tuples never invoke Binder adapter");
        target.fail=true;check(!writer.send(1,0,0,0),"write failure reported");target.fail=false;check(writer.send(1,0,0,0),"failure retry succeeds");
        for(Method method:Target.class.getMethods())if(Arrays.asList("stat","result","three","boxed").contains(method.getName())){
            Points.selected=method;n=Points.constructors;check(LaneDistanceWriter.create(new Context())==null&&Points.constructors==n,"bad method fails before construction");}
        Points.selected=null;check(LaneDistanceWriter.create(new Context())==null,"missing API optional");
        Points.selected=Target.class.getMethod("write",int.class,int.class,int.class,int.class);
        for(Object wrong:new Object[]{null,new Object()}){Points.receiver=wrong;check(LaneDistanceWriter.create(new Context())==null,"receiver mismatch rejected");}
        System.out.println("LaneDistanceWriterTest PASS checks="+checks);
    }
}
