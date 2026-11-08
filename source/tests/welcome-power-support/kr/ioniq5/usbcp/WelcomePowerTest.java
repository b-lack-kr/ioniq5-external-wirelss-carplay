package kr.ioniq5.usbcp;
import hmgapi.hook.*;
import com.mobis.welcome.WelcomeService;
public final class WelcomePowerTest {
 static int checks,originals;
 static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static void points()throws Exception {
  Points.methods.clear();Points.methods.put(WelcomePower.STATUS,WelcomeService.class.getMethod("e",int.class));
  Points.methods.put(WelcomePower.MODE,WelcomeService.class.getMethod("getMode"));
 }
 static void event(Object target,Object status,boolean fails)throws Throwable {
  int before=originals;
  try {
   Object result=ModContext.dispatch(WelcomePower.STATUS,target,new Object[]{status},new ModContext.Original(){public Object run(){originals++;if(fails)throw new IllegalStateException("OEM");return null;}});
   check(!fails && result==null,"original return unchanged");
  }catch(IllegalStateException e){check(fails && e.getMessage().equals("OEM"),"original failure preserved");}
  check(originals==before+1,"original called exactly once");
 }
 public static void main(String[] args)throws Throwable {
  points();LoadedApp app=new LoadedApp();
  check(WelcomePower.target(app,1000),"exact target");
  check(!WelcomePower.target(app,0) && !WelcomePower.target(app,1001),"wrong UID");
  app.processName="com.mobis.welcome:other";check(!WelcomePower.target(app,1000),"secondary process rejected");app.processName=app.packageName;
  app.packageName="com.other";check(!WelcomePower.target(app,1000),"other package rejected");app.packageName=app.processName;
  app.context=null;check(!WelcomePower.target(app,1000),"missing context");app.context=new android.content.Context();
  ModContext mod=new ModContext();WelcomePower.install(mod,app);check(OemPower.created==1,"one private animation sender");
  check(mod.hooks.size()==1 && mod.hooks.containsKey(WelcomePower.STATUS),"only verified OEM method hooked");
  WelcomeService service=new WelcomeService();WelcomeService.mode=1;event(service,1,false);
  check(OemPower.offers.size()==1 && OemPower.offers.get(0),"Goodbye start turns owned AP off");
  event(service,0,false);check(OemPower.offers.size()==1,"Goodbye completion cannot restart AP");
  WelcomeService.mode=0;event(service,1,false);check(OemPower.offers.size()==2 && !OemPower.offers.get(1),"Welcome start releases OFF latch");
  for(int mode:new int[]{-1,2,Integer.MAX_VALUE}){WelcomeService.mode=mode;event(service,1,false);}
  WelcomeService.mode=1;
  for(Object status:new Object[]{-1,2,Integer.MAX_VALUE,null,"1"})event(service,status,false);
  event(new Object(),1,false);event(null,1,false);
  check(OemPower.offers.size()==2,"unknown mode/status/type/instance never becomes power decision");
  mod.on=false;event(service,1,false);mod.on=true;check(OemPower.offers.size()==2,"disabled mod inert");
  WelcomeService.failMode=true;event(service,1,false);event(service,1,false);WelcomeService.failMode=false;
  check(OemPower.offers.size()==2,"mode failure does not infer power");
  int failures=0;for(String line:mod.logs){if(line.endsWith("read_failed"))failures++;check(line.matches("USB_CP_ANIMATION_POWER event=(ready|unavailable|welcome_start|goodbye_start|read_failed)"),"fixed diagnostic grammar");}
  check(failures==1 && mod.hookErrors.isEmpty(),"bounded failure logging without exception payload");
  event(service,1,true);check(OemPower.offers.size()==3,"observe authoritative entry without masking original failure");
  OemPower.fail=true;event(service,1,false);OemPower.fail=false;check(mod.hookErrors.isEmpty(),"relay failure never affects OEM playback");
  points();Points.methods.remove(WelcomePower.MODE);mod=new ModContext();int created=OemPower.created;WelcomePower.install(mod,app);
  check(mod.hooks.isEmpty() && OemPower.created==created && mod.logs.contains("USB_CP_ANIMATION_POWER event=unavailable"),"missing exact map fails closed before sender");
  android.os.Process.uid=1001;points();mod=new ModContext();WelcomePower.install(mod,app);check(mod.hooks.isEmpty(),"install rejects wrong UID");
  System.out.println("WelcomePowerTest PASS checks="+checks);
 }
}
