// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observe only the verified OEM animation-status sender in its own process.
 * No broadcast receiver, animation replacement, or network work on the OEM thread. */
public final class WelcomePower {
    static final String PACKAGE="com.mobis.welcome";
    static final String STATUS="welcome.service.animation_status", MODE="welcome.service.mode";
    static boolean target(LoadedApp app,int uid) {
        return app!=null && app.context!=null && uid==1000 && PACKAGE.equals(app.packageName)
                && PACKAGE.equals(app.processName);
    }
    public static void install(final ModContext mod,final LoadedApp app) {
        if(!target(app,android.os.Process.myUid()))return;
        try {
            final Method status=Points.method(app.classLoader,STATUS);
            final Method mode=Points.method(app.classLoader,MODE);
            if(!"com.mobis.welcome.WelcomeService".equals(status.getDeclaringClass().getName())
                    || status.getReturnType()!=Void.TYPE || Modifier.isStatic(status.getModifiers())
                    || status.getParameterTypes().length!=1 || status.getParameterTypes()[0]!=Integer.TYPE
                    || mode.getDeclaringClass()!=status.getDeclaringClass() || mode.getReturnType()!=Integer.TYPE
                    || !Modifier.isStatic(mode.getModifiers()) || mode.getParameterTypes().length!=0)
                throw new IllegalArgumentException("animation signature");
            final OemPower.Sink sink=OemPower.createAnimationSink(mod);
            final AtomicBoolean readFailed=new AtomicBoolean();
            mod.hook(app.classLoader,STATUS,new MethodHook(){
                @Override protected void beforeHookedMethod(Param p) {
                    if(!mod.enabled() || p.thisObject==null || p.thisObject.getClass()!=status.getDeclaringClass()
                            || p.args==null || p.args.length!=1 || !(p.args[0] instanceof Integer)
                            || ((Integer)p.args[0]).intValue()!=1)return;
                    try {
                        // Exact 260902 sender: e(1), getMode()==0 emits Welcome start;
                        // e(1), getMode()==1 emits Goodbye start. e(0) only ends playback.
                        int value=((Integer)mode.invoke(null)).intValue();
                        if(value!=0 && value!=1)return;
                        sink.offer(value==1);
                        mod.log("USB_CP_ANIMATION_POWER event="+(value==1?"goodbye_start":"welcome_start"));
                    } catch(Throwable ignored) {
                        if(readFailed.compareAndSet(false,true))mod.log("USB_CP_ANIMATION_POWER event=read_failed");
                    }
                }
            });
            mod.log("USB_CP_ANIMATION_POWER event=ready");
        } catch(Throwable ignored) {mod.log("USB_CP_ANIMATION_POWER event=unavailable");}
    }
}
