package hmgapi.mods.wireless.dipo;
import android.os.*;
import hmgapi.hook.*;
import java.util.*;

/** Synthetic valid coordinates; tests never read a car or store actual NMEA. */
public final class RmcVehicleSpeedTest {
 static int checks;
 static void ok(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static String wire(String body,String suffix){int sum=0;for(int i=1;i<body.length();i++)sum^=body.charAt(i);return body+String.format(Locale.US,"*%02X",sum)+suffix;}
 static final String BODY="$GPRMC,123519.00,A,4807.038,N,01131.000,E,022.400,084.4,230394,,,";
 static String fixture(String suffix){return wire(BODY,suffix);}
 static String unchangedExceptSpeed(String s){int star=s.indexOf('*');String[] f=s.substring(0,star).split(",",-1);f[7]="SPEED";return String.join(",",f)+s.substring(star+3);}
 static void parser(){
  Locale original=Locale.getDefault();Locale.setDefault(Locale.FRANCE);
  try{
   for(String suffix:new String[]{"","\r\n","\u0000"})for(int speed:new int[]{0,1,3,100,510}){
    String in=fixture(suffix);RmcSpeedRewrite.Result r=RmcSpeedRewrite.apply(in,speed);
    ok(r.status==RmcSpeedRewrite.REPLACED,"valid exact OEM-style RMC "+speed);
    ok(unchangedExceptSpeed(in).equals(unchangedExceptSpeed(r.sentence)),"time/position/course/date/mode/terminator byte-for-byte");
    ok(Math.abs(r.outputKmhMilli-speed*1000)<=1,"km/h->knots precision "+speed);
    RmcSpeedRewrite.Result again=RmcSpeedRewrite.apply(r.sentence,speed);
    ok(again.status==RmcSpeedRewrite.UNCHANGED&&again.sentence.equals(r.sentence),"new checksum validates and rewrite idempotent");
    ok(r.inputKmhMilli==41485,"input GPS numeric speed retained separately");
   }
   String standard="$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";
   ok(RmcSpeedRewrite.apply(standard,36).status==3,"published12-field RMC actual checksum accepted");
   String ascii=fixture("");int checksum=ascii.indexOf('*')+1;
   String fullwidth=ascii.substring(0,checksum)+(char)(ascii.charAt(checksum)+0xfee0)+(char)(ascii.charAt(checksum+1)+0xfee0);
   ok(RmcSpeedRewrite.apply(fullwidth,20).status==1,"actual matching checksum rendered in fullwidth hex is rejected");
   for(int speed:new int[]{-1,511,Integer.MAX_VALUE})ok(RmcSpeedRewrite.apply(fixture(""),speed).status==1,"invalid MICOM not substituted");
   for(String input:new String[]{null,"",fixture("")+"junk",fixture("\u0000\u0000"),fixture("\n"),fixture("").replace("*","*0"),fixture("").substring(0,fixture("").length()-2)+"Ａ０",fixture("").replace("4807.038","4808.038"),"$GPGGA,anything*00",fixture("").replace("GPRMC","GNRMC")}){
    RmcSpeedRewrite.Result r=RmcSpeedRewrite.apply(input,20);ok(r.status==1&&Objects.equals(r.sentence,input),"invalid checksum/shape retained");
   }
   for(String body:new String[]{BODY.replace(",A,",",V,"),BODY+"N",BODY+"E",BODY+"M",BODY+"S"}){
    String in=wire(body,"\u0000");ok(RmcSpeedRewrite.apply(in,20).status==2,"invalid/estimated/simulated GPS fix not promoted");
   }
   for(String body:new String[]{BODY.replace("123519.00","245900"),BODY.replace("4807.038","4860.00"),BODY.replace("4807.038","9100.00"),BODY.replace("01131.000","18100.00"),BODY.replace(",N,",",X,"),BODY.replace("022.400","NaN"),BODY.replace("022.400","-1"),BODY.replace("022.400","99999"),BODY.replace("084.4","400"),BODY.replace("230394","003394"),BODY+",extra",BODY.replace("022.400","1e2"),BODY.replace("022.400","1."),BODY.replace("022.400",".1")})
    ok(RmcSpeedRewrite.apply(wire(body,""),30).status==1,"bounded malformed RMC retains OEM content");
  }finally{Locale.setDefault(original);}
 }
 static String output(ModContext mod,CarPlayVehicleSpeedHookTest.Manager m){return (String)mod.hooks.get("dipo.location.sentence").beforeParam(m,fixture("\u0000")).args[0];}
 static void hooks(){
  Handler.queue.clear();ModContext mod=new ModContext();CarPlayVehicleSpeed.install(mod,new LoadedApp());
  CarPlayVehicleSpeedHookTest.cpStart(mod,new CarPlayVehicleSpeedHookTest.Cp());
  CarPlayVehicleSpeedHookTest.Manager m=new CarPlayVehicleSpeedHookTest.Manager();
  CarPlayVehicleSpeedHookTest.Listener l=new CarPlayVehicleSpeedHookTest.Listener(m);
  String gps=fixture("\u0000");
  CarPlayVehicleSpeedHookTest.change(mod,l,33);ok(output(mod,m).equals(gps),"observation before request not reused");
  CarPlayVehicleSpeedHookTest.start(mod,m,new Object());ok(output(mod,m).equals(gps),"cold manager default0 never substituted");
  CarPlayVehicleSpeedHookTest.change(mod,l,36);String changed=output(mod,m);
  ok(!changed.equals(gps)&&changed.contains(",19.438,"),"actual MICOM36kmh used in requested phone RMC");
  SystemClock.now+=600000;ok(output(mod,m).equals(changed),"steady unchanged speed survives callback age without fabricated refresh");
  Bridge.sSession=false;ok(output(mod,m).equals(changed),"normal Bluetooth handover retains active wirelessCP");Bridge.sSession=true;
  m.rmcRequested=false;ok(output(mod,m).equals(gps),"phone must request RMC");m.rmcRequested=true;
  Bridge.sUsb=true;ok(output(mod,m).equals(gps),"wiredUSB untouched");Bridge.sUsb=false;
  Bridge.sCpWireless=false;ok(output(mod,m).equals(gps),"nonwireless untouched");Bridge.sCpWireless=true;
  mod.active=false;ok(output(mod,m).equals(gps),"disabled untouched");mod.active=true;
  String pascd="$PASCD,OEM\u0000";ok(mod.hooks.get("dipo.location.sentence").beforeParam(m,pascd).args[0].equals(pascd),"PASCD remains exact OEM");
  CarPlayVehicleSpeedHookTest.change(mod,l,511);ok(output(mod,m).equals(gps),"invalidMICOM511 clears oldvalid rather thanOEMzero");
  CarPlayVehicleSpeedHookTest.change(mod,l,0);ok(output(mod,m).contains(",0.000,"),"actual measured0 supported");
  mod.hooks.get("dipo.location_speed.changed").after(l,new RuntimeException(),44);ok(output(mod,m).equals(gps),"failed callback invalidates oldsample");
  CarPlayVehicleSpeedHookTest.change(mod,l,44);mod.hooks.get("dipo.location.stop").before(m);ok(output(mod,m).equals(gps),"stoppedrequest leavesGPS");
  CarPlayVehicleSpeedHookTest.start(mod,m,new Object());ok(output(mod,m).equals(gps),"newrequest discards priorspeed");
  CarPlayVehicleSpeedHookTest.change(mod,l,45);ok(!output(mod,m).equals(gps),"newobserved sample resumes");
  Object old=Bridge.sCp;mod.hooks.get("dipo.carplay.session_finalized").before(old,0);ok(output(mod,m).equals(gps),"finalized generation is untouched");
  CarPlayVehicleSpeedHookTest.cpStart(mod,new CarPlayVehicleSpeedHookTest.Cp());ok(output(mod,m).equals(gps),"replacement CP cannot inherit request/sample");
  CarPlayVehicleSpeedHookTest.start(mod,m,new Object());CarPlayVehicleSpeedHookTest.change(mod,l,50);String newer=output(mod,m);
  mod.hooks.get("dipo.carplay.session_finalized").before(old,0);ok(output(mod,m).equals(newer),"late oldownerfinalize cannot erase newowner sample");
  MethodHook cpStart=mod.hooks.get("dipo.carplay.session_started");
  cpStart.before(Bridge.sCp,1);cpStart.after(Bridge.sCp,null,1);
  ok(output(mod,m).equals(gps),"same CP object restart cannot reuse earlier observation");
  CarPlayVehicleSpeedHookTest.change(mod,l,51);ok(!output(mod,m).equals(gps),"current callback restores sameowner restart");
  // Listener replay during start is observed, but not used until OEM start succeeds.
  MethodHook start=mod.hooks.get("dipo.location.start");start.before(m,new Object());
  CarPlayVehicleSpeedHookTest.change(mod,l,51);ok(output(mod,m).equals(gps),"pendingSTART cannot rewrite");
  start.after(m,null,new Object());ok(!output(mod,m).equals(gps),"successful listener replay becomes eligible");
  start.before(m,new Object());CarPlayVehicleSpeedHookTest.change(mod,l,52);start.after(m,new RuntimeException(),new Object());
  ok(output(mod,m).equals(gps),"failedSTART discards listener replay");
  ok(mod.logs.contains("USB_CP_LOCATION rmc_ready output_only=1 vehicle_kmh=1 preserve_fix=1"),"numeric ready diagnostic");
  boolean saw=false;for(String line:mod.logs)if(line.contains("rmc_window")){saw=true;ok(!line.contains("GPRMC")&&!line.contains("4807"),"summary contains noNMEA/coordinates");}ok(saw,"window is emitted");
 }
 static void requestEdges(){
  RmcVehicleSpeedSource source=new RmcVehicleSpeedSource();Object manager=new Object(),session=new Object();
  RmcVehicleSpeedSource.Request first=source.begin(manager,session);source.changed(manager,session,60,10);source.complete(first,true);
  RmcVehicleSpeedSource.Sample sample=source.sample(manager,session,20);ok(sample!=null&&sample.age==10,"confirmed sample");
  RmcVehicleSpeedSource.Request next=source.begin(manager,session);source.changed(manager,session,61,30);source.complete(next,true);
  ok(!source.current(sample,manager,session),"same-manager request replacement rejects old rewrite at commit");
  source.complete(first,false);ok(source.sample(manager,session,40).kmh==61,"late first failure cannot invalidate replacement");
  sample=source.sample(manager,session,40);source.changed(manager,session,62,41);ok(!source.current(sample,manager,session),"changed value during parse rejects stale rewrite");
  source.changed(manager,new Object(),63,42);ok(source.sample(manager,session,43)==null,"session mismatch clears snapshot");
 }
 public static void main(String[] args){parser();hooks();requestEdges();System.out.println("RmcVehicleSpeedTest PASS "+checks+" checks");}
}
