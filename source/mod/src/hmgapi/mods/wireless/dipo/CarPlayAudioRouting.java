package hmgapi.mods.wireless.dipo;

import java.lang.reflect.Field;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/** Route this bridge's non-call Alt audio through the vehicle navigation channel.
 * Main/music, calls, Siri, gain, focus, mute and wired sessions retain stock behavior.
 * The head unit receives stream/type, not the identity of the originating phone app. */
public final class CarPlayAudioRouting {
    private static final String START="dipo.session_listener.audio_stream_start";
    private static final String STOP="dipo.session_listener.audio_stream_stop";
    private static final String MICOM_START="framework.micom_audio.start_audio";
    private static final String MICOM_END="framework.micom_audio.end_audio";
    private static final String MUSIC="framework.micom_audio.start_dipo_music";
    private static final String OWNER="dipo.session_listener.audio_owner";
    private static final String[] METHODS={START,STOP,MICOM_START,MICOM_END,MUSIC,
        "dipo.carplay.session_started","dipo.carplay.session_finalized",
        "dipo.audio_owner.start_music","dipo.audio_owner.micom","dipo.audio_owner.state",
        "dipo.audio_owner.active","dipo.audio_owner.speech_active",
        "dipo.audio_owner.telematics","dipo.audio_owner.tmu_call","dipo.audio_owner.power_off",
        "dipo.audio_state.phone_call","dipo.audio_state.speech"};
    private final ModContext mod;
    private final ClassLoader cl;
    private final Field ownerField, routeField, musicField;
    private final ThreadLocal<Frame> starts=new ThreadLocal<Frame>();
    private final ThreadLocal<Frame> music=new ThreadLocal<Frame>();
    private Object session;
    private long epoch, musicSuccessRevision;
    private int generation, details;
    private volatile boolean installed;

