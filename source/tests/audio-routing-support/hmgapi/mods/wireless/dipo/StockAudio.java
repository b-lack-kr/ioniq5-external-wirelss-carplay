package hmgapi.mods.wireless.dipo;

import hmgapi.hook.ModContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Only the branches needed for hook integration; copied stock order is documented in test assertions. */
public final class StockAudio {
    public enum Stream { Main(100), Alt(101), MainHigh(102), Invalid(-1);
        private final int value; Stream(int v) { value = v; } public int getValue() { return value; }
    }
    public enum Type { Unknown(-1), Compatibility(0), Default(1), Media(2), Telephony(3), SpeechRecognition(4), Alert(5);
        private final int value; Type(int v) { value = v; } public int getValue() { return value; }
    }
    public static Object run(String point, Object target, Object[] args, ModContext.Original original) {
        try { return ModContext.dispatch(point, target, args, original); }
        catch (RuntimeException e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException(e); }
    }

    public static final class Micom {
        public final List<String> calls = new ArrayList<String>();
        public final ArrayDeque<Boolean> starts = new ArrayDeque<Boolean>();
        public final ArrayDeque<Boolean> ends = new ArrayDeque<Boolean>();
        public final ArrayDeque<Boolean> music = new ArrayDeque<Boolean>();
        public int current;
        public Runnable duringStart;
        public Runnable duringEnd;
        public Runnable duringMusic;
        public int startsSeen, endsSeen, throwStartAt, throwEndAt;
        public boolean startAudio(final int requested) {
            final Object[] args=new Object[]{requested};
            return (Boolean)run("framework.micom_audio.start_audio", this, args, new ModContext.Original() {
                public Object run() {
                    int type=((Number)args[0]).intValue();
                    startsSeen++;
                    boolean ok = starts.isEmpty() || starts.remove();
                    calls.add("start:" + type + ":" + ok);
                    if (ok) current = type;
                    if (duringStart != null) { Runnable r = duringStart; duringStart = null; r.run(); }
                    if(startsSeen==throwStartAt) throw new IllegalStateException("accepted start reply lost");
                    return ok;
                }
            });
        }
        public boolean endAudio(final int type) {
            return (Boolean)run("framework.micom_audio.end_audio", this, new Object[]{type}, new ModContext.Original() {
                public Object run() {
                    endsSeen++;
                    boolean ok = ends.isEmpty() || ends.remove();
                    calls.add("end:" + type + ":" + ok);
                    if (ok && current == type) current = 0;
                    if (duringEnd != null) { Runnable r = duringEnd; duringEnd = null; r.run(); }
                    if(endsSeen==throwEndAt) throw new IllegalStateException("end reply lost");
                    return ok;
                }
            });
        }
        public boolean startDiPOMusic(final int arg) {
            return (Boolean)run("framework.micom_audio.start_dipo_music", this, new Object[]{arg}, new ModContext.Original() {
                public Object run() {
                    boolean ok = music.isEmpty() || music.remove();
                    calls.add("music:" + arg + ":" + ok);
                    if (duringMusic != null) { Runnable r = duringMusic; duringMusic = null; r.run(); }
                    return ok;
                }
            });
        }
        public int getCurrentStreamType() { return current; }
        public boolean setMute(int type) { calls.add("mute:" + type); return true; }
    }
    public static final class Mode {
        public int main = 1, phone, speech;
        public int getMainAudio() { return main; }
        public int getPhoneCall() { return phone; }
        public int getSpeech() { return speech; }
    }
    public static final class Service {
        public Micom audio = new Micom();
        public Mode mode = new Mode();
        public boolean hk, active = true, telematics, call, speech, tmu, powerOff;
        public int iY, iZ;
        public boolean throwAfterAlt;
        public Runnable beforeAltRequest;
        public synchronized Micom fK() { return audio; }
        public synchronized Mode fM() { return mode; }
        public boolean er() { return active; }
        public synchronized boolean fX() { return telematics; }
        public boolean ew() { return mode != null && mode.getSpeech() == 1 && mode.getPhoneCall() != 1; }
        public synchronized boolean ch() { return speech; }
        public boolean cN() { return tmu; }
        public boolean isPowerOff() { return powerOff; }
        public void eL() {
            run("dipo.audio_owner.start_music", this, new Object[0], new ModContext.Original() {
                public Object run() {
                    if (fK() == null || fM() == null || hk) return null;
                    fK().startDiPOMusic(0);
                    fK().setMute(3);
                    hk = true; // Exact stock bug: false MICOM acceptance still latches this.
                    return null;
                }
            });
        }
    }
    public static final class Listener {
        public Service R;
        public Listener(Service service) { R = service; }
        public void onAudioStreamStart(final Stream stream, final Type type, final int rate) {
            run("dipo.session_listener.audio_stream_start", this, new Object[]{stream, type, rate}, new ModContext.Original() {
                public Object run() {
                    if (type == Type.Unknown || rate < 0 || R.fX() || (R.ew() && type == Type.Media) || !R.er()) return null;
                    if (stream == Stream.Alt && R.fK() != null) {
                        R.iZ = R.iY == 1 ? 1 : 2;
                        if(R.beforeAltRequest!=null){Runnable r=R.beforeAltRequest;R.beforeAltRequest=null;r.run();}
                        R.fK().startAudio(R.iZ == 1 ? 13 : 19);
                    }
                    if (R.throwAfterAlt) throw new IllegalStateException("injected original failure");
                    return null;
                }
            });
        }
        public void onAudioStreamStop(final Stream stream) {
            run("dipo.session_listener.audio_stream_stop", this, new Object[]{stream}, new ModContext.Original() {
                public Object run() {
                    if (stream == Stream.Alt && R.fK() != null) {
                        if (R.iZ == 1) R.fK().endAudio(13);
                        else if (R.iZ == 2) R.fK().endAudio(19);
                    }
                    return null; // Stock leaves stale iZ; wrapper must own any subsequent correction.
                }
            });
        }
        public void aI() {
            run("dipo.session_listener.duck", this, new Object[0], new ModContext.Original() {
                public Object run() { R.iY = 1; return null; }
            });
        }
        public void aJ() {
            run("dipo.session_listener.unduck", this, new Object[0], new ModContext.Original() {
                public Object run() { R.iY = 2; return null; }
            });
        }
        public void aF() {
            run("dipo.session_listener.iap2_disconnected", this, new Object[0], new ModContext.Original() {
                public Object run() { R.iY = 0; R.iZ = 0; return null; }
            });
        }
    }
    public static final class CarPlay {
        public void onSessionStarted(final int transport) {
            run("dipo.carplay.session_started", this, new Object[]{transport}, new ModContext.Original() {
                public Object run() { return null; }
            });
        }
        public void onSessionFinalized(final int transport) {
            run("dipo.carplay.session_finalized", this, new Object[]{transport}, new ModContext.Original() {
                public Object run() { return null; }
            });
        }
    }
}
