package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.List;

final class Dipo {
    static final List<String> events = new ArrayList<String>();
    static boolean logFails;
    static void log(String event) {
        if (logFails) throw new IllegalStateException("synthetic recorder failure");
        events.add(event);
    }
    static void reset() { events.clear(); logFails = false; }
}
