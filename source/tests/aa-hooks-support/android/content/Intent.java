package android.content;
public final class Intent {
    public ComponentName component;public int mode=-1;public Object device;
    public ComponentName getComponent(){return component;}
    public int getIntExtra(String name,int fallback){return mode;}
    public Object getParcelableExtra(String name){return device;}
}
