package hmgapi.hook;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
class NativePatch {
    static final Map<String,List<Owned>> owned=new HashMap<String,List<Owned>>();
    static final class Owned {final String mod,recipe;Owned(String mod,String recipe){this.mod=mod;this.recipe=recipe;}}
}
public final class NativeProofFixture {
    public static void reset(){synchronized(NativePatch.class){NativePatch.owned.clear();}}
    public static void add(String mod,String recipe){synchronized(NativePatch.class){
        String path="/system/lib/libautoreceiver_infobank.so";List<NativePatch.Owned> records=NativePatch.owned.get(path);
        if(records==null){records=new ArrayList<NativePatch.Owned>();NativePatch.owned.put(path,records);}
        records.add(new NativePatch.Owned(mod,recipe));
    }}
}
