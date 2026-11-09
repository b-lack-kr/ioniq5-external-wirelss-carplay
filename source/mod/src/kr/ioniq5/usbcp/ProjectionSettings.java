package kr.ioniq5.usbcp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/** Route explicit wireless-settings requests to the bridge's shared BT device list.
 * Wired-settings helpers, pairing, deletion and transport are left to their owners. */
public final class ProjectionSettings {
    public static final String PROJECTION="com.daudio.app.phoneprojection";
    public static final String BLUETOOTH="com.android.btsettings";
    private static final String TARGET="com.android.settings2.bluetooth.DeviceConnectionSettingActivity";
    private static final String ACTION="android.bluetooth.DeviceConnectionSettingActivity.action.LAUNCH";
    private static final String POPUP="com.daudio.app.phoneprojection.wireless.popup.WirelessAlertDialogActivity";
    private static final int LOG_LIMIT=24;

    private ProjectionSettings() {}

    public static boolean supports(String packageName) {
        return PROJECTION.equals(packageName)||BLUETOOTH.equals(packageName);
    }

    public static void install(final ModContext mod,LoadedApp app) {
        if(app==null||app.context==null||!supports(app.packageName))return;
        if(android.os.Process.myUid()!=1000){mod.log("USB_CP_SETTINGS event=wrong_uid");return;}
        BtSettingsRows.install(mod,app);
        final boolean projection=PROJECTION.equals(app.packageName);
        final String point=projection
                ?"phoneprojection.usbcp_settings.open":"btsettings.usbcp_settings.open";
        try {
            Points.method(app.classLoader,point);
            mod.hook(app.classLoader,point,new MethodHook(){
                private int details;
                private void event(String event) {
                    if(details<LOG_LIMIT){details++;mod.log("USB_CP_SETTINGS event="+event);}
                }
                @Override protected void beforeHookedMethod(Param p) {
                    if(!mod.enabled()
                            ||p.args.length!=1||!(p.args[0] instanceof Context))return;
                    Context context=(Context)p.args[0];
                    // The same stock helper also serves settings search. Only
                    // its verified popup Settings caller passes this Activity.
                    if(projection&&!POPUP.equals(context.getClass().getName()))return;
                    Intent intent=new Intent(ACTION).setClassName(BLUETOOTH,TARGET)
                            .putExtra("fr_mode","fr_normal")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    try {
                        ResolveInfo found=context.getPackageManager().resolveActivity(intent,0);
                        if(found==null||found.activityInfo==null||!found.activityInfo.enabled
                                ||!BLUETOOTH.equals(found.activityInfo.packageName)
                                ||!TARGET.equals(found.activityInfo.name)
                                ||found.activityInfo.applicationInfo==null
                                ||!found.activityInfo.applicationInfo.enabled
                                ||found.activityInfo.applicationInfo.uid!=1000){event("unavailable");return;}
                        context.startActivity(intent);
                    }catch(RuntimeException failed){event("launch_failed");return;}
                    // Replace only a completed launch; failed resolution/execution
                    // leaves the verified original helper free to run normally.
                    p.setResult(null);
                    event("opened");
                }
            });
            mod.log("USB_CP_SETTINGS event=ready");
        }catch(Throwable unavailable){mod.log("USB_CP_SETTINGS event=hook_unavailable");}
    }
}
