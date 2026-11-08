package android.content;
public class Intent { public String action,pkg; public boolean active; public int bar,source=1;
 public Intent(String a){action=a;} public String getAction(){return action;} public String getPackage(){return pkg;}
 public boolean getBooleanExtra(String k,boolean d){return active;} public int getIntExtra(String k,int d){return "source".equals(k)?source:bar;} }
