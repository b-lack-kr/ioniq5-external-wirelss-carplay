package hmgapi.mods.wireless.dipo;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import static hmgapi.mods.wireless.dipo.StockAudio.*;

/** Drives the real wrapper callbacks, including nested MICOM calls and stock latch bugs. */
public final class AudioRoutingIntegrationTest {
    static int scenarios;
    static void method(String id, Class<?> owner, String name, Class<?>... args) throws Exception {
        Points.methods.put(id, owner.getMethod(name, args));
    }
    static void mappings() throws Exception {
        Points.methods.clear(); Points.fields.clear();
        method("dipo.session_listener.audio_stream_start",Listener.class,"onAudioStreamStart",Stream.class,Type.class,int.class);
        method("dipo.session_listener.audio_stream_stop",Listener.class,"onAudioStreamStop",Stream.class);
        method("dipo.session_listener.duck",Listener.class,"aI");
        method("dipo.session_listener.unduck",Listener.class,"aJ");
        method("dipo.carplay.session_started",CarPlay.class,"onSessionStarted",int.class);
        method("dipo.carplay.session_finalized",CarPlay.class,"onSessionFinalized",int.class);
        method("framework.micom_audio.start_audio",Micom.class,"startAudio",int.class);
        method("framework.micom_audio.end_audio",Micom.class,"endAudio",int.class);
        method("framework.micom_audio.start_dipo_music",Micom.class,"startDiPOMusic",int.class);
        method("framework.micom_audio.current_stream",Micom.class,"getCurrentStreamType");
        method("dipo.audio_owner.start_music",Service.class,"eL");
        method("dipo.audio_owner.micom",Service.class,"fK");
        method("dipo.audio_owner.state",Service.class,"fM");
        method("dipo.audio_owner.active",Service.class,"er");
        method("dipo.audio_owner.speech_active",Service.class,"ew");
        method("dipo.audio_owner.telematics",Service.class,"fX");
        method("dipo.audio_owner.tmu_call",Service.class,"cN");
        method("dipo.audio_owner.power_off",Service.class,"isPowerOff");
        method("dipo.audio_state.phone_call",Mode.class,"getPhoneCall");
        method("dipo.audio_state.speech",Mode.class,"getSpeech");
        Points.fields.put("dipo.session_listener.audio_owner",Listener.class.getField("R"));
        Points.fields.put("dipo.audio_owner.duck_status",Service.class.getField("iY"));
        Points.fields.put("dipo.audio_owner.alt_status",Service.class.getField("iZ"));
        Points.fields.put("dipo.audio_owner.music_started",Service.class.getField("hk"));
    }
    static final class Rig {
        final ModContext mod = new ModContext();
        final Service service = new Service();
        final Listener listener = new Listener(service);
        final CarPlay cp = new CarPlay();
        Rig() throws Exception { this(null); }
        Rig(String reject) throws Exception {
            mappings(); Bridge.sCp = cp; Bridge.sCpWireless = true;
            Bridge.sCpClient = true; Bridge.sCpConnected = true; Bridge.sUsb = false; Bridge.on = true;
            mod.rejectPoint = reject;
            CarPlayAudioRouting.install(mod,new LoadedApp());
            if(reject == null) check(mod.logs.contains("USB_CP_AUDIO_ROUTE ready"),"wrapper installed");
            cp.onSessionStarted(1);
        }
        void alt() { listener.onAudioStreamStart(Stream.Alt,Type.Default,48000); }
        void stop() { listener.onAudioStreamStop(Stream.Alt); }
        void calls(String... expected) { eq(service.audio.calls, Arrays.asList(expected), "MICOM call sequence"); }
        void clean() throws Exception {
            check(mod.hookErrors.isEmpty(),"no swallowed hook exception: "+mod.hookErrors);
            Object callback=mod.hooks.get("dipo.session_listener.audio_stream_start").get(0);
            Field outer=callback.getClass().getDeclaredField("this$0"); outer.setAccessible(true);
            Object routing=outer.get(callback);
            for(String name:new String[]{"starts","music"}) {
                Field f=routing.getClass().getDeclaredField(name); f.setAccessible(true);
                check(((ThreadLocal<?>)f.get(routing)).get()==null,"thread frame cleaned: "+name);
            }
            scenarios++;
        }
    }
    static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    static void eq(Object a,Object b,String message) { check(a==null?b==null:a.equals(b),message+" expected="+b+" actual="+a); }

