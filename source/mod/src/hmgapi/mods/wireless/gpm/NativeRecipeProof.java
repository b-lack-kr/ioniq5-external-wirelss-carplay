package hmgapi.mods.wireless.gpm;
import hmgapi.hook.ModContext;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Read-only compatibility check for the verified HMG 1.0.1/1.0.2 patch ownership record. */
final class NativeRecipeProof implements Gpm.NativeReady {
    static final String LIB="/system/lib/libautoreceiver_infobank.so",MD5="48ee793af8b86052503b8c952b034731",RECIPE="aa-tbt-48ee793a-neev-v0147";
    final String modId;final Class<?> patch;final Field owned,owner,recipe;private volatile boolean applied;
    NativeRecipeProof(ModContext mod)throws Exception {
        modId=mod.id;
        patch=Class.forName("hmgapi.hook.NativePatch",false,ModContext.class.getClassLoader());
        Class<?> record=Class.forName("hmgapi.hook.NativePatch$Owned",false,ModContext.class.getClassLoader());
        owned=patch.getDeclaredField("owned");owner=record.getDeclaredField("mod");recipe=record.getDeclaredField("recipe");
        if(!Map.class.isAssignableFrom(owned.getType())||owner.getType()!=String.class||recipe.getType()!=String.class)throw new IllegalStateException();
        owned.setAccessible(true);owner.setAccessible(true);recipe.setAccessible(true);
    }
    public boolean eligible(){return MD5.equals(ModContext.md5(LIB));}
    public boolean applied(){
        if(applied)return true;
        try {
            synchronized(patch){
                Object records=((Map<?,?>)owned.get(null)).get(LIB);
                if(records instanceof List)for(Object record:(List<?>)records)
                    if(modId.equals(owner.get(record))&&RECIPE.equals(recipe.get(record))){applied=true;return true;}
            }
        }catch(Throwable unknown){return false;}
        return false;
    }
}
