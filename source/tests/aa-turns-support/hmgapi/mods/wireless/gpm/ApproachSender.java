package hmgapi.mods.wireless.gpm;
import android.content.Context;import android.os.Handler;
/** Transport stand-in; production sender has its own direct tests. */
public final class ApproachSender {
 public static final int SOURCE_ANDROID_AUTO=2;
 public interface Clock{long now();} public interface Owner{Object current();} public interface Gate{boolean allowed(Object owner);}
 public static final java.util.List<String> sent=new java.util.ArrayList<String>();
 private final Owner owner;private final Gate gate;
 public ApproachSender(Context c,Handler h,Clock clock,Owner owner,Gate gate,int source){this.owner=owner;this.gate=gate;}
 public void update(boolean active,int bar){Object token=owner.current();if(!active||token!=null&&gate.allowed(token))sent.add(active+" "+bar);}
}
