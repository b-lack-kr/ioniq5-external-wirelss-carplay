package hmgapi.hook;
public final class LoadedApp {
    public final ClassLoader classLoader=getClass().getClassLoader();
    public final android.content.Context context=new android.content.Context();
}
