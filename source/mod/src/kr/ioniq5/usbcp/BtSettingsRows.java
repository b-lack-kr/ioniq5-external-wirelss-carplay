// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.ImageButton;
import android.widget.Toast;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Reuse the OEM per-phone projection slot. Never changes the AA row or BT profile action. */
public final class BtSettingsRows {
    private static final String PKG="com.android.btsettings";
    private static final int PHONE=0x7f0800e7, CP=0x7f0800ed, PROGRESS=0x7f0800ef;
    private static final int LABEL=0x7f080006, BUTTONS=0x7f0800ee, PROFILES=0x7f0800e6;
    private static final int CP_ON=0x7f020017,CP_OFF=0x7f020018;
    private static final long STATUS_INTERVAL_MS=1500,STATUS_FRESH_MS=4000;
    private static boolean installed;
    private final ModContext mod;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final RowConnectState request=new RowConnectState();
    private final Map<View,Row> rows=new WeakHashMap<View,Row>();
    private final Field owner,hidden,manager,pending,reserved;
    private final Method restricted,btcm,wireless,vendor;
    private ExecutorService worker;
    private WeakReference<Row> active=new WeakReference<Row>(null);
    private int logs;
    private boolean statusScheduled,statusInFlight;
    private long statusAt;
    private long serviceAttempt,serviceRequested,serverAnswered;
    private int serviceResult;
    private final Runnable statusTick=new Runnable(){public void run(){statusScheduled=false;refreshStatus();}};