    private static final class Frame {
        final Frame parent;
        final Object owner, manager, session;
        final long epoch;
        Object listener;
        long musicSuccessRevision;
        boolean requestSeen, altEnded, failedMusic, successfulMusic;
        Frame(Frame parent,Object owner,Object manager,Object session,long epoch) {
            this.parent=parent; this.owner=owner; this.manager=manager;
            this.session=session; this.epoch=epoch;
        }
    }
    private CarPlayAudioRouting(ModContext mod,ClassLoader cl) {
        this.mod=mod; this.cl=cl;
        ownerField=Points.field(cl,OWNER);
        routeField=Points.field(cl,"dipo.audio_owner.alt_status");
        musicField=Points.field(cl,"dipo.audio_owner.music_started");
    }
    public static void install(ModContext mod,LoadedApp app) {
        try {
            for(String point:METHODS) Points.method(app.classLoader,point);
            CarPlayAudioRouting routing=new CarPlayAudioRouting(mod,app.classLoader);
            routing.hooks();
            mod.log("USB_CP_AUDIO_ROUTE ready");
        } catch(Throwable unavailable) { mod.log("USB_CP_AUDIO_ROUTE unavailable"); }
    }
    private void hooks() {
        mod.hook(cl,"dipo.carplay.session_started",new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                synchronized(CarPlayAudioRouting.this) {
                    if(p.getThrowable()==null && p.thisObject==Bridge.sCp) resetSession(p.thisObject);
                }
            }
        });
        mod.hook(cl,"dipo.carplay.session_finalized",new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                synchronized(CarPlayAudioRouting.this) {
                    if(p.thisObject==session) resetSession(null);
                }
            }
        });
        mod.hook(cl,START,new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                synchronized(CarPlayAudioRouting.this) {
                    Frame f=new Frame(starts.get(),null,null,null,-1);
                    if(isAlt(p.args[0])) {
                        invalidate();
                        if(isGuidanceType(p.args[1])) {
                            try {
                                Object o=ownerField.get(p.thisObject),cp=Bridge.sCp;
                                if(live(cp)) {
                                    f=new Frame(starts.get(),o,call("micom",o),cp,epoch);
                                    f.listener=p.thisObject;
                                }
                            } catch(Throwable ignored) { /* Preserve the original callback. */ }
                        }
                    }
                    starts.set(f);
                }
            }
            @Override protected void afterHookedMethod(Param p) {
                Frame f=starts.get();
                if(f!=null) restore(starts,f.parent);
                // No post-start writes: reentrant stop/finalize owns its cleanup.
            }
        });
        mod.hook(cl,STOP,new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                if(isAlt(p.args[0])) synchronized(CarPlayAudioRouting.this) { invalidate(); }
            }
        });
        mod.hook(cl,MICOM_START,new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                int route=number(p.args[0]);
                if(route!=13 && route!=19) return;
                synchronized(CarPlayAudioRouting.this) {
                    Frame f=starts.get();
                    if(f==null || f.owner==null || f.listener==null || f.requestSeen || f.altEnded
                            || f.epoch!=epoch || f.manager!=p.thisObject) return;
                    // OEM q.start makes exactly one Alt allocation. A nested,
                    // unrelated MICOM request must not inherit this authorization.
                    f.requestSeen=true;
                    if(route!=19 || !guard(f.owner,f.manager,f.session)) return;
                    try {
                        if(ownerField.get(f.listener)!=f.owner || routeField.getInt(f.owner)!=2) return;
                        // OEM writes iZ before startAudio and uses it at Alt stop.
                        // Change the pending slot together with the one request;
                        // leave a rejected/uncertain response to the stock path.
                        routeField.setInt(f.owner,1);
                        p.args[0]=Integer.valueOf(13);
                    } catch(Throwable ignored) { /* No retry or fallback allocation. */ }
                }
            }
        });
        mod.hook(cl,MICOM_END,new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                int ended=number(p.args[0]);
                if((ended!=13 && ended!=19) ||
                        (p.getThrowable()==null && !Boolean.TRUE.equals(p.getResult()))) return;
                synchronized(CarPlayAudioRouting.this) {
                    for(Frame f=starts.get();f!=null;f=f.parent) {
                        if(f.manager==p.thisObject && f.epoch==epoch) f.altEnded=true;
                    }
                }
            }
        });
        mod.hook(cl,"dipo.audio_owner.start_music",new MethodHook() {
            @Override protected void beforeHookedMethod(Param p) {
                synchronized(CarPlayAudioRouting.this) {
                    Frame f=new Frame(music.get(),null,null,null,-1);
                    try {
                        Object cp=Bridge.sCp;
                        if(live(cp) && !musicField.getBoolean(p.thisObject))
                            f=new Frame(music.get(),p.thisObject,call("micom",p.thisObject),cp,epoch);
                    } catch(Throwable ignored) { }
                    f.musicSuccessRevision=musicSuccessRevision;
                    music.set(f);
                }
            }
            @Override protected void afterHookedMethod(Param p) {
                Frame f=music.get();
                if(f==null) return;
                restore(music,f.parent);
                synchronized(CarPlayAudioRouting.this) {
                    if(p.getThrowable()!=null || f.owner==null || !f.failedMusic || f.successfulMusic
                            || !live(f.session) || f.epoch!=epoch
                            || f.musicSuccessRevision!=musicSuccessRevision) return;
                    try {
                        musicField.setBoolean(f.owner,false);
                        detail("music_retryable generation="+generation);
                    } catch(Throwable ignored) { }
                }
            }
        });
        mod.hook(cl,MUSIC,new MethodHook() {
            @Override protected void afterHookedMethod(Param p) {
                if(number(p.args[0])!=0 || p.getThrowable()!=null) return;
                synchronized(CarPlayAudioRouting.this) {
                    if(Boolean.TRUE.equals(p.getResult())) musicSuccessRevision++;
                    for(Frame f=music.get();f!=null;f=f.parent) {
                        if(f.owner==null || f.manager!=p.thisObject) continue;
                        if(Boolean.FALSE.equals(p.getResult())) f.failedMusic=true;
                        if(Boolean.TRUE.equals(p.getResult())) f.successfulMusic=true;
                    }
                }
            }
        });
        installed=true;
    }
    private static void restore(ThreadLocal<Frame> local,Frame parent) {
        if(parent==null) local.remove(); else local.set(parent);
    }
    private static boolean isAlt(Object value) {
        return value instanceof Enum && "Alt".equals(((Enum<?>)value).name());
    }
    private static boolean isGuidanceType(Object value) {
        if(!(value instanceof Enum)) return false;
        String name=((Enum<?>)value).name();
        return "Compatibility".equals(name) || "Default".equals(name)
                || "Media".equals(name) || "Alert".equals(name);
    }
    private static int number(Object value) { return value instanceof Number?((Number)value).intValue():-1; }
    private Object call(String point,Object target) { return Points.call(cl,"dipo.audio_owner."+point,target); }
    private boolean live(Object cp) {
        return installed && mod.enabled() && cp!=null && cp==Bridge.sCp && Bridge.sCpWireless
                && !Bridge.sUsb && Bridge.enabled();
    }
    private boolean guard(Object o,Object m,Object cp) {
        try {
            if(o==null || m==null || !live(cp) || call("micom",o)!=m
                    || !Boolean.TRUE.equals(call("active",o))
                    || !Boolean.FALSE.equals(call("speech_active",o))
                    || !Boolean.FALSE.equals(call("telematics",o))
                    || !Boolean.FALSE.equals(call("tmu_call",o))
                    || !Boolean.FALSE.equals(call("power_off",o))) return false;
            Object resources=call("state",o);
            if(resources==null) return false;
            int phone=number(Points.call(cl,"dipo.audio_state.phone_call",resources));
            int speech=number(Points.call(cl,"dipo.audio_state.speech",resources));
            return (phone==0 || phone==2) && (speech==0 || speech==2);
        } catch(Throwable ignored) { return false; }
    }
    private void invalidate() { epoch++; }
    private void resetSession(Object cp) {
        invalidate(); session=cp; generation++; details=0;
    }
    private void detail(String detail) {
        if(details>=24) return;
        details++;
        mod.log("USB_CP_AUDIO_ROUTE "+detail);
    }
}
