package hmgapi.hook;
/** host stand-in: the owner's unit is set by the test */
public final class Car {
    public static final int DISTANCE_KM = 0, DISTANCE_MILES = 1;
    public static int spec=100; public static boolean cp,aa=true;
    public static int stepOfSpec(){return spec;}
    public static boolean carPlayGuiding(android.content.Context c){return cp;}
    public static boolean androidAutoGuiding(android.content.Context c){return aa;}
    public static int unit = DISTANCE_MILES;
    public static int distanceUnit(android.content.Context c) { return unit; }
}
