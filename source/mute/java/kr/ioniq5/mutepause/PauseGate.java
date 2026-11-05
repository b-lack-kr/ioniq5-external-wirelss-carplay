package kr.ioniq5.mutepause;

/** Main-looper state. Only undo a pause issued by this gate in the same session. */
final class PauseGate {
    interface Port {
        Object service() throws Exception;
        boolean muted(Object player) throws Exception;
        boolean safe(Object player) throws Exception;
        boolean playing(Object player) throws Exception;
        void pause(Object player) throws Exception;
        void play(Object player) throws Exception;
        void later(Runnable task,long ms);
        void failed(Exception e);
    }

    private static final int MAX_SAMPLES = 8;
    private static final long SAMPLE_MS = 120;
    private final Port port;
    private Object knownService, pausedService;
    private Boolean knownMute;
    private int generation;
    private long latest = -1;
    private boolean sendingCommand, pauseAcknowledged;
    PauseGate(Port p) { port=p; }

    // A disconnected/replaced session must never inherit a previous session's resume request.
    void reset() {
        generation++;
        knownService=null;
        knownMute=null;
        pausedService=null;
        pauseAcknowledged=false;
    }

    // DiPO's Application loads before its Service. Read a baseline without issuing commands.
    void initialize() {
        reset();
        seed(0,generation);
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
    private void unavailable(Exception e) { reset(); port.failed(e); }

    // An explicit media key overrides our automatic behavior. Calls originating
    // synchronously from our own pause/play operation are not user overrides.
    void manualAction() {
        if (sendingCommand) return;
        generation++;
        pausedService=null;
    }

    // Volume can clear MICOM mute without another mute-button release. It can
    // resume a pause we own, but must never create a new pause request.
    void refresh() { begin(false); }

    void release(final long up,long now) {
        if (up < 0 || up <= latest || now < up || now-up > 1200) return;
        latest=up;
        begin(true);
    }

    private void begin(boolean muteKey) {
        final int ticket=++generation;
        try {
            final Object s=port.service();
            if (s == null) { reset(); return; }
            if (knownService != s) {
                pausedService=null;
                remember(s,port.muted(s));
                return; // An event from before we had a baseline cannot own a pause.
            }
            if (!port.safe(s)) { pausedService=null; remember(s,port.muted(s)); return; }
            final boolean target=muteKey && Boolean.FALSE.equals(knownMute);
            final boolean mayPause=target && port.playing(s);
            final boolean mayResume=!target && Boolean.TRUE.equals(knownMute) && pausedService==s;
            poll(ticket,s,target,mayPause,mayResume,0,null,0);
        } catch (Exception e) { unavailable(e); }
    }

    private void acknowledge(final int ticket,final Object s,final int attempt) {
        port.later(new Runnable(){ public void run(){
            if (generation!=ticket || pausedService!=s) return;
            try {
                if (port.service()!=s) { reset(); return; }
                if (!port.safe(s)) { pausedService=null; return; }
                if (!port.playing(s)) { pauseAcknowledged=true; return; }
                if (attempt+1<MAX_SAMPLES) acknowledge(ticket,s,attempt+1);
            } catch (Exception e) { unavailable(e); }
        }},SAMPLE_MS);
    }

    private void poll(final int ticket,final Object s,final boolean target,
            final boolean mayPause,final boolean mayResume,final int attempt,
            final Boolean previous,final int stable) {
        port.later(new Runnable(){ public void run(){
            if (generation != ticket) return;
            try {
                if (port.service()!=s) { reset(); return; }
                final boolean mute=port.muted(s);
                if (!port.safe(s)) { pausedService=null; remember(s,mute); return; }
                final int matches=previous!=null && previous.booleanValue()==mute ? stable+1 : 1;
                if (mute==target && matches>=2) {
                    if (target) {
                        remember(s,true);
                        if (mayPause && port.playing(s)) {
                            pausedService=null;
                            pauseAcknowledged=false;
                            sendingCommand=true;
                            try { port.pause(s); } finally { sendingCommand=false; }
                            // A failed command never creates permission to resume.
                            if (generation==ticket && port.service()==s) {
                                pausedService=s;
                                pauseAcknowledged=!port.playing(s);
                                if (!pauseAcknowledged) acknowledge(ticket,s,0);
                            }
                        }
                    } else {
                        boolean own=mayResume && pausedService==s;
                        boolean playing=port.playing(s);
                        if (own && playing && !pauseAcknowledged && attempt+1<MAX_SAMPLES) {
                            // The phone may not have acknowledged our PAUSE yet.
                            // Do not drop it just because unmute arrived first.
                            poll(ticket,s,target,mayPause,mayResume,attempt+1,mute,matches);
                            return;
                        }
                        remember(s,false);
                        // Consume before invoking play: repeated keys cannot send another play.
                        pausedService=null;
                        if (own && !playing) {
                            sendingCommand=true;
                            try { port.play(s); } finally { sendingCommand=false; }
                        }
                    }
                    return;
                }
                if (attempt+1<MAX_SAMPLES) {
                    poll(ticket,s,target,mayPause,mayResume,attempt+1,mute,matches);
                } else if (matches>=2) {
                    // Stable but unchanged: retain the owned pause for a later real unmute.
                    remember(s,mute);
                } else {
                    // No trustworthy final state. Be conservative rather than autoplay later.
                    reset();
                }
            } catch (Exception e) { unavailable(e); }
        }},SAMPLE_MS);
    }
}
