import hmgapi.engine.MemRecipe;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

/** Executes the installed HMG-API engine on copied stock bytes; never writes a library file. */
public final class MemRecipeTest {
    static final long BASE=0x10000000L;
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static final class Memory implements MemRecipe.Memory {
        final byte[] library; byte[] code; long start; int writes,maps;
        Memory(byte[] bytes){library=bytes.clone();}
        public byte[] peek(long at,int length){
            byte[] bytes=at>=start&&code!=null&&at<start+code.length?code:library;
            long base=bytes==code?start:BASE;int off=(int)(at-base);
            check(off>=0&&off+length<=bytes.length,"bounded read");
            return Arrays.copyOfRange(bytes,off,off+length);
        }
        public boolean poke(long at,byte[] data){
            byte[] bytes=at>=start&&code!=null&&at<start+code.length?code:library;
            long base=bytes==code?start:BASE;int off=(int)(at-base);
            check(off>=0&&off+data.length<=bytes.length,"bounded write");
            System.arraycopy(data,0,bytes,off,data.length);writes++;return true;
        }
        public long mapCode(long hint,int length){maps++;start=hint;code=new byte[length];return hint;}
    }
    public static void main(String[] args)throws Exception {
        byte[] stock=Files.readAllBytes(Paths.get(args[0]));
        check(MemRecipe.md5(stock).equals("48ee793af8b86052503b8c952b034731"),"exact supported stock");
        MemRecipe recipe=MemRecipe.parse(new String(Files.readAllBytes(Paths.get(args[1])),"UTF-8"));
        check(recipe.id.equals("aa-tbt-48ee793a-neev-v0147"),"new decoder ownership ID");
        long textEnd=Long.parseLong(args[2]);
        Memory memory=new Memory(stock);
        MemRecipe.Result applied=recipe.apply(memory,BASE,textEnd,MemRecipe.md5(stock));
        check(applied.status==MemRecipe.Result.APPLIED&&memory.maps==1&&memory.writes==4,"actual engine applies mapped segment+two sites+branch");
        check(memory.library[0xb1a2e]==6&&memory.library[0x144efa]==3,"protocol/status sites");
        check(Arrays.equals(memory.peek(BASE+0xb6818,4),MemRecipe.branchW(BASE+0xb6818,applied.segment)),"Thumb branch reaches relocated segment");
        for(long[] rel:recipe.rel32){
            byte[] raw=memory.peek(applied.segment+rel[0],4);
            int value=(raw[0]&255)|(raw[1]&255)<<8|(raw[2]&255)<<16|raw[3]<<24;
            check(value==(int)((BASE+rel[2])-(applied.segment+rel[1])),"rel32 exact target");
        }
        for(MemRecipe.Verify verify:recipe.verifies){
            Memory wrong=new Memory(stock);wrong.library[(int)verify.off]^=1;
            MemRecipe.Result refused=recipe.apply(wrong,BASE,textEnd,MemRecipe.md5(stock));
            check(refused.status==MemRecipe.Result.MISMATCH&&wrong.writes==0&&wrong.maps==0,"each verify mismatch refuses before first mutation");
        }
        int writes=memory.writes,maps=memory.maps;
        check(recipe.apply(memory,BASE,textEnd,MemRecipe.md5(stock)).status==MemRecipe.Result.MISMATCH
            &&memory.writes==writes&&memory.maps==maps,"already-branched process is never hot-patched again");
        check(Arrays.equals(stock,Files.readAllBytes(Paths.get(args[0]))),"stock file stays unchanged");
        System.out.println("MemRecipeTest PASS exact stock, all verify regions, relocated branch, rel32, mismatch/no-write");
    }
}
