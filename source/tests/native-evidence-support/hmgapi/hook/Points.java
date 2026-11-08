package hmgapi.hook;
import java.lang.reflect.Method;import java.util.*;
public class Points {public static final Map<String,Method> methods=new HashMap<String,Method>();public static Method method(ClassLoader cl,String name){Method m=methods.get(name);if(m==null)throw new IllegalArgumentException();return m;}}
