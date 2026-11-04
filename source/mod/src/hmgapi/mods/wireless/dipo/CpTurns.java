package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.text.format.DateFormat;

import com.tcc.iap2.Iap2NotiMsg;

import java.util.Calendar;
import java.util.LinkedHashMap;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * CarPlay turn-by-turn on the cluster and HUD: a port of the turn-by-turn build's DiPO
 * route guidance manager (e/u, with its route update e/c and maneuver e/v), fed by
 * HMG-API's own iAP2 listener instead of DiPO's, and sending through HMG-API's Cluster API.
 * DiPO's own manager only exists when stepofspec is 999 or more, and stays off.
 *
 * The iPhone sends RouteGuidanceUpdate (120: state, distances, the current maneuver
 * list), RouteGuidanceManeuverUpdate (121: one maneuver by index) and LaneGuidance
 * (122), each for a display component, often field by field. Kept per component as in
 * DiPO. What it sends, as the build did:
 *   - the turn (updateTBTInfo: icon, distance in the phone's units), crossroad name and,
 *     when state is 6, the destination name
 *   - the HUD list (0xF2 frame: icon, next icon, road) and the HUD distance (2C)
 *   - remaining distance and arrival time (updateTBTSubInfo, in the owner's units)
 *   - up to 3 upcoming turns (updateTPTurnList, owner's units) and lanes (0xF1 frame)
 * Never the cluster's alive or operation status: phone guidance doesn't claim the
 * cluster (the cluster doesn't come back from that on these cars).
 * Also from the build: when the source app changes (Apple Maps to Waze, say), what the
 * old app left is cleared and route guidance restarted, so the phone resends all of it.
 * Not ported: DiPO's per-region icon filters (China, Europe, Middle East, Australia,
 * Brazil); on US and Canada cars the build sends every icon.
 * One thread: every call comes from the same handler.
 */
final class CpTurns {
    final Context context;
    final Cluster cluster;
    /** restarts the phone's route guidance updates (Stop, then Start) on a thread of its own */
    final Runnable restart;
    /** component id -> its route guidance state (DiPO e/u.Al) */
    final LinkedHashMap<Integer, Update> updates = new LinkedHashMap<Integer, Update>();
    /** component id -> maneuver index -> maneuver (DiPO e/u.Am) */
    final LinkedHashMap<Integer, LinkedHashMap<Integer, Maneuver>> maneuvers = new LinkedHashMap<Integer, LinkedHashMap<Integer, Maneuver>>();
    String source;
    private final java.util.LinkedHashSet<String> missingManeuvers = new java.util.LinkedHashSet<String>();
    boolean outputAllowed = true;
    boolean displayed;
    int turnOwner = -1, tripOwner = -1;
    Boolean lastDistanceAvailable;
    boolean hudHasDistance;
    String lastDestination, lastRoad;
    int lastPrimaryIcon = -1, lastPrimaryUnit, lastHudUnit = -1, lastHudScaled;
    float lastPrimaryValue;
    boolean lastPrimaryValid;
    static final long TRIP_INTERVAL_MS = 1000;
    final TripScheduler scheduler;
    TripFrame lastTrip, pendingTrip;
    Runnable tripFlush;
    long lastTripAt;
    Object scheduledOwner;
    int pendingComponent;

    interface TripScheduler {
        long now();
        void later(Runnable work, long delay);
        void cancel(Runnable work);
        // A new identity for every replacement or terminated guidance session.
        Object owner();
    }
    /** last HUD list sent (type, next, road), so an unchanged one isn't resent */
    int hudType, hudNext;
    String hudRoad;

    CpTurns(Context context, Cluster cluster, Runnable restart, TripScheduler scheduler) {
        this.scheduler = scheduler;
        this.context = context;
        this.cluster = cluster;
        this.restart = restart;
    }

    /** RouteGuidanceUpdate (DiPO e/u.c) */
    void routeGuidance(Iap2NotiMsg msg) {
        if (msg == null) return;
        // A source switch clears cached fields, but the display component remains the
        // same physical recipient when a componentless delta refers to a known display.
        int[] ids = componentIds(msg);
        java.util.Set<Integer> known = ids == null ? knownComponents() : null;
        sourceCheck(msg);
        if (ids == null) {
            for (Integer id : known) storeRoute(id, msg);
        } else {
            for (int id : ids) storeRoute(id, msg);
        }
    }

    private java.util.Set<Integer> knownComponents() {
        java.util.Set<Integer> known = new java.util.LinkedHashSet<Integer>(updates.keySet());
        known.addAll(maneuvers.keySet());
        return known;
    }

    private void storeRoute(int id, Iap2NotiMsg msg) {
        if (id < 0) return;
        Update u = updates.get(id);
        if (u == null) {
            u = new Update(id, msg);
            updates.put(id, u);
        } else u.read(id, msg);
        turn(id, u);
    }

    /** the components a RouteGuidanceUpdate is for (item 0); null if it doesn't say */
    static int[] componentIds(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return null;
        int[] ids = null;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) return null;
            if (it.getItemParamType() == 5 && it.getItemId() == 0 && it.getItemObjCount() > 0) ids = it.getItemIntArray();
        }
        return ids;
    }

    /**
     * RouteGuidanceManeuverUpdate (DiPO e/u.d): item 0 the components, item 1 the index.
     * Stored under the index the message carries (the build's fix, by Isaac Wisdom: stock
     * wrote the index into a copy and stored every maneuver under -1, so none was found).
     */
    void maneuver(Iap2NotiMsg msg) {
        if (msg == null) return;
        int index = -1;
        int[] ids = null;
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g != null) {
            int[] found = null;
            boolean whole = true;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) {
                    whole = false;
                    break;
                }
                int id = it.getItemId();
                if (it.getItemObjCount() <= 0 || (id != 0 && id != 1)) continue;
                int[] v = it.getItemIntArray();
                if (v == null || v.length == 0) continue;
                if (id == 0) found = v;
                else index = v[0];
            }
            if (whole) ids = found;
        }
        if (index < 0) return;
        // Some apps send the component list only in the preceding route update.
        if (ids == null) {
            for (Integer id : knownComponents()) storeManeuver(id, index, msg);
        } else {
            for (int id : ids) storeManeuver(id, index, msg);
        }
    }

    private void storeManeuver(int id, int index, Iap2NotiMsg msg) {
        if (id < 0) return;
        LinkedHashMap<Integer, Maneuver> byIndex = maneuvers.get(id);
        if (byIndex == null) {
            byIndex = new LinkedHashMap<Integer, Maneuver>();
            maneuvers.put(id, byIndex);
        }
        Maneuver value = byIndex.get(index);
        if (value == null) byIndex.put(index, new Maneuver(index, id, msg));
        else value.read(index, id, msg);
        Update u = updates.get(id);
        if (u == null || u.list == null) return;
        for (int listed : u.list) {
            if (listed == index) {
                turn(id, u);
                return;
            }
        }
    }

    /** LaneGuidance (the build's applyLanes): up to 8 lanes as the cluster's 0xF1 frame */
    void lanes(Iap2NotiMsg msg) {
        if (!outputAllowed || !displayed) return;
        Iap2NotiMsg.Iap2ObjGroup g = msg == null ? null : msg.getItemGroup();
        if (g == null) return;
        int[] status = new int[8], a = new int[8], b = new int[8], c = new int[8];
        int n = 0;
        Iap2NotiMsg.Iap2ObjGroup[] lanes = null;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it != null && it.getItemParamType() == 13 && it.getItemId() == 2) lanes = it.getItemGroupArray();
        }
        if (lanes != null) {
            for (int k = 0; k < lanes.length && n < 8; k++) {
                int[] one = laneOne(lanes[k]);
                status[n] = one[0];
                a[n] = one[1];
                b[n] = one[2];
                c[n] = one[3];
                n++;
            }
        }
        byte[] f = new byte[81];
        f[0] = (byte) 0xF1;
        f[1] = (byte) n;
        for (int k = 0; k < n; k++) {
            f[k + 3] = (byte) status[k];
            f[k * 3 + 19] = (byte) a[k];
            f[k * 3 + 20] = (byte) b[k];
            f[k * 3 + 21] = (byte) c[k];
        }
        cluster.laneInfo(f);
    }

    /** one lane: {status (1, or 2 highlighted), up to 3 arrow icons} */
    static int[] laneOne(Iap2NotiMsg.Iap2ObjGroup g) {
        int[] r = new int[4];
        r[0] = 1;
        if (g == null) return r;
        int[] angles = null, highlight = null;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null) continue;
            int t = it.getItemParamType();
            if (t != 5 && t != 8) continue;
            int[] v = it.getItemIntArray();
            if (v == null || v.length == 0) continue;
            int id = it.getItemId();
            if (id == 1) {
                if (v[0] == 2) r[0] = 2;
            } else if (id == 2) {
                angles = v;
            } else if (id == 3) {
                highlight = v;
            }
        }
        if (highlight != null && highlight.length > 0) r[0] = 2;
        if (angles != null) {
            int n = 0;
            for (int k = 0; k < angles.length && n < 3; k++) {
                int icon = laneIcon(angles[k]);
                if (icon != 0) r[1 + n++] = icon;
            }
        }
        return r;
    }

    /** a lane angle (degrees, right positive) as the cluster's arrow: 1 straight, 2-4 right, 5 back, 6-8 left */
    static int laneIcon(int a) {
        if (a > 180) a -= 360;
        else if (a < -180) a += 360;
        if (a >= 0) {
            if (a <= 22) return 1;
            if (a <= 67) return 2;
            if (a <= 112) return 3;
            return a <= 157 ? 4 : 5;
        }
        a = -a;
        if (a <= 22) return 1;
        if (a <= 67) return 8;
        if (a <= 112) return 7;
        return a <= 157 ? 6 : 5;
    }

    /** the iAP2 session ended (DiPO e/u.disconnected) */
    void disconnected() {
        stop();
        source = null;
    }

    /** route guidance stopped (DiPO e/u.nS: the car's navigation took turn-by-turn back, or state 0) */
    void stop() {
        reset();
        clearTurn();
        clearTrip();
        lastDistanceAvailable = null;
    }

    private void clearTurn() {
        if (!displayed) return;
        displayed = false;
        turnOwner = -1;
        lastPrimaryIcon = lastHudUnit = -1;
        lastRoad = null;
        cluster.tbtInfo(0, 0, 0f, 0f, -1, 0, 0, 0, -1);
        cluster.crossroadName("");
        hudBlank();
        hudHasDistance = false;
    }

    private void clearTrip() {
        cancelTripFlush();
        if (tripOwner >= 0) {
            cluster.destinationName(0, "");
            cluster.tbtSubInfo(0f, -1, 0, 0, 0, 0, 0, 0f, -1, 0);
        }
        lastTrip = null;
        lastDestination = null;
        tripOwner = -1;
    }

    /** forget every component's state and maneuvers (DiPO e/u.nT) */
    void reset() {
        maneuvers.clear();
        updates.clear();
    }

    /** SourceName (item 19) changed: clear the old app's leftovers and have the phone resend */
    void sourceCheck(Iap2NotiMsg msg) {
        Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
        if (g == null) return;
        for (int k = 0; k < g.getItemCnt(); k++) {
            Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
            if (it == null || it.getItemId() != 19 || it.getItemParamType() != 10 || it.getItemObjCount() <= 0) continue;
            String[] v = it.getItemStringArray();
            String s = v == null || v.length == 0 ? null : v[0];
            if (s == null || s.length() == 0) continue;
            String was = source;
            source = s;
            if (was == null || was.equals(s)) return;
            Dipo.log("USB_CP_TBT_SOURCE_CHANGED");
            stop();
            if (restart != null) restart.run();
            return;
        }
    }

    /** one component's update to the cluster (DiPO e/u.a(Integer, Object), with the build's changes) */
    void turn(Integer id, Update u) {
        if (id < 0 || u == null) return;
        if (u.state == 0) {
            maneuvers.remove(id);
            u.clear();
            if (turnOwner == id) clearTurn();
            if (tripOwner == id) clearTrip();
            return;
        }
        if (!outputAllowed) {
            cancelTripFlush();
            return;
        }
        if (u.state < 0) return;
        // Route totals belong to the trip. A missing next maneuver must not clear them.
        int ownerUnit = Car.distanceUnit(context);
        sendTrip(id, u, ownerUnit);
        if (u.state == 6 && !u.destination.isEmpty() && !u.destination.equals(lastDestination)) {
            cluster.destinationName(0, u.destination);
            lastDestination = u.destination;
            tripOwner = id;
        }
        int[] list = u.list;
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        Maneuver x = m == null || list == null || list.length == 0 ? null : m.get(list[0]);
        if (x == null) {
            if (turnOwner == id) clearTurn();
            return;
        }
        Distance distance = Distance.resolve(u.distNext, u.nextStr, u.nextUnits, ownerUnit);
        boolean hasDistance = distance != null;
        if (lastDistanceAvailable == null || lastDistanceAvailable.booleanValue() != hasDistance) {
            Dipo.log("USB_CP_TBT_DISTANCE raw=" + (u.distNext >= 0) + " text=" + !u.nextStr.isEmpty()
                    + " displayUnit=" + u.nextUnits + " usable=" + (distance != null));
            lastDistanceAvailable = hasDistance;
        }
        int icon = x.primaryIcon(u.state);
        if ((icon == 0 || icon == 64) && missingManeuvers.size() < 12) {
            String fields = "type=" + x.type + " side=" + x.side + " junction=" + x.junction
                    + " exit=" + (validAngle(x.exit) ? Integer.toString(x.exit) : "absent");
            if (missingManeuvers.add(fields)) Dipo.log("USB_CP_TBT_MANEUVER_DETAIL " + fields);
        }
        if (icon == 0) {
            if (turnOwner == id) clearTurn();
            return;
        }
        if (!displayed) Dipo.log("USB_CP_TBT_DISPLAY first_turn icon=" + icon);
        displayed = true;
        turnOwner = id;
        String road = x.displayRoad();
        if (!road.equals(lastRoad)) {
            cluster.crossroadName(road);
            lastRoad = road;
        }
        float value = distance == null ? 0f : distance.value;
        int unit = distance == null ? Cluster.UNIT_M : distance.unit;
        if (icon != lastPrimaryIcon || unit != lastPrimaryUnit || value != lastPrimaryValue
                || hasDistance != lastPrimaryValid) {
            cluster.tbtInfo(icon, 0, 0f, value, hasDistance ? 0 : -1, 0, unit, 0, 0);
            lastPrimaryIcon = icon;
            lastPrimaryValue = value;
            lastPrimaryUnit = unit;
            lastPrimaryValid = hasDistance;
        }
        sendHud(icon, id, u);
        if (hasDistance || hudHasDistance) sendHudDist(value, unit);
        hudHasDistance = hasDistance;
        sendAhead(id, u);
    }

    /**
     * The cluster icon for a maneuver: iAP2 ManeuverType, driving side (0 right-hand
     * traffic, 1 left), junction type (0 intersection, 1 roundabout) and exit angle, as
     * the turn-by-turn build maps them (0: nothing to show).
     */
    static int icon(int type, int side, int junction, int exit, int state) {
        if (type == 19 || (junction == 1 && type == 4)) return 102; // U-turn at a roundabout, even without geometry
        if (type == 4 || type == 18 || type == 26) return side == 1 ? 73 : 72;
        if (isRoundaboutType(type) || (junction == 1 && (type == 1 || type == 2 || type == 3))) {
            return validAngle(exit) ? roundaboutIcon(exit) : 64;
        }
        switch (type) {
            case 1: case 20: // left / left at end of road
                if (exit == -45) return 145;
                if (validAngle(exit) && exit < -110) return 69;
                if (validAngle(exit) && exit < 0 && exit > -70) return 71;
                return 70;
            case 2: case 21: // right / right at end of road
                if (exit == 45) return 144;
                if (validAngle(exit) && exit > 110) return 68;
                if (validAngle(exit) && exit > 0 && exit < 70) return 66;
                return 67;
            case 3: return 65;
            case 13: case 49: return 71; // keep/slight left
            case 14: case 50: return 66; // keep/slight right
            case 47: return 69;
            case 48: return 68;
            case 5:
            case 9:
                return side == 0 && junction == 0 && exit == 0 ? 172 : 0;
            case 12: {   // arrive; state 2 (arrived): 3
                int i = side == 0 && junction == 0 ? 112 : 0;
                return state == 2 ? 3 : i;
            }
            case 15: return side == 0 && junction == 0 && exit == 0 ? 176 : 0;
            case 22: return side == 0 && junction == 0 && exit == 0 ? 82 : 0;
            case 23: return side == 0 && junction == 0 && exit == 0 ? 81 : 0;
            case 24: return side == 0 && junction == 0 && exit == 0 ? 114 : 0;
            case 25: return side == 0 && junction == 0 && exit == 0 ? 113 : 0;
            case 52: return side == 0 && junction == 0 && exit == 0 ? 174 : 0;
            case 53: return side == 0 && junction == 0 && exit == 0 ? 173 : 0;
            default: return 0;
        }
    }

    static final int NO_ANGLE = Integer.MIN_VALUE;
    static boolean validAngle(int a) { return a >= -180 && a <= 180; }
    static boolean isRoundaboutType(int type) {
        return type == 6 || type == 7 || type == 19 || (type >= 28 && type <= 46);
    }
    private static final int[] ROUNDABOUT_ICONS = {96, 97, 99, 100, 102, 103, 105, 106};
    private static final int[] INTERSECTION_ICONS = {65, 66, 67, 68, 64, 69, 70, 71};
    private static int sector(int degrees) { return (((degrees + 360) % 360) + 22) / 45 % 8; }
    private static int roundaboutIcon(int degrees) {
        // Known Gen5W glyphs for the closest sector. Preserve the two more-specific
        // legacy glyphs observed for -30/-120; exact clock text uses the real angle.
        if (degrees == -30) return 107;
        if (degrees == -120) return 104;
        return ROUNDABOUT_ICONS[sector(degrees)];
    }

    /** The small next-turn field has fewer glyphs than the current-turn field. */
    static int aheadIcon(Maneuver x) {
        int value = x.primaryIcon(1);
        if (value == 144) return 66;
        if (value == 145) return 71;
        return value >= 65 && value <= 73 ? value : 64;
    }

    /** the HUD's list frame (0xF2): count, this icon, the next turn's icon, 0, road after this turn (UTF-16LE) */
    void sendHud(int type, Integer id, Update u) {
        if (type < 64) return;
        int next = 0;
        String road = "";
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        int[] list = u == null ? null : u.list;
        if (m != null && list != null && list.length > 0) {
            Maneuver x = m.get(list[0]);
            if (x != null) road = x.displayRoad();
            if (list.length > 1) {
                Maneuver y = m.get(list[1]);
                if (y != null && y.distance > 0) next = aheadIcon(y);
            }
        }
        if (hudType == type && hudNext == next && hudRoad != null && hudRoad.equals(road)) return;
        byte[] r;
        try {
            r = road.getBytes("UTF-16LE");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        int n = Math.min(r.length, 78);
        if (n < r.length && n >= 2 && Character.isHighSurrogate(road.charAt(n / 2 - 1))) n -= 2;
        byte[] f = new byte[n + 5];
        f[0] = (byte) 0xF2;
        f[1] = (byte) (next != 0 ? 2 : 1);
        f[2] = (byte) type;
        f[3] = (byte) next;
        System.arraycopy(r, 0, f, 5, n);
        Dipo.log("CarPlay: hud list type " + type + " next " + next);
        cluster.turnListNew(f);
        hudType = type;
        hudNext = next;
        hudRoad = road;
    }

    /** the HUD's distance (updateTBTSubInfo2C: unit, tenths for km/mi, whole up to 4095) */
    void sendHudDist(float f, int unit) {
        if (f < 0f || unit < 0 || unit > 4) return;
        boolean decimal = unit == Cluster.UNIT_KM || unit == Cluster.UNIT_MI;
        int scaled = Math.min(Math.round(f * (decimal ? 10f : 1f)), decimal ? 40959 : 4095);
        if (unit == lastHudUnit && scaled == lastHudScaled) return;
        lastHudUnit = unit;
        lastHudScaled = scaled;
        int whole = decimal ? scaled / 10 : scaled;
        int tenths = decimal ? scaled % 10 : 0;
        cluster.tbtSubInfo2C(unit, tenths, whole, 0, 0, 0, 0, 0, 0);
    }

    /** HUD list and extras cleared (the build's sendHudBlank) */
    void hudBlank() {
        cluster.tbtSubInfo2C(0, 0, 0, 0, 0, 0, 0, 0, 0);
        byte[] lanes = new byte[81];
        lanes[0] = (byte) 0xF1;
        cluster.laneInfo(lanes);
        hudType = -1;
        hudNext = 0;
        hudRoad = null;
        byte[] empty = { (byte) 0xF2 };
        cluster.turnListNew(empty);
        cluster.turnListNew(empty);
    }

    /**
     * Remaining distance and arrival time (updateTBTSubInfo). Distance in the owner's
     * units (Settings), else the phone's. Arrival: a clock time (seconds since 2001 or
     * 1970) or, failing that, now + the time remaining (up to a week).
     */
    void sendTrip(int id, Update u, int ownerUnit) {
        Distance distance = Distance.resolve(u.distLeft, u.distLeftStr, u.distLeftUnits, ownerUnit);
        int hour = -1, minute = -1, timeType = -1, timeFormat;
        Calendar cal = null;
        if (u.eta >= 500000000L && u.eta < 10000000000L) {
            long eta = u.eta < 1500000000L ? u.eta + 978307200L : u.eta;
            cal = Calendar.getInstance();
            cal.setTimeInMillis(eta * 1000L);
        } else if (u.arrivalFromDuration > 0) {
            cal = Calendar.getInstance();
            cal.setTimeInMillis(u.arrivalFromDuration);
        }
        if (cal != null) {
            hour = cal.get(Calendar.HOUR_OF_DAY);
            minute = cal.get(Calendar.MINUTE);
            timeType = 1;
            timeFormat = context == null || DateFormat.is24HourFormat(context) ? 2 : 1;
        } else {
            timeFormat = -1;
        }
        if (distance == null && timeType < 0) return;
        int error = distance == null ? -1 : 0;
        float value = distance == null ? 0f : distance.value;
        int unit = distance == null ? Cluster.UNIT_M : distance.unit;
        offerTrip(id, new TripFrame(value, error, unit, hour, minute, timeType, timeFormat));
    }

    /** Cluster-visible values only, so repeated 120/121 packets cannot redraw unchanged trip data. */
    static final class TripFrame {
        final float value;
        final int error, unit, hour, minute, type, format;
        TripFrame(float value, int error, int unit, int hour, int minute, int type, int format) {
            this.value=value; this.error=error; this.unit=unit; this.hour=hour;
            this.minute=minute; this.type=type; this.format=format;
        }
        boolean matches(TripFrame other) {
            return other != null && value == other.value && error == other.error
                    && unit == other.unit && hour == other.hour && minute == other.minute
                    && type == other.type && format == other.format;
        }
    }

    private void cancelTripFlush() {
        if (tripFlush != null) scheduler.cancel(tripFlush);
        tripFlush = null;
        pendingTrip = null;
        scheduledOwner = null;
    }

    private void writeTrip(int id, TripFrame f) {
        cluster.tbtSubInfo(f.value, f.error, f.unit, f.hour, f.minute, f.type, f.format, 0f, -1, 0);
        tripOwner = id;
        lastTrip = f;
        lastTripAt = scheduler.now();
    }

    private void offerTrip(int id, TripFrame frame) {
        Object owner = scheduler.owner();
        if (owner == null || frame.matches(lastTrip)) {
            cancelTripFlush();
            return;
        }
        long delay = TRIP_INTERVAL_MS - (scheduler.now() - lastTripAt);
        if (lastTrip == null || delay <= 0) {
            cancelTripFlush();
            writeTrip(id, frame);
            return;
        }
        if (scheduledOwner != owner) cancelTripFlush();
        pendingTrip = frame;
        pendingComponent = id;
        scheduledOwner = owner;
        if (tripFlush != null) return;
        tripFlush = new Runnable() {
            public void run() {
                if (tripFlush != this) return;
                TripFrame latest = pendingTrip;
                int component = pendingComponent;
                Object owner = scheduledOwner;
                cancelTripFlush();
                if (outputAllowed && owner == scheduler.owner()) writeTrip(component, latest);
            }
        };
        scheduler.later(tripFlush, delay);
    }

    /** Items 7 and 10 are meters; 8/9 and 11/12 are independent display strings and unit enums. */
    static final class Distance {
        final float value;
        final int unit;
        Distance(float value, int unit) { this.value = value; this.unit = unit; }

        static Distance resolve(int metres, String text, int phoneUnit, int ownerUnit) {
            if (metres >= 0) {
                if (ownerUnit == Car.DISTANCE_MILES) {
                    return metres < 161 ? new Distance(Math.round(metres * 3.28084f), Cluster.UNIT_FT)
                            : new Distance(Math.round(metres / 160.9344d) / 10f, Cluster.UNIT_MI);
                }
                if (ownerUnit == Car.DISTANCE_KM || phoneUnit < 0) {
                    return metres < 1000 ? new Distance(metres, Cluster.UNIT_M)
                            : new Distance(Math.round(metres / 100d) / 10f, Cluster.UNIT_KM);
                }
                switch (phoneUnit) {
                    case 0: return new Distance(Math.round(metres / 100d) / 10f, Cluster.UNIT_KM);
                    case 1: return new Distance(Math.round(metres / 160.9344d) / 10f, Cluster.UNIT_MI);
                    case 3: return new Distance(Math.round(metres / 0.9144d), Cluster.UNIT_YD);
                    case 4: return new Distance(Math.round(metres / 0.3048d), Cluster.UNIT_FT);
                    default: return new Distance(metres, Cluster.UNIT_M);
                }
            }
            if (text == null || text.isEmpty()) return null;
            java.util.regex.Matcher match = DISPLAY.matcher(text.trim());
            if (!match.matches()) return null;
            String number = match.group(1);
            String suffix = match.group(2);
            int unit;
            if (suffix == null) {
                switch (phoneUnit) {
                    case 0: unit=Cluster.UNIT_KM; break;
                    case 1: unit=Cluster.UNIT_MI; break;
                    case 2: unit=Cluster.UNIT_M; break;
                    case 3: unit=Cluster.UNIT_YD; break;
                    case 4: unit=Cluster.UNIT_FT; break;
                    default: return null;
                }
            } else if (suffix.equals("km") || suffix.equals("㎞") || suffix.equals("킬로미터")) unit=Cluster.UNIT_KM;
            else if (suffix.equals("m") || suffix.equals("미터")) unit=Cluster.UNIT_M;
            else if (suffix.equals("mi") || suffix.equals("마일")) unit=Cluster.UNIT_MI;
            else if (suffix.equals("yd") || suffix.equals("야드")) unit=Cluster.UNIT_YD;
            else unit=Cluster.UNIT_FT;
            // Korean numeric strings use comma grouping; also accept one unambiguous decimal comma.
            if (number.indexOf(',') >= 0) {
                if (GROUPED.matcher(number).matches()) number = number.replace(",", "");
                else if (number.indexOf(',') == number.lastIndexOf(',') && number.indexOf('.') < 0)
                    number = number.replace(',', '.');
                else return null;
            }
            try {
                float result = Float.parseFloat(number);
                return Float.isInfinite(result) || result > 65535f ? null : new Distance(result, unit);
            } catch (NumberFormatException ignored) { return null; }
        }

        static final java.util.regex.Pattern DISPLAY = java.util.regex.Pattern.compile(
                "^([0-9]+(?:[.,][0-9]+)*)[\\s\u00a0\u202f]*(km|m|mi|yd|ft|㎞|킬로미터|미터|마일|야드|피트)?$");
        static final java.util.regex.Pattern GROUPED = java.util.regex.Pattern.compile(
                "^[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?$");
    }

    /** up to 3 upcoming turns after this one (updateTPTurnList), distances in the owner's units */
    void sendAhead(Integer id, Update u) {
        int[] list = u == null ? null : u.list;
        if (id == null || list == null || list.length <= 1) return;
        LinkedHashMap<Integer, Maneuver> m = maneuvers.get(id);
        if (m == null) return;
        int count = 0;
        for (int k = 1; k < list.length && count < 3; k++) {
            Maneuver x = m.get(list[k]);
            if (x != null && x.distance > 0) count++;
        }
        if (count == 0) return;
        int owner = Car.distanceUnit(context);
        Cluster.Turn[] turns = new Cluster.Turn[count];
        int n = 0;
        for (int k = 1; k < list.length && n < 3; k++) {
            Maneuver x = m.get(list[k]);
            if (x == null || x.distance <= 0 || n >= count) continue;
            Cluster.Turn t = new Cluster.Turn();
            t.type = aheadIcon(x);
            t.direction = 0;
            t.traffic = 0;
            t.no = n + 1;
            t.name = x.afterRoad == null ? "" : x.afterRoad;
            t.exitNumber = "";
            int d = x.distance;
            float f = d;
            int unit = Cluster.UNIT_M;
            if (owner == Car.DISTANCE_KM) {
                if (d >= 1000) {
                    unit = Cluster.UNIT_KM;
                    f = d / 1000f;
                }
            } else if (owner == Car.DISTANCE_MILES) {
                if (d < 161) {
                    unit = Cluster.UNIT_FT;
                    f = d * 3.28084f;
                } else {
                    unit = Cluster.UNIT_MI;
                    f = d / 1609f;
                }
            }
            if (f < 0f) f = 0f;
            if (f > 65535f) f = 65535f;
            t.distance = f;
            t.unit = unit;
            turns[n++] = t;
        }
        if (n > 0) cluster.turnList(turns);
    }

    /** one display component's route guidance state (DiPO e/c) */
    static final class Update {
        int state, maneuverState, distLeft, distLeftUnits, distNext, nextUnits, count, laneIndex, laneCount;
        String road, destination, distLeftStr, nextStr, sourceName;
        long eta, arrivalFromDuration;
        int[] list;
        boolean visible, lanesShown, supports;

        /** Component IDs come from the phone and may include zero. */
        Update(int id, Iap2NotiMsg msg) {
            if (msg == null || id < 0) return;
            clear();
            read(id, msg);
        }

        void clear() {
            state = -1;
            maneuverState = 1;
            road = "";
            destination = "";
            eta = 0;
            arrivalFromDuration = 0;
            distLeft = -1;
            distLeftStr = "";
            distLeftUnits = -1;
            distNext = -1;
            nextStr = "";
            nextUnits = -1;
            list = null;
            count = 0;
            visible = false;
            laneIndex = 0;
            laneCount = 0;
            lanesShown = false;
            sourceName = "";
            supports = false;
        }

        /** the fields this message carries (by iAP2 type: 1/5 uint8/16, 2/6 uint32, 3/7 uint64, 8 enum, 9 bool, 10 string) */
        void read(int id, Iap2NotiMsg msg) {
            Iap2NotiMsg.Iap2ObjGroup g = msg == null ? null : msg.getItemGroup();
            if (g == null) return;
            int previousIndex = list == null || list.length == 0 ? -1 : list[0];
            boolean rawNext = false, textNext = false, rawTrip = false, textTrip = false;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) continue;
                int item = it.getItemId(), n = it.getItemObjCount();
                switch (it.getItemParamType()) {
                    case 1:
                    case 5: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        switch (item) {
                            case 13:
                                if (v != null) {
                                    list = v.clone();
                                } else {
                                    list = null;
                                }
                                break;
                            case 14: count = v != null && v.length > 0 ? v[0] : 0; break;
                            case 16: laneIndex = v != null && v.length > 0 ? v[0] : 0; break;
                            case 17: laneCount = v != null && v.length > 0 ? v[0] : 0; break;
                        }
                        break;
                    }
                    case 2:
                    case 6: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null || v.length == 0) break;
                        if (item == 7 && v[0] >= 0) { distLeft = v[0]; rawTrip = true; }
                        else if (item == 10 && v[0] >= 0) { distNext = v[0]; rawNext = true; }
                        break;
                    }
                    case 3:
                    case 7: {
                        long[] v = n > 0 ? it.getItemLongArray() : null;
                        if (v == null || v.length == 0) break;
                        if (item == 5) eta = v[0];
                        else if (item == 6 && v[0] >= 0 && v[0] <= 604800L)
                            arrivalFromDuration = System.currentTimeMillis() + v[0] * 1000L;
                        break;
                    }
                    case 8: {
                        int[] v = it.getItemIntArray();
                        if (v == null || v.length == 0) break;
                        switch (item) {
                            case 1: state = v[0]; break;
                            case 2: maneuverState = v[0]; break;
                            case 9: distLeftUnits = v[0]; break;
                            case 12: nextUnits = v[0]; break;
                        }
                        break;
                    }
                    case 9: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null || v.length == 0) break;
                        if (item == 15) visible = v[0] != 0;
                        else if (item == 18) lanesShown = v[0] != 0;
                        else if (item == 20) supports = v[0] != 0;
                        break;
                    }
                    case 10: {
                        String[] v = n > 0 ? it.getItemStringArray() : null;
                        if (v == null || v.length == 0) break;
                        switch (item) {
                            case 3: road = v[0]; break;
                            case 4: destination = v[0] == null ? "" : v[0]; break;
                            case 8: distLeftStr = v[0] == null ? "" : v[0]; textTrip = true; break;
                            case 11: nextStr = v[0] == null ? "" : v[0]; textNext = true; break;
                            case 19: sourceName = v[0]; break;
                        }
                        break;
                    }
                }
            }
            int index = list == null || list.length == 0 ? -1 : list[0];
            if (previousIndex >= 0 && index != previousIndex) {
                if (!rawNext) distNext = -1;
                if (!textNext) nextStr = "";
            }
            // A new formatted-only fix can supersede an older raw sample. A raw
            // field in this same packet takes priority regardless of item order.
            if (textNext && !rawNext && Distance.resolve(-1, nextStr, nextUnits, -1) != null) distNext = -1;
            if (textTrip && !rawTrip && Distance.resolve(-1, distLeftStr, distLeftUnits, -1) != null) distLeft = -1;
        }
    }

    /** one maneuver (DiPO e/v) */
    static final class Maneuver {
        int index, type, distance, distUnits, side, junction, exit, linkedLane;
        String description, afterRoad, distStr;
        int[] angles;

        // Apple CPManeuver: item 11 is the selected road, item 10 represents the
        // other arms. A missing selected bearing is not the same as 0 (12 o'clock).
        int textAngle;
        boolean textUTurn;
        private static final java.util.regex.Pattern CLOCK_TEXT = java.util.regex.Pattern.compile(
            "(?:(?:회전교차로|교차로|로터리)(?:에서)?\\s+)?(1[0-2]|[1-9])\\s*시(?:\\s*(30)\\s*분)?\\s*방향"
            + "(?:으로)?(?:\\s*(?:진출|진입|진행)(?:하세요|합니다)?)?[.!]?");
        private static final java.util.regex.Pattern UTURN_TEXT = java.util.regex.Pattern.compile(
            "(?:(?:교차로|사거리|삼거리)에서\\s+)?유턴(?:하세요|합니다)?[.!]?");

        private void setInstruction(String value) {
            description = value == null ? "" : value.trim();
            java.util.regex.Matcher clock = CLOCK_TEXT.matcher(description);
            textAngle = NO_ANGLE;
            if (clock.matches()) {
                int angle = (Integer.parseInt(clock.group(1)) % 12) * 30 + (clock.group(2) != null ? 15 : 0);
                textAngle = angle <= 180 ? angle : angle - 360;
            }
            textUTurn = UTURN_TEXT.matcher(description).matches();
        }

        private int angle() { return validAngle(exit) ? exit : textAngle; }
        private boolean neutralType() { return type == 0 || type == 5 || type < 0 || type > 53; }

        private boolean selectedAtJunction() {
            return validAngle(exit) && (junction == 1 || (angles != null && angles.length > 1));
        }
        private boolean uTurn() { return type == 4 || type == 18 || type == 19 || type == 26 || (neutralType() && textUTurn); }

        int primaryIcon(int state) {
            if (neutralType()) {
                if (textUTurn) return junction == 1 ? 102 : side == 1 ? 73 : 72;
                if (validAngle(textAngle) || selectedAtJunction()) {
                    return junction == 1 ? roundaboutIcon(angle()) : INTERSECTION_ICONS[sector(angle())];
                }
                if (type != 5) return 0;
            }
            // Use the legacy zero-angle default only for unrelated maneuvers whose
            // stock mapping accepts no geometry (e.g. ramps and destination).
            int selected = angle();
            if (!validAngle(selected) && !isRoundaboutType(type) && junction != 1) selected = 0;
            return icon(type, side, junction, selected, state);
        }

        String displayRoad() {
            String label = "";
            int selected = angle();
            if (uTurn()) {
                label = type == 19 || junction == 1 ? "회전교차로 유턴" : "유턴";
            } else if (isRoundaboutType(type) && !validAngle(selected)) {
                if (type >= 28 && type <= 46) label = (type - 27) + "번째 출구";
                else label = type == 6 ? "회전교차로 진입" : "회전교차로 진출";
            } else if (validAngle(selected) && clockUseful(selected)) {
                int bearing = (selected + 360) % 360;
                int halfHours = ((bearing + 7) / 15) % 24;
                int hour = halfHours / 2;
                label = (bearing % 15 == 0 ? "" : "약 ") + (hour == 0 ? 12 : hour)
                        + (halfHours % 2 == 0 ? "시 방향" : "시 30분 방향");
            }
            String road = afterRoad == null ? "" : afterRoad;
            if (label.isEmpty()) return road;
            return road.isEmpty() ? label : label + " · " + road;
        }

        private boolean clockUseful(int selected) {
            if (isRoundaboutType(type) || junction == 1 || (neutralType() && selectedAtJunction())) return true;
            if ((type == 1 || type == 13 || type == 20 || type == 47 || type == 49) && selected >= 0) return false;
            if ((type == 2 || type == 14 || type == 21 || type == 48 || type == 50) && selected <= 0) return false;
            if (validAngle(textAngle)) return true;
            boolean directional = type == 1 || type == 2 || type == 3 || type == 13 || type == 14
                    || type == 20 || type == 21 || (type >= 47 && type <= 50);
            if (!directional) return false;
            if (angles != null && angles.length > 1) return true;
            int magnitude = Math.abs(selected);
            return magnitude != 0 && magnitude != 90;
        }

        Maneuver(int index, int id, Iap2NotiMsg msg) {
            if (msg == null) return;
            clear();
            read(index, id, msg);
        }

        void clear() {
            index = -1;
            description = "";
            type = 0;
            afterRoad = "";
            distance = 0;
            distStr = "";
            distUnits = 2;
            side = 0;
            junction = 0;
            angles = null;
            exit = NO_ANGLE;
            textAngle = NO_ANGLE;
            textUTurn = false;
            linkedLane = 0;
        }

        /** iAP2 maneuver deltas retain the previous fields for the same index. */
        void read(int index, int id, Iap2NotiMsg msg) {
            Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
            // Reused maneuver indexes can acquire a new type during a reroute.
            // Clear old geometry/instructions before applying this entire delta in wire order.
            if (type != 0) {
                for (int k = 0; k < g.getItemCnt(); k++) {
                    Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                    if (it == null || it.getItemId() != 3 || it.getItemParamType() != 8) continue;
                    int[] value = it.getItemIntArray();
                    if (value != null && value.length > 0 && value[0] != type) clear();
                    break;
                }
            }
            this.index = index;
            for (int k = 0; k < g.getItemCnt(); k++) {
                Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                if (it == null) continue;
                int item = it.getItemId(), n = it.getItemObjCount();
                switch (it.getItemParamType()) {
                    case 1: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 10) {
                            if (v != null) {
                                angles = v.clone();
                            } else {
                                angles = null;
                            }
                        } else if (item == 11 && v != null && v.length > 0) {
                            exit = validAngle(v[0]) ? v[0] : NO_ANGLE;
                        }
                        break;
                    }
                    case 5: {
                        int[] v = it.getItemIntArray();
                        if (item == 12 && v != null && v.length > 0) linkedLane = v[0];
                        break;
                    }
                    case 6: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (item == 5 && v != null && v.length > 0) distance = v[0];
                        break;
                    }
                    case 8: {
                        int[] v = n > 0 ? it.getItemIntArray() : null;
                        if (v == null || v.length == 0) break;
                        switch (item) {
                            case 3: type = v[0]; break;
                            case 7: distUnits = v[0]; break;
                            case 8: side = v[0]; break;
                            case 9: junction = v[0]; break;
                        }
                        break;
                    }
                    case 10: {
                        String[] v = it.getItemStringArray();
                        if (v == null || v.length == 0) break;
                        switch (item) {
                            case 2: setInstruction(v[0]); break;
                            case 4: afterRoad = v[0]; break;
                            case 6: distStr = v[0]; break;
                        }
                        break;
                    }
                }
            }
        }
    }
}
