package kr.ioniq5.mutepause;

/** Main-looper state. Require matching service and stable MICOM state after stock handles the key. */
final class PauseGate {
    interface Port {
        Object service() throws Exception;
        boolean muted(Object player) throws Exception;
        boolean eligible(Object player) throws Exception;
        void pause(Object player) throws Exception;
        void later(Runnable task,long ms);
        void failed(Exception e);
    }

    private final Port port;
    private Object knownService;
    private Boolean knownMute;
    private int generation;
    private long latest = -1;
    PauseGate(Port p) { port=p; }

    // DiPO's Application loads before its Service. Seed the initial mute state once it is ready.
    void initialize() {
        knownService=null;
        knownMute=null;
        seed(0,++generation);
    }
    private void seed(final int attempt,final int ticket) {
        if (generation != ticket) return;
        try {
            Object s=port.service();
            if (s != null) { remember(s,port.muted(s)); return; }
        } catch (Exception ignored) { /* service may still be initializing */ }
        if (attempt < 12) port.later(new Runnable(){ public void run(){seed(attempt+1,ticket);} },500);
    }

    private void remember(Object s,boolean mute) { knownService=s; knownMute=mute; }

    // A physical volume change can clear mute without going through release().
    // Re-arm only on an actual MICOM unmute and never issue a command here.
    void refresh() {
        try {
            Object s=port.service();
            if (s != null && !port.muted(s)) {
                generation++;
                remember(s,false);
            }
        } catch (Exception e) { knownService=null; knownMute=null; port.failed(e); }
    }

    void release(final long up,long now) {
        if (up < 0 || up <= latest || now < up || now-up > 1200) return;
        latest = up;
        final int ticket=++generation;
        try {
            final Object s=port.service();
            if (s == null) { knownService=null; knownMute=null; return; }
            final boolean mayPause=knownService == s && Boolean.FALSE.equals(knownMute) && port.eligible(s);
            port.later(new Runnable() { public void run() { confirm(ticket,s,mayPause); } },120);
        } catch (Exception e) { knownService=null; knownMute=null; port.failed(e); }
    }

    private void confirm(final int ticket,final Object s,final boolean mayPause) {
        if (generation != ticket) return;
        try {
            if (port.service()!=s) return;
            final boolean first=port.muted(s);
            port.later(new Runnable() { public void run() {
                if (generation != ticket) return;
                try {
                    if (port.service()!=s) return;
                    boolean last=port.muted(s);
                    remember(s,last);
                    if (first && last && mayPause && port.eligible(s)) port.pause(s);
                } catch (Exception e) { knownService=null; knownMute=null; port.failed(e); }
            } },120);
        } catch (Exception e) { knownService=null; knownMute=null; port.failed(e); }
    }
}
