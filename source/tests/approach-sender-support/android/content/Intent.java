package android.content;

public final class Intent {
    public final String action;
    public String target;
    public Boolean active;
    public Integer bar;
    public Integer source;
    public Intent(String action) { this.action = action; }
    public Intent setPackage(String target) { this.target = target; return this; }
    public Intent putExtra(String key, boolean value) {
        if (!"active".equals(key)) throw new AssertionError("unexpected boolean extra");
        active = value;
        return this;
    }
    public Intent putExtra(String key, int value) {
        if ("source".equals(key)) { source=value; return this; }
        if (!"bar".equals(key)) throw new AssertionError("unexpected integer extra");
        bar = value;
        return this;
    }
}
