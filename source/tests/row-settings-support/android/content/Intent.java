package android.content;
public class Intent {
 private ComponentName component;
 public Intent setClassName(String p,String c){component=new ComponentName(p,c);return this;}
 public ComponentName getComponent(){return component;}
 public String getAction(){return null;}
 public Object getExtras(){return null;}
}
