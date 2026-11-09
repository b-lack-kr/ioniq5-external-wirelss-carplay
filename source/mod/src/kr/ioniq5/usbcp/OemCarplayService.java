// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Looper;

/** Start only the stock component, as DipoReceiver/DipoActivity do, without a mode command. */
final class OemCarplayService {
    static final String PACKAGE="com.daudio.app.dipo";
    static final String SERVICE=PACKAGE+".service.DipoService";
    static final int STARTED=1,UNAVAILABLE=2,DENIED=3,FAILED=4;
    private OemCarplayService() {}
    static int start(Context context) {
        if(context==null||android.os.Process.myUid()!=1000
                ||Looper.myLooper()==Looper.getMainLooper())return DENIED;
        try {
            Intent intent=new Intent().setClassName(PACKAGE,SERVICE);
            PackageManager pm=context.getPackageManager();
            ResolveInfo found=pm.resolveService(intent,0);
            ServiceInfo service=found==null?null:found.serviceInfo;
            if(service==null||!service.enabled||!service.exported
                    ||!PACKAGE.equals(service.packageName)||!SERVICE.equals(service.name)
                    ||!PACKAGE.equals(service.processName)||service.applicationInfo==null
                    ||!service.applicationInfo.enabled||service.applicationInfo.uid!=1000
                    ||!PACKAGE.equals(service.applicationInfo.packageName))return UNAVAILABLE;
            if(service.permission!=null&&!service.permission.isEmpty()
                    &&pm.checkPermission(service.permission,"com.android.btsettings")!=PackageManager.PERMISSION_GRANTED)return DENIED;
            ComponentName started=context.startService(intent);
            return started!=null&&PACKAGE.equals(started.getPackageName())
                    &&SERVICE.equals(started.getClassName())?STARTED:FAILED;
        }catch(SecurityException denied){return DENIED;}
        catch(RuntimeException unavailable){return FAILED;}
    }
}
