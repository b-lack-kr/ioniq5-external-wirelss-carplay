// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import android.content.*;
import android.os.*;
import hmgapi.hook.*;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/** USB AP adaptation of HMG-API's Bluetooth CarPlay bridge. No system-wide Wi-Fi spoofing. */
public final class UsbCarplay extends ModEntry {
    private static final String DIPO="com.daudio.app.dipo", IAP2="com.tcc.iap2server";
    private static final String ATTACH="com.ev6.cpwl.BT_ATTACHED", DETACH="com.ev6.cpwl.BT_DETACHED";
    private ApClient client;
    private ModContext mod;
    private LoadedApp app;
    private Method apChanged;
    private Handler main;
    private final java.util.concurrent.atomic.AtomicLong attachRequest=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong wifiRequest=new java.util.concurrent.atomic.AtomicLong();
    private int btComponent;
    @Override public void zygoteInit(ModContext m) throws Throwable {
        if(!Provider.PRODUCT.equals(Provider.property("ro.build.product")) || !"tcc803x".equals(Provider.property("ro.board.platform")))
            throw new IllegalStateException("USB_CP_WRONG_TARGET");
        m.log("USB_CP_ZYGOTE_READY version=1.0.0");
    }
    @Override public void appLoaded(final ModContext m, final LoadedApp a) throws Throwable {
        if (a.context != null && "com.mnsoft.navi".equals(a.packageName)) {
            if (m.settingBoolean("carplay_tbt", true)) yieldNativeDistances(m, a);
            return;
        }
        if(a.context==null || !(DIPO.equals(a.packageName)||IAP2.equals(a.packageName)))return;
        if(android.os.Process.myUid()!=1000)throw new IllegalStateException("USB_CP_APP_UID_MISMATCH");
        mod=m;app=a;main=new Handler(Looper.getMainLooper());
        // Resolve before hooks: install also runs verify-map automatically.
        if(IAP2.equals(a.packageName)) {
            Points.method(a.classLoader,"iap2.service.usbcp_send_wifi");
            Points.method(a.classLoader,"iap2.service.usbcp_send_config");
        }
        client=new ApClient(new ApClient.Listener(){public void changed(Snapshot before,Snapshot after){
            main.post(new Runnable(){public void run(){
                if(apChanged!=null)try{apChanged.invoke(null);}catch(Exception e){mod.log("USB_CP_AP_NOTIFY_FAILED");}
            }});
        }},new ApClient.Audit(){public void event(String detail){m.log(detail);}});
        if(IAP2.equals(a.packageName)) iap2();
        else {
            Class<?> part=m.appPart(a,"hmgapi.mods.wireless.dipo.Dipo");
            apChanged=part.getMethod("usbApChanged");
            // A Java platform interface crosses the app-part loader boundary, never our same-jar classes.
            part.getMethod("startUsb",ModContext.class,LoadedApp.class,Callable.class).invoke(null,m,a,new Callable<Boolean>(){
                public Boolean call(){return current()!=null;}
            });
        }
        client.start();m.log("USB_CP_APP_READY "+a.packageName);
    }
    /** Only the native navigation app's distance/turn writers share these display slots.
     * DiPO's own calls, Android Auto and stock safety/camera messages are outside this hook. */
    private static void yieldNativeDistances(final ModContext mod, final LoadedApp app) {
        MethodHook giveWay = new MethodHook() {
            private boolean seen;
            @Override protected void beforeHookedMethod(Param p) {
                if (Car.carPlayGuiding(app.context)) {
                    p.setResult(null);
                    if (!seen) {
                        seen = true;
                        mod.log("USB_CP_TBT_NATIVE_DISTANCE_YIELDED");
                    }
                }
            }
        };
        String[] writers = { "update_tbt_info", "update_tbt_sub_info", "update_tbt_sub_info_b",
                "update_tbt_sub_info_2c", "update_remain_dis" };
        for (String writer : writers) mod.hook(null, "framework.navigation_cluster." + writer, giveWay);
    }
    private Snapshot current(){return mod!=null && mod.enabled() && client!=null ? client.current():null;}
    private void iap2() {
        final ClassLoader cl=app.classLoader;
        if (mod.settingBoolean("carplay_tbt", true)) {
            mod.hook(cl, "iap2.init_info.check_supports_features", new MethodHook() {
                @Override protected void afterHookedMethod(Param p) {
                    if (p.getThrowable() != null) return;
                    try {
                        Points.field(cl, "iap2.init_info.route_guidance").setInt(null, 1);
                        mod.log("USB_CP_TBT_ADVERTISED");
                    } catch (Exception e) { mod.log("USB_CP_TBT_ADVERTISEMENT_FAILED"); }
                }
            });
        }
        mod.hook(cl,"iap2.service.on_create",new MethodHook(){protected void afterHookedMethod(Param p){
            if(p.getThrowable()!=null)return;
            final Object svc=p.thisObject;
            IntentFilter f=new IntentFilter();f.addAction(ATTACH);f.addAction(DETACH);
            ((Context)svc).registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                if(!mod.enabled())return;
                try {
                    if(ATTACH.equals(i.getAction())) {
                        wifiRequest.incrementAndGet();
                        attach(svc,i,attachRequest.incrementAndGet(),0);
                    }else stopBt(svc);
                }catch(Throwable e){mod.log("USB_CP_IAP_ATTACH_FAILED");}
            }},f,"android.permission.BLUETOOTH_PRIVILEGED",null);
        }});
        mod.hook(cl,"iap2.service_receiver.on_receive",new MethodHook(){protected void beforeHookedMethod(Param p){
            Intent i=(Intent)p.args[1];String act=i==null?null:i.getAction();
            if("android.hardware.usb.action.CARPLAY_DEVICE_ATTACHED".equals(act)||"android.hardware.usb.action.DIGITAL_AUDIO_DEVICE_ATTACHED".equals(act))
                stopBt(Points.get(cl,"iap2.service_receiver.service",p.thisObject));
        }});
        mod.hook(cl,"iap2.service.usbcp_send_wifi",new MethodHook(){protected void beforeHookedMethod(Param p){
            // Override only the bridge's BT/CarPlay sessions; wired sessions retain their own behavior.
            int comp=((Number)Points.call(cl,"iap2.service.get_component",null)).intValue();
            if(comp!=4&&comp!=5)return;
            p.setResult(null);
            sendWifi(p.thisObject,wifiRequest.incrementAndGet(),comp,0);
        }});
    }
    private void attach(final Object svc,final Intent intent,final long token,final int attempt) {
        try { attachNow(svc,intent,token,attempt); } catch(Throwable e) { mod.log("USB_CP_IAP_ATTACH_FAILED"); }
    }
    private void attachNow(final Object svc,final Intent intent,final long token,final int attempt) {
        if(!mod.enabled() || attachRequest.get()!=token)return;
        final int comp=intent.getIntExtra("component",4);if(comp!=4&&comp!=5)return;
        if(current()==null){
            if(attempt<20)main.postDelayed(new Runnable(){public void run(){attach(svc,intent,token,attempt+1);}},250);
            else mod.log("USB_CP_ATTACH_WAIT_AP_TIMEOUT");
            return;
        }
        int live = liveComponent();
        if ((live != 0 && !bridgeComponent(live))
                || (live == 0 && Boolean.TRUE.equals(Points.call(app.classLoader,"iap2.service.is_connected",null)))) {
            mod.log("USB_CP_ATTACH_DEFERRED active_component=" + live);
            return;
        }
        if (btComponent == 0 && bridgeComponent(live)) {
            // The server can outlive the local bridge session. A fresh BT attach
            // must release that wireless transport before queuing the new one.
            btComponent = live;
            mod.log("USB_CP_IAP_RECLAIM component=" + live);
        }
        stopBt(svc);btComponent=comp;
        Points.call(app.classLoader,"iap2.service.set_device_serial",svc,intent.getStringExtra("serial"));
        Points.call(app.classLoader,"iap2.service.receive_connect",svc,comp);
        mod.log("USB_CP_IAP_ATTACH component="+comp);
    }
    private int liveComponent() {
        return ((Number)Points.call(app.classLoader,"iap2.service.get_component",null)).intValue();
    }
    private static boolean bridgeComponent(int component) {
        return component == 4 || component == 5;
    }
    private void stopBt(Object svc) {
        attachRequest.incrementAndGet();
        wifiRequest.incrementAndGet();
        int c=btComponent;
        if(c==0)return;
        btComponent=0;
        int live = liveComponent();
        if (live != 0 && !bridgeComponent(live)) return;
        Points.call(app.classLoader,"iap2.service.set_device_serial",svc,(Object)null);
        Points.call(app.classLoader,"iap2.service.receive_disconnect",svc,c);
    }
    private void sendWifi(final Object svc,final long token,final int comp,final int attempt) {
        try { sendWifiNow(svc,token,comp,attempt); } catch(Throwable e) { mod.log("USB_CP_CREDENTIALS_SESSION_GONE"); }
    }
    private void sendWifiNow(final Object svc,final long token,final int comp,final int attempt) {
        if(!mod.enabled() || token!=wifiRequest.get())return;
        int liveComp=((Number)Points.call(app.classLoader,"iap2.service.get_component",null)).intValue();
        if(liveComp!=comp)return;
        Snapshot s=current();
        if(s==null){
            if(attempt<20)main.postDelayed(new Runnable(){public void run(){sendWifi(svc,token,comp,attempt+1);}},250);
            else mod.log("USB_CP_CREDENTIALS_WAIT_AP_TIMEOUT");
            return;
        }
        try {
            // Existing iAP2 implementation maps WPA/WPA2 PSK to security type 2.
            Object ok=Points.call(app.classLoader,"iap2.service.usbcp_send_config",svc,s.ssid,true,s.password,true,2,true,36);
            mod.log(Boolean.TRUE.equals(ok)?"USB_CP_CREDENTIALS_SENT frequency=5180":"USB_CP_CREDENTIALS_SEND_FAILED");
        }catch(Throwable e){mod.log("USB_CP_CREDENTIALS_SEND_EXCEPTION");}
    }
    @Override public void onEnabled(ModContext m,boolean on){
        if(!on && client!=null){client.stop();attachRequest.incrementAndGet();wifiRequest.incrementAndGet();}
        if(apChanged!=null)try{apChanged.invoke(null);}catch(Exception ignored){}
    }
}
