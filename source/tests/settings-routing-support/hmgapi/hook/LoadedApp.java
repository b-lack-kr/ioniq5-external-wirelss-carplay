package hmgapi.hook;import android.content.Context;
public class LoadedApp {public String packageName;public Context context;public ClassLoader classLoader;public LoadedApp(String pkg,Context c){packageName=pkg;context=c;classLoader=getClass().getClassLoader();}}
