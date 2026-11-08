package android.content;

import java.util.ArrayList;
import java.util.List;

public final class Context {
    public final List<Intent> sent = new ArrayList<Intent>();
    public boolean fail;
    public void sendBroadcast(Intent intent) {
        if (fail) throw new SecurityException("synthetic permission failure");
        sent.add(intent);
    }
}