    private BtSettingsRows(ModContext m,LoadedApp a)throws Exception {
        mod=m;
        owner=Points.field(a.classLoader,"btsettings.usbcp_rows.owner");
        hidden=Points.field(a.classLoader,"btsettings.usbcp_devices.hidden");
        manager=Points.field(a.classLoader,"btsettings.usbcp_devices.manager");
        pending=Points.field(a.classLoader,"btsettings.usbcp_devices.pending");
        reserved=Points.field(a.classLoader,"btsettings.usbcp_devices.reserved");
        restricted=Points.method(a.classLoader,"btsettings.usbcp_devices.restricted");
        btcm=Points.method(a.classLoader,"btsettings.usbcp_manager.btcm");
        wireless=Points.method(a.classLoader,"btsettings.usbcp_manager.wireless");
        vendor=BluetoothDevice.class.getMethod("getVendor");
        Points.method(a.classLoader,"btsettings.usbcp_rows.bind");
        Points.method(a.classLoader,"btsettings.usbcp_devices.destroy");
    }
    public static synchronized void install(final ModContext m,final LoadedApp a) {
        if(installed||a==null||a.context==null||!PKG.equals(a.packageName)
                ||!PKG.equals(a.processName)||android.os.Process.myUid()!=1000)return;
        try {
            final BtSettingsRows ui=new BtSettingsRows(m,a);
            m.hook(a.classLoader,"btsettings.usbcp_rows.bind",new MethodHook(){
                @Override protected void beforeHookedMethod(Param p) {
                    if(p.args!=null&&p.args.length==3&&p.args[1] instanceof View)
                        ui.restore((View)p.args[1]);
                }
                @Override protected void afterHookedMethod(Param p) {
                    if(p.getThrowable()==null&&p.getResult() instanceof View&&p.thisObject instanceof Adapter
                            &&p.args!=null&&p.args.length==3&&p.args[0] instanceof Integer)
                        ui.bind((Adapter)p.thisObject,((Integer)p.args[0]).intValue(),(View)p.getResult());
                }
            });
            m.hook(a.classLoader,"btsettings.usbcp_devices.destroy",new MethodHook(){
                @Override protected void beforeHookedMethod(Param p) {ui.destroy(p.thisObject);}
            });
            installed=true;ui.event("ready");
        }catch(Throwable unavailable){m.log("USB_CP_SETTINGS event=rows_unavailable");}
    }
    private void event(String value){if(logs++<48)mod.log("USB_CP_SETTINGS event=rows_"+value);}
    private static final class Row {
        final WeakReference<View> view;
        final WeakReference<Adapter> adapter;
        final WeakReference<Activity> activity;
        final String address;
        final int position;
        final int[] widths;
        boolean statusQueried,connected;
        long statusRequested,statusReceived;
        Row(View v,Adapter a,Activity activity,String address,int position,int[] widths) {
            view=new WeakReference<View>(v);adapter=new WeakReference<Adapter>(a);
            this.activity=new WeakReference<Activity>(activity);this.address=address;this.position=position;this.widths=widths;
        }
    }
    private void restore(View v) {
        Row old=rows.remove(v);
        if(old==null)return;
        int[] ids={LABEL,BUTTONS,PROFILES};
        for(int i=0;i<ids.length;i++) {
            View child=v.findViewById(ids[i]);
            if(child!=null&&child.getLayoutParams()!=null) {
                ViewGroup.LayoutParams p=child.getLayoutParams();p.width=old.widths[i];child.setLayoutParams(p);
            }
        }
        // The original getView now rebinds all three OEM listeners and visibility.
    }
    private boolean enabled(){return mod.enabled()&&mod.settingBoolean("external_dongle",true);}
    private void bind(Adapter adapter,int position,View v) {
        if(!enabled()||Looper.myLooper()!=Looper.getMainLooper())return;
        try {
            Object obj=owner.get(adapter);
            if(!(obj instanceof Activity)||position<0||position>=adapter.getCount())return;
            Activity activity=(Activity)obj;
            if(hidden.getBoolean(activity)&&position==0)return;
            Object item=adapter.getItem(position);
            if(!(item instanceof BluetoothDevice))return;
            BluetoothDevice phone=(BluetoothDevice)item;
            // Positive vendor identity only. Unknown vendors keep the complete OEM row.
            if(phone.getBondState()!=BluetoothDevice.BOND_BONDED
                    ||((Integer)vendor.invoke(phone)).intValue()!=76)return;
            String address=phone.getAddress();if(RowConnectState.bytes(address)==null)return;
            View button=v.findViewById(CP),hfp=v.findViewById(PHONE),progress=v.findViewById(PROGRESS);
            View label=v.findViewById(LABEL),buttons=v.findViewById(BUTTONS),profiles=v.findViewById(PROFILES);
            if(!(button instanceof ImageButton)||!(hfp instanceof ImageButton)||progress==null
                    ||label==null||buttons==null||profiles==null
                    ||label.getLayoutParams()==null||buttons.getLayoutParams()==null||profiles.getLayoutParams()==null)return;
            Row row=new Row(v,adapter,activity,address,position,new int[]{label.getLayoutParams().width,
                    buttons.getLayoutParams().width,profiles.getLayoutParams().width});
            width(label,392);width(buttons,364);width(profiles,274);
            rows.put(v,row);
            final Row clicked=row;
            button.setOnClickListener(new View.OnClickListener(){public void onClick(View ignored){click(clicked);}});
            paint(row);
            scheduleStatus(0);
        }catch(Throwable unavailable){event("bind_failed");}
    }
    private static void width(View v,int dp) {
        ViewGroup.LayoutParams p=v.getLayoutParams();
        p.width=(int)(dp*v.getResources().getDisplayMetrics().density+0.5f);v.setLayoutParams(p);
    }
    private Row bound(Row requested) {
        for(Row row:rows.values())if(row.address.equalsIgnoreCase(requested.address)
                &&row.adapter.get()==requested.adapter.get()&&row.activity.get()==requested.activity.get())return row;
        return null;
    }
    private BluetoothDevice validate(Row row,boolean requireFocus)throws Exception {
        Row current=bound(row);View view=current==null?null:current.view.get();
        Activity a=row.activity.get();Adapter adapter=row.adapter.get();
        if(!enabled()||view==null||a==null||a.isFinishing()||adapter==null
                ||view.getWindowToken()==null||(requireFocus&&!a.hasWindowFocus())
                ||Boolean.TRUE.equals(restricted.invoke(a)))return null;
        // The untouched HFP listener captures this exact position. A reordered
        // list without an OEM rebind must abort rather than connect another row.
        int position=current.position;
        if(position<0||position>=adapter.getCount()||(hidden.getBoolean(a)&&position==0))return null;
        Object item=adapter.getItem(position);
        if(item instanceof BluetoothDevice) {
            BluetoothDevice d=(BluetoothDevice)item;
            if(row.address.equalsIgnoreCase(d.getAddress())&&d.getBondState()==BluetoothDevice.BOND_BONDED
                    &&((Integer)vendor.invoke(d)).intValue()==76)return d;
        }
        return null;
    }
    private Object bt(Row row)throws Exception {
        Activity a=row.activity.get();return a==null?null:btcm.invoke(manager.get(a));
    }
    private String hfp(Row row)throws Exception {
        Object bt=bt(row);if(bt==null)return null;
        Object d=bt.getClass().getMethod("IbtcmGetHfpConnectedDevice").invoke(bt);
        return d instanceof BluetoothDevice?((BluetoothDevice)d).getAddress():null;
    }
    private boolean nativePending(Row row)throws Exception {
        Activity a=row.activity.get();if(a==null)return false;
        for(Field f:new Field[]{pending,reserved}) {
            Object d=f.get(a);
            if(d instanceof BluetoothDevice&&row.address.equalsIgnoreCase(((BluetoothDevice)d).getAddress()))return true;
        }
        Object bt=bt(row);if(bt==null)return false;
        Object d=bt.getClass().getMethod("IbtcmGetConnectingDevice").invoke(bt);
        return d instanceof BluetoothDevice&&row.address.equalsIgnoreCase(((BluetoothDevice)d).getAddress());
    }
    private void click(Row row) {
        try {
            View view=row.view.get();
            if(view==null||rows.get(view)!=row||validate(row,true)==null){toast(row,"현재 기기에서 CarPlay 연결을 시작할 수 없습니다.");return;}
            long id=request.begin(row.address,SystemClock.elapsedRealtime());
            if(id==0){toast(row,"CarPlay 연결을 확인하고 있습니다. 잠시 기다려 주세요.");return;}
            active=new WeakReference<Row>(row);paintAll();event("requested");
            toast(row,"CarPlay 연결 상태를 확인합니다.");exchange(id,row,false,false);
        }catch(Throwable failed){event("request_failed");toast(row,"CarPlay 연결 상태를 확인하지 못했습니다.");}
    }
    private synchronized ExecutorService worker() {
        if(worker==null)worker=Executors.newSingleThreadExecutor(new ThreadFactory(){
            public Thread newThread(Runnable r){Thread t=new Thread(r,"usbcp-row-connect");t.setDaemon(true);return t;}
        });
        return worker;
    }
    /** Read-only live status. Binding and periodic refresh never initiate pairing. */
    private void scheduleStatus(long delay) {
        if(rows.isEmpty())return;
        long at=SystemClock.elapsedRealtime()+delay;
        if(statusScheduled) {
            if(at>=statusAt)return;
            main.removeCallbacks(statusTick);
        }
        statusAt=at;
        statusScheduled=true;
        main.postDelayed(statusTick,delay);
    }
    private void refreshStatus() {
        if(!enabled()||rows.isEmpty())return;
        if(statusInFlight){scheduleStatus(200);return;}
        long now=SystemClock.elapsedRealtime();
        Row next=null;
        for(Row row:new ArrayList<Row>(rows.values())) {
            paint(row);
            if(request.owns(row.address)&&request.busy())continue;
            try {
                if(validate(row,true)==null)continue;
            }catch(Throwable unavailable){continue;}
            if(!row.statusQueried||now-row.statusRequested>=STATUS_INTERVAL_MS) {
                if(next==null||row.statusRequested<next.statusRequested)next=row;
            }
        }
        if(next==null){scheduleStatus(STATUS_INTERVAL_MS);return;}
        final Row row=next;
        final long requestGeneration=request.generation();
        row.statusQueried=true;row.statusRequested=now;statusInFlight=true;
        worker().execute(new Runnable(){public void run(){
            ManualCarplay.Result reply=null;
            try {reply=ManualCarplay.query(RowConnectState.bytes(row.address));}
            catch(Exception unavailable) {}
            final ManualCarplay.Result result=reply;
            main.post(new Runnable(){public void run(){
                statusInFlight=false;
                View view=row.view.get();
                try {
                    // Recycled rows and a later explicit request own their state.
                    if(view!=null&&rows.get(view)==row&&validate(row,false)!=null
                            &&request.generation()==requestGeneration
                            &&!(request.owns(row.address)&&request.busy())) {
                        liveStatus(row,result);paint(row);
                    }
                }catch(Throwable unavailable){row.connected=false;}
                scheduleStatus(50);
            }});
        }});
    }
    private void liveStatus(Row row,ManualCarplay.Result result) {
        int proof=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;
        row.connected=result!=null&&result.status==ManualCarplay.CONNECTED&&(result.flags&proof)==proof;
        row.statusReceived=SystemClock.elapsedRealtime();
    }
    /** All socket operations live here, never on the OEM binding/click thread. */
    private void exchange(final long id,final Row row,final boolean retry,final boolean polling) {
        if(!request.live(id,SystemClock.elapsedRealtime())){finish(id,row,false,timeoutMessage(id));return;}
        // Only an explicit click owns a request id. Passive status reads never reach here.
        final boolean prepare=!retry&&serviceAttempt!=id;
        final Activity context=prepare?row.activity.get():null;
        if(prepare)serviceAttempt=id;
        worker().execute(new Runnable(){public void run(){
            ManualCarplay.Result reply=null;
            int prepared=0;
            if(prepare&&request.live(id,SystemClock.elapsedRealtime()))prepared=OemCarplayService.start(context);
            try {if(request.live(id,SystemClock.elapsedRealtime()))reply=retry?ManualCarplay.retry(RowConnectState.bytes(row.address))
                    :ManualCarplay.query(RowConnectState.bytes(row.address));}
            catch(Exception ignored) {}
            final ManualCarplay.Result result=reply;
            final int preparation=prepared;
            // Only the first query may retry an older terminal result. A retry's own
            // failure ends this click; treating it as another first query loops IPC.
            main.post(new Runnable(){public void run(){
                if(request.same(id)&&preparation!=0) {
                    serviceResult=preparation;
                    if(preparation==OemCarplayService.STARTED)serviceRequested=id;
                    event(preparation==OemCarplayService.STARTED?"service_requested":"service_unavailable");
                }
                receive(id,row,result,polling||retry);
            }});
        }});
    }
    private String timeoutMessage(long id) {
        return serviceRequested==id&&serverAnswered!=id
                ?"CarPlay 연결 기능이 준비되지 않았습니다. 내비를 재부팅해 주세요."
                :"CarPlay 연결 시간이 초과되었습니다.";
    }
    private void receive(final long id,final Row row,ManualCarplay.Result result,final boolean polling) {
        if(!request.live(id,SystemClock.elapsedRealtime())){finish(id,row,false,timeoutMessage(id));return;}
        try {
            if(validate(row,false)==null){cancel(row,"선택한 기기가 변경되어 연결 요청을 취소했습니다.");return;}
            Row current=bound(row);if(current!=null)liveStatus(current,result);
            if(result==null) {
                if(serviceRequested==id) {
                    main.postDelayed(new Runnable(){public void run(){exchange(id,row,false,polling);}},750);
                }else finish(id,row,false,serviceResult==OemCarplayService.DENIED
                        ?"순정 CarPlay 서비스를 시작할 권한이 없습니다. 설치 상태를 확인해 주세요."
                        :"순정 CarPlay 서비스를 시작하지 못했습니다. 내비를 재부팅해 주세요.");
                return;
            }
            serverAnswered=id;
            int status=result.status;
            if((result.flags&ManualCarplay.ACTIVE_CP)!=0&&(result.flags&ManualCarplay.ACTIVE_TARGET)==0) {
                finish(id,row,false,"다른 CarPlay 연결을 먼저 종료해 주세요.");return;
            }
            if(status==ManualCarplay.CONNECTED){
                int proof=ManualCarplay.ACTIVE_CP|ManualCarplay.ACTIVE_TARGET;
                boolean connected=(result.flags&proof)==proof;
                finish(id,row,connected,connected?"CarPlay가 연결되어 있습니다.":"선택한 기기의 CarPlay 연결을 확인하지 못했습니다.");return;
            }
            if(status==ManualCarplay.ACCEPTED) {
                request.phase(id,RowConnectState.CONNECTING);paintAll();
                main.postDelayed(new Runnable(){public void run(){exchange(id,row,false,true);}},700);return;
            }
            // Only the explicit request's first query may ask the provider to recover
            // an unavailable AP. POWER_OFF never enters this path; a retry's failure
            // remains terminal so a single click cannot restart recovery in a loop.
            if(status==ManualCarplay.READY||(!polling&&status==ManualCarplay.AP_UNAVAILABLE)
                    ||(!polling&&(status==ManualCarplay.FAILED||status==ManualCarplay.TIMED_OUT)
                    &&(result.flags&ManualCarplay.CURRENT_HFP)!=0)) {
                if(polling){finish(id,row,false,"CarPlay 연결이 완료되지 않았습니다. 다시 시도해 주세요.");return;}
                request.phase(id,RowConnectState.CONNECTING);paintAll();exchange(id,row,true,false);return;
            }
            if(status==ManualCarplay.PHONE_REQUIRED&&!polling) {
                if((result.flags&ManualCarplay.ACTIVE_CP)!=0){finish(id,row,false,"다른 CarPlay 연결을 먼저 종료해 주세요.");return;}
                startNative(id,row);return;
            }
            String message=status==ManualCarplay.BUSY?"다른 연결이 진행 중입니다. 기존 연결을 확인해 주세요.":
                    status==ManualCarplay.POWER_OFF?"차량 전원이 꺼진 상태에서는 CarPlay를 시작하지 않습니다.":
                    status==ManualCarplay.DISABLED?"외부 USB Wi-Fi 동글 사용 설정을 확인해 주세요.":
                    status==ManualCarplay.AP_UNAVAILABLE?"USB Wi-Fi 동글 연결 상태를 확인해 주세요.":
                    status==ManualCarplay.NOT_APPLE?"이 기기는 CarPlay 연결 대상으로 확인되지 않았습니다.":
                    status==ManualCarplay.TIMED_OUT?"CarPlay 연결 시간이 초과되었습니다.":"CarPlay 연결에 실패했습니다. 휴대전화 연결 상태를 확인해 주세요.";
            finish(id,row,false,message);
        }catch(Throwable failed){finish(id,row,false,"CarPlay 연결 상태를 확인하지 못했습니다.");}
    }
    private void startNative(final long id,final Row row)throws Exception {
        if(validate(row,true)==null){finish(id,row,false,"현재 기기에서 전화 연결을 시작할 수 없습니다.");return;}
        if(row.address.equalsIgnoreCase(hfp(row))) {exchange(id,row,true,false);return;}
        Activity a=row.activity.get();
        if(a==null||Boolean.TRUE.equals(wireless.invoke(manager.get(a)))) {
            finish(id,row,false,"기존 CarPlay 연결을 먼저 종료해 주세요.");return;
        }
        Row current=bound(row);View view=current==null?null:current.view.get();View phone=view==null?null:view.findViewById(PHONE);
        if(phone==null||phone.getVisibility()!=View.VISIBLE||!phone.isEnabled()) {
            finish(id,row,false,"순정 전화 연결 버튼을 사용할 수 없습니다.");return;
        }
        request.phase(id,RowConnectState.NATIVE);paintAll();
        // The OEM ga listener (untouched) owns prompts, busy state and HFP transfer.
        if(!phone.performClick()){finish(id,row,false,"순정 전화 연결을 시작하지 못했습니다.");return;}
        toast(row,"선택한 기기의 전화 연결을 기다립니다.");event("native_wait");
        waitNative(id,row,SystemClock.elapsedRealtime());
    }
    private void waitNative(final long id,final Row row,final long started) {
        main.postDelayed(new Runnable(){public void run(){
            if(!request.live(id,SystemClock.elapsedRealtime())){finish(id,row,false,"전화 연결 시간이 초과되었습니다.");return;}
            try {
                if(validate(row,false)==null){cancel(row,"기기 목록이 변경되어 연결 요청을 취소했습니다.");return;}
                Activity activity=row.activity.get();
                // Do not advance while an OEM dialog owns input. Acceptance is not inferred.
                if(activity!=null&&activity.hasWindowFocus()) {
                    if(request.hfpReady(id,hfp(row),SystemClock.elapsedRealtime())) {
                        request.phase(id,RowConnectState.CONNECTING);paintAll();exchange(id,row,false,false);return;
                    }
                    if(SystemClock.elapsedRealtime()-started>=2000&&!nativePending(row)) {
                        finish(id,row,false,"전화 연결이 취소되었거나 시작되지 않았습니다.");return;
                    }
                }
                waitNative(id,row,started);
            }catch(Throwable failed){finish(id,row,false,"선택한 기기의 전화 연결을 확인하지 못했습니다.");}
        }},500);
    }
    private void finish(long id,Row row,boolean success,String message) {
        // A stale callback must never overwrite the next user request's state or toast.
        if(!request.same(id)||!request.owns(row.address)||active.get()!=row)return;
        if(!request.live(id,SystemClock.elapsedRealtime())&&request.phase()!=RowConnectState.FAILED)return;
        request.phase(id,success?RowConnectState.DONE:RowConnectState.FAILED);paintAll();
        toast(row,message);event(success?"connected":"failed");active=new WeakReference<Row>(null);
        scheduleStatus(STATUS_INTERVAL_MS);
    }
    private void cancel(Row row,String message){request.cancel();active=new WeakReference<Row>(null);paintAll();toast(row,message);event("cancelled");}
    private void paintAll(){for(Row row:new ArrayList<Row>(rows.values()))paint(row);}
    private void paint(Row row) {
        View v=row.view.get();if(v==null)return;
        View progress=v.findViewById(PROGRESS);ImageButton button=(ImageButton)v.findViewById(CP);
        boolean selected=request.owns(row.address),busy=selected&&request.busy();
        if(progress!=null)progress.setVisibility(busy?View.VISIBLE:View.GONE);
        if(button!=null) {
            button.setVisibility(busy?View.GONE:View.VISIBLE);
            // A completed click is not connection proof; expired/failed reads clear the icon.
            boolean connected=row.connected&&SystemClock.elapsedRealtime()-row.statusReceived<STATUS_FRESH_MS;
            button.setImageResource(connected?CP_ON:CP_OFF);
            button.setContentDescription(busy?(request.phase()==RowConnectState.NATIVE?"CarPlay 전화 연결 대기":"CarPlay 연결 중"):
                    connected?"CarPlay 연결됨":selected&&request.phase()==RowConnectState.FAILED?"CarPlay 다시 연결":"CarPlay 연결");
        }
    }
    private void toast(Row row,String text) {Activity a=row.activity.get();if(a!=null&&!a.isFinishing())Toast.makeText(a,text,Toast.LENGTH_LONG).show();}
    private void destroy(Object activity) {
        Row selected=active.get();
        if(selected!=null&&selected.activity.get()==activity){request.cancel();active=new WeakReference<Row>(null);}
        ArrayList<View> dead=new ArrayList<View>();
        for(Map.Entry<View,Row> e:rows.entrySet())if(e.getValue().activity.get()==activity)dead.add(e.getKey());
        for(View v:dead)rows.remove(v);
        if(rows.isEmpty()){main.removeCallbacks(statusTick);statusScheduled=false;}
    }
}
