package kr.ioniq5.mutepause;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Bind all required symbols up front; the package installer pins the Korean DiPO ODEX. */
final class Player {
    private final Method service, active, powerOff, audio, mode, owners, userMute, currentMode;
    private final Method pendingIvr, telematics, siriFocus, phone, callStatus, speech, pause;
    private final Field playing;

    Player(ClassLoader cl) throws Exception {
        Class<?> d = Class.forName("com.daudio.app.dipo.service.DipoService",false,cl);
        service=method(d,"bC"); active=method(d,"er"); powerOff=method(d,"isPowerOff");
        audio=method(d,"fK"); mode=method(d,"fL"); owners=method(d,"fM");
        currentMode=mode.getReturnType().getMethod("getCurrentMode");
        userMute=audio.getReturnType().getMethod("getUserMuteState");
        pendingIvr=method(d,"fX"); telematics=method(d,"cN"); siriFocus=method(d,"ch"); pause=method(d,"pause");
        callStatus=method(owners.getReturnType(),"getCallStatus");
        phone=method(owners.getReturnType(),"getPhoneCall"); speech=method(owners.getReturnType(),"getSpeech");
        playing=d.getDeclaredField("go"); playing.setAccessible(true);
    }

    private static Method method(Class<?> owner,String name) throws Exception {
        Method m=owner.getDeclaredMethod(name); m.setAccessible(true); return m;
    }
    Object service() throws Exception { return service.invoke(null); }
    boolean muted(Object s) throws Exception { return (Boolean)userMute.invoke(audio.invoke(s)); }
    boolean eligible(Object s) throws Exception {
        if (!(Boolean)active.invoke(s) || (Boolean)powerOff.invoke(s) || !playing.getBoolean(s)
                || (Boolean)pendingIvr.invoke(s) || (Boolean)telematics.invoke(s) || (Boolean)siriFocus.invoke(s)) return false;
        Object m=mode.invoke(s), own=owners.invoke(s);
        return m != null && own != null && ((Integer)currentMode.invoke(m))==115
                && ((Integer)callStatus.invoke(own)) == 0 && ((Integer)phone.invoke(own)) != 1 && ((Integer)speech.invoke(own)) != 1;
    }
    void pause(Object s) throws Exception { pause.invoke(s); }
}
