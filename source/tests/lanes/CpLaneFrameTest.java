package hmgapi.mods.wireless.dipo;
import java.util.Arrays;
/** Shared public renderer: verified overlay fixtures plus arbitrary bounded input invariants. */
public final class CpLaneFrameTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void frame(String why,int[] expected,int[] classes,int route,int status){check(Arrays.equals(expected,CpLaneFrame.lane(classes,route,status)),why+" got "+Arrays.toString(CpLaneFrame.lane(classes,route,status)));}
    public static void main(String[] args){
        frame("left/straight preferred left",new int[]{11,9,12,0},new int[]{7,1},1,2);
        frame("straight/right preferred right",new int[]{11,16,18,0},new int[]{1,3},2,2);
        frame("left/right good left",new int[]{13,31,32,0},new int[]{7,3},1,1);
        frame("Uturn/left components",new int[]{11,10,14,0},new int[]{5,7},2,2);
        frame("L/S/R route-left independently colored",new int[]{62,28,22,24},new int[]{7,1,3},1,2);
        frame("L/S/R route-straight independently colored",new int[]{59,28,22,24},new int[]{7,1,3},2,2);
        frame("L/S/R route-right independently colored",new int[]{47,28,22,24},new int[]{7,1,3},4,2);
        frame("third slot blue survives packing",new int[]{42,28,22,24},new int[]{7,1,3},0,2);
        frame("three slots all grey",new int[]{63,28,22,24},new int[]{7,1,3},7,0);
        frame("three slots all white",new int[]{21,28,22,24},new int[]{7,1,3},7,1);
        frame("phone ordering cannot reorder verified overlay components",new int[]{47,28,22,24},new int[]{3,7,1},1,2);
        frame("duplicate classes retain route bit",new int[]{11,16,18,0},new int[]{1,3,3},4,2);
        frame("null lane retains one empty physical slot",new int[]{1,0,0,0},null,0,1);
        frame("invalids ignored",new int[]{1,0,0,0},new int[]{-1,0,9,Integer.MAX_VALUE},15,1);
        frame("unverified set selects route single arrow",new int[]{2,5,0,0},new int[]{5,7,1,3},1,2);
        for(int a=-1;a<=9;a++)for(int b=-1;b<=9;b++)for(int c=-1;c<=9;c++)for(int status=0;status<=3;status++)for(int mask=0;mask<8;mask++){
            int[] input={a,b,c},copy=input.clone(),r=CpLaneFrame.lane(input,mask,status);
            check(Arrays.equals(input,copy),"input immutable");check(r.length==4&&r[0]>=0&&r[0]<64,"three bounded status fields");
            for(int slot=0;slot<3;slot++){int colour=(r[0]>>(slot*2))&3;
                check(r[slot+1]>=0&&r[slot+1]<=33,"glyph stays in verified lane family");
                if(r[slot+1]!=0)check(colour!=0&&(status==0?colour==3:status==2?colour!=1:colour!=2),"status policy and AA never-blue invariant");
            }
        }
        int[] longInput=new int[10000];Arrays.fill(longInput,1);longInput[9999]=3;
        frame("only bounded eight direction slots consumed",new int[]{1,1,0,0},longInput,-1,1);
        System.out.println("CpLaneFrameTest: "+checks+" checks passed");
    }
}
