package hmgapi.hook;

import java.net.URL;
import java.net.URLClassLoader;

/** Exercise the real app-part parent policy, including AA hooks and renderer helpers.
 * The host VM does not emulate Dalvik preverification; same-loader assertions ensure
 * none of these same-jar dependencies needs the prohibited cross-package fallback. */
public final class AppPartBoundaryTest {
    public static void main(String[] ignored) throws Exception {
        final ClassLoader parent = AppPartBoundaryTest.class.getClassLoader();
        ClassLoader main = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("hmgapi.mods.wireless."))
                    throw new AssertionError("App part escaped to mod loader: " + name);
                return super.loadClass(name, resolve);
            }
        };
        URL root = AppPartBoundaryTest.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader part = new URLClassLoader(new URL[] { root },
                new AppPartLoader("hmgapi.mods.wireless.gpm", main, parent))) {
            String prefix = "hmgapi.mods.wireless.gpm.";
            for (String name : new String[] {"Gpm", "AaTurns", "AaGuidance", "ApproachSender",
                    "LaneDistanceWriter", "CpLaneFrame", "CpLaneFrame$LaneGlyphs", "NativeRecipeProof"}) {
                if (part.loadClass(prefix + name).getClassLoader() != part)
                    throw new AssertionError("Helper is not defined by app-part loader: " + name);
            }
            for (String name : new String[] {"AaTurnsTest", "GpmTest", "AaIntegrationTest"})
                part.loadClass(prefix + name).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        }
        System.out.println("AppPartBoundaryTest PASS (actual AppPartLoader; all GPM helpers share its child loader)");
    }
}
