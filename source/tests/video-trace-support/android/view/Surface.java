package android.view;
public final class Surface {
    public boolean valid=true;
    public boolean isValid(){return valid;}
    @Override public String toString(){throw new AssertionError("Surface contents must not be formatted");}
}