    static void noDuckUsesNavigationImmediately() throws Exception {
        Rig r=new Rig();r.alt();
        r.calls("start:13:true");check(r.service.iZ==1,"navigation cleanup slot selected");
        check(r.service.audio.current==13,"actual MICOM sees changed argument");
        r.stop();r.calls("start:13:true","end:13:true");r.clean();
    }
    static void unduckCannotDemoteActiveNavigation() throws Exception {
        Rig r=new Rig();r.listener.aI();r.alt();r.listener.aJ();r.listener.aJ();r.listener.aI();
        r.calls("start:13:true");check(r.service.iZ==1,"duck changes do not rewrite pending route");
        r.stop();r.calls("start:13:true","end:13:true");r.clean();
    }
    static void lateDuckDoesNotRestartAudio() throws Exception {
        Rig r=new Rig();r.alt();r.listener.aI();r.listener.aJ();r.stop();r.listener.aI();
        r.calls("start:13:true","end:13:true");r.clean();
    }
    static void repeatedPromptsUseNavigationWithoutDuck() throws Exception {
        Rig r=new Rig();r.alt();r.stop();r.alt();r.stop();
        r.calls("start:13:true","end:13:true","start:13:true","end:13:true");r.clean();
    }
    static void secondaryTypesHaveNoPhoneAppIdentity() throws Exception {
        for(Type type:new Type[]{Type.Compatibility,Type.Default,Type.Media,Type.Alert}){
            Rig r=new Rig();r.listener.onAudioStreamStart(Stream.Alt,type,48000);r.stop();
            r.calls("start:13:true","end:13:true");r.clean();
        }
    }
    static void callAndSiriTypesRemainStock() throws Exception {
        for(Type type:new Type[]{Type.Telephony,Type.SpeechRecognition}){
            Rig r=new Rig();r.listener.onAudioStreamStart(Stream.Alt,type,48000);r.stop();
            r.calls("start:19:true","end:19:true");r.clean();
        }
        Rig r=new Rig();r.listener.onAudioStreamStart(Stream.Alt,Type.Unknown,48000);
        r.calls();r.clean();
    }
    static void musicStreamsRemainStock() throws Exception {
        for(Stream stream:new Stream[]{Stream.Main,Stream.MainHigh}){
            for(Type type:Type.values()){
                Rig r=new Rig();r.listener.onAudioStreamStart(stream,type,48000);r.listener.aI();r.listener.aJ();
                r.listener.onAudioStreamStop(stream);r.calls();r.clean();
            }
        }
    }
    static void directUnownedRequestsRemainStock() throws Exception {
        Rig r=new Rig();r.service.audio.startAudio(19);r.calls("start:19:true");r.clean();
    }
    static void wiredAndDisabledSessionsRemainStock() throws Exception {
        for(int reason=0;reason<4;reason++){
            Rig r=new Rig();
            if(reason==0)Bridge.sCpWireless=false;
            if(reason==1)Bridge.sUsb=true;
            if(reason==2)Bridge.on=false;
            if(reason==3)r.mod.on=false;
            r.alt();r.listener.aI();r.stop();r.calls("start:19:true","end:19:true");r.clean();
        }
    }
    static void oemAndCallGuardsRemainAuthoritative() throws Exception {
        for(int reason=0;reason<8;reason++){
            Rig r=new Rig();
            if(reason==0)r.service.active=false;
            if(reason==1)r.service.telematics=true;
            if(reason==2)r.service.mode.speech=1;
            if(reason==3)r.service.tmu=true;
            if(reason==4)r.service.powerOff=true;
            if(reason==5)r.service.mode.phone=1;
            if(reason==6)r.service.mode=null;
            if(reason==7)r.service.audio=null;
            r.listener.onAudioStreamStart(Stream.Alt,Type.Media,48000);
            if(reason==0||reason==1||reason==2||reason==7){if(r.service.audio!=null)r.calls();}
            else r.calls("start:19:true");
            r.clean();
        }
    }
    static void rejectedNavigationDoesNotFallbackOrRetry() throws Exception {
        Rig r=new Rig();r.service.audio.starts.add(false);r.alt();r.listener.aI();r.listener.aJ();
        r.calls("start:13:false");check(r.service.audio.current==0,"no false accepted allocation");
        check(r.service.iZ==1,"OEM retains attempted route for normal cleanup");
        r.stop();r.calls("start:13:false","end:13:true");r.clean();
    }
    static void failedEndDoesNotCreateAnotherAllocation() throws Exception {
        Rig r=new Rig();r.alt();r.service.audio.ends.add(false);r.stop();r.listener.aI();r.listener.aJ();
        r.calls("start:13:true","end:13:false");r.clean();
    }
    static void stockExceptionDoesNotTriggerExtraRequests() throws Exception {
        Rig r=new Rig();r.service.throwAfterAlt=true;
        try{r.alt();throw new AssertionError("stock error suppressed");}catch(IllegalStateException expected){}
        r.listener.aI();r.listener.aJ();r.calls("start:13:true");
        r.service.throwAfterAlt=false;r.stop();r.calls("start:13:true","end:13:true");r.clean();
    }
    static void uncertainStartUsesNormalCleanup() throws Exception {
        Rig r=new Rig();r.service.audio.throwStartAt=1;
        try{r.alt();throw new AssertionError("Binder error suppressed");}catch(IllegalStateException expected){}
        r.listener.aI();r.listener.aJ();r.stop();
        r.calls("start:13:true","end:13:true");r.clean();
    }
    static void finalizedDuringHardwareRequestCannotRestoreSlot() throws Exception {
        final Rig r=new Rig();r.service.audio.duringStart=new Runnable(){public void run(){r.cp.onSessionFinalized(1);r.service.iZ=0;}};
        r.alt();r.listener.aI();r.listener.aJ();r.calls("start:13:true");
        check(r.service.iZ==0,"finalize cleanup wins");r.clean();
    }
    static void reentrantStopCannotRestoreSlot() throws Exception {
        final Rig r=new Rig();r.service.audio.duringStart=new Runnable(){public void run(){r.stop();r.service.iZ=0;}};
        r.alt();r.listener.aI();r.listener.aJ();r.calls("start:13:true","end:13:true");
        check(r.service.iZ==0,"reentrant stop cleanup wins");r.clean();
    }
    static void managerChangeBeforeRequestIsNotCaptured() throws Exception {
        final Rig r=new Rig();final Micom first=r.service.audio;
        r.service.beforeAltRequest=new Runnable(){public void run(){r.service.audio=new Micom();}};
        r.alt();check(first.calls.isEmpty(),"old manager unused");r.calls("start:19:true");
        check(r.service.iZ==2,"new manager pending route untouched");r.clean();
    }
    static void ownerChangeBeforeRequestIsNotCaptured() throws Exception {
        final Rig r=new Rig();final Service next=new Service();next.audio=r.service.audio;next.iZ=2;
        r.service.beforeAltRequest=new Runnable(){public void run(){r.listener.R=next;}};
        r.alt();r.calls("start:19:true");check(r.service.iZ==2&&next.iZ==2,"both owners untouched");r.clean();
    }
    static void sessionChangeBeforeRequestIsNotCaptured() throws Exception {
        final Rig r=new Rig();r.service.beforeAltRequest=new Runnable(){public void run(){Bridge.sCp=new CarPlay();}};
        r.alt();r.calls("start:19:true");check(r.service.iZ==2,"previous session slot unchanged");r.clean();
    }
    static void nestedUnrelatedRequestDoesNotInheritScope() throws Exception {
        final Rig r=new Rig();r.service.audio.duringStart=new Runnable(){public void run(){r.service.audio.startAudio(19);}};
        r.alt();r.calls("start:13:true","start:19:true");r.clean();
    }
    static void nestedNewAltDoesNotRestoreOldOwner() throws Exception {
        final Rig r=new Rig();r.service.audio.duringStart=new Runnable(){public void run(){r.alt();r.stop();r.service.iZ=0;}};
        r.alt();r.listener.aI();r.listener.aJ();r.calls("start:13:true","start:13:true","end:13:true");
        check(r.service.iZ==0,"nested cleanup not overwritten");r.clean();
    }
    static void partialInstallCannotRewriteRequests() throws Exception {
        Rig r=new Rig("framework.micom_audio.start_dipo_music");
        check(r.mod.logs.contains("USB_CP_AUDIO_ROUTE unavailable"),"injected partial hook failure");
        r.alt();r.listener.aI();r.stop();r.calls("start:19:true","end:19:true");r.clean();
    }
    static void finalizedSessionCannotRewriteRequests() throws Exception {
        Rig r=new Rig();r.cp.onSessionFinalized(1);Bridge.sCp=null;
        r.alt();r.calls("start:19:true");r.clean();
    }
    static void failedMusicRetry() throws Exception {
        Rig r=new Rig(); r.service.audio.music.add(false); r.service.eL();
        check(!r.service.hk,"rejected music is retryable");
        r.service.eL(); check(r.service.hk,"next stock call accepts music"); r.service.eL();
        r.calls("music:0:false","mute:3","music:0:true","mute:3"); r.clean();
    }
    static void prelatchedMusicNoop() throws Exception {
        Rig r=new Rig(); r.service.hk=true; r.service.eL(); r.calls();
        check(r.service.hk,"existing accepted latch preserved"); r.clean();
    }
    static void musicNullNoop() throws Exception {
        Rig r=new Rig(); r.service.mode=null; r.service.eL(); r.calls();
        check(!r.service.hk,"stock null-state noop"); r.clean();
    }
    static void nestedMusicAcceptedNotCleared() throws Exception {
        final Rig r=new Rig(); r.service.audio.music.add(false);
        r.service.audio.duringMusic=new Runnable(){public void run(){r.service.eL();}};
        r.service.eL(); check(r.service.hk,"nested accepted music is not overwritten by outer false");
        r.service.eL(); r.calls("music:0:false","music:0:true","mute:3","mute:3"); r.clean();
    }
    static void concurrentMusicAcceptedNotCleared() throws Exception {
        final Rig r=new Rig(); r.service.audio.music.add(false);
        final Throwable[] failed=new Throwable[1];
        r.service.audio.duringMusic=new Runnable(){public void run(){
            Thread t=new Thread(new Runnable(){public void run(){
                try {r.service.eL();} catch(Throwable e){failed[0]=e;}
            }},"fixture-music-callback");
            t.start();
            try {t.join(2000);} catch(InterruptedException e){throw new AssertionError(e);}
            check(!t.isAlive(),"music hook callback must complete on another thread");
        }};
        r.service.eL(); check(failed[0]==null,"concurrent original succeeded");
        check(r.service.hk,"concurrently accepted music is not overwritten by older false");
        r.service.eL(); r.calls("music:0:false","music:0:true","mute:3","mute:3"); r.clean();
    }
    public static void main(String[] args) throws Exception {
        noDuckUsesNavigationImmediately();
        unduckCannotDemoteActiveNavigation();
        lateDuckDoesNotRestartAudio();
        repeatedPromptsUseNavigationWithoutDuck();
        secondaryTypesHaveNoPhoneAppIdentity();
        callAndSiriTypesRemainStock();
        musicStreamsRemainStock();
        directUnownedRequestsRemainStock();
        wiredAndDisabledSessionsRemainStock();
        oemAndCallGuardsRemainAuthoritative();
        rejectedNavigationDoesNotFallbackOrRetry();
        failedEndDoesNotCreateAnotherAllocation();
        stockExceptionDoesNotTriggerExtraRequests();
        uncertainStartUsesNormalCleanup();
        finalizedDuringHardwareRequestCannotRestoreSlot();
        reentrantStopCannotRestoreSlot();
        managerChangeBeforeRequestIsNotCaptured();
        ownerChangeBeforeRequestIsNotCaptured();
        sessionChangeBeforeRequestIsNotCaptured();
        nestedUnrelatedRequestDoesNotInheritScope();
        nestedNewAltDoesNotRestoreOldOwner();
        partialInstallCannotRewriteRequests();
        finalizedSessionCannotRewriteRequests();
        failedMusicRetry();
        prelatchedMusicNoop();
        musicNullNoop();
        nestedMusicAcceptedNotCleared();
        concurrentMusicAcceptedNotCleared();
        System.out.println("AudioRoutingIntegrationTest PASS scenarios="+scenarios);
    }
}
