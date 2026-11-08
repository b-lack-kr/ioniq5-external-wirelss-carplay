package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.List;

final class Dipo {
    static final List<String> logs = new ArrayList<String>();
    static void log(String message) { logs.add(message); }
}
