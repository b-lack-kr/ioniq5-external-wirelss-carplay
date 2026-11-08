package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import java.nio.charset.StandardCharsets;

public final class ClusterFramesTest {
    static final int COMPONENT = 7, INDEX = 3;
    static final int U8 = 1, U16 = 5, U32 = 6, ENUM = 8, STR = 10;
    static final String ROAD = "세종대로";

    final Cluster receiver = new Cluster();
    final Schedule schedule = new Schedule();
    final CpTurns sender = new CpTurns(new Context(), receiver, null, schedule);

    static final class Schedule implements CpTurns.TripScheduler {
        long now;
        Runnable work;
        final java.util.LinkedHashMap<Runnable, Long> jobs = new java.util.LinkedHashMap<Runnable, Long>();
        Object owner = new Object();
        public long now() { return now; }
        public Object owner() { return owner; }
        public void later(Runnable r, long delay) {
            check(delay > 0 && !jobs.containsKey(r), "each task has only one outstanding callback");
            jobs.put(r, now + delay); work = r;
        }
        public void cancel(Runnable r) { jobs.remove(r); if (work == r) work = jobs.isEmpty() ? null : jobs.keySet().iterator().next(); }
        void advance(long millis) {
            long target = now + millis;
            while (true) {
                Runnable first = null; long due = Long.MAX_VALUE;
                for (java.util.Map.Entry<Runnable,Long> job : jobs.entrySet()) if (job.getValue() < due) {
                    first = job.getKey(); due = job.getValue();
                }
                if (first == null || due > target) break;
                now = due; cancel(first); first.run();
            }
            now = target;
        }
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void item(Iap2NotiMsg msg, int id, int type, int... values) {
        msg.getItemGroup().add(id, type, values);
    }

    static void string(Iap2NotiMsg msg, int id, String text) {
        msg.getItemGroup().add(id, STR, new String[]{text});
    }

    static Iap2NotiMsg route(int component, int... indices) {
        Iap2NotiMsg msg = new Iap2NotiMsg();
        item(msg, 0, U16, component);
        item(msg, 1, ENUM, 1);
        item(msg, 12, ENUM, 2);  // metres
        string(msg, 11, "200");
        item(msg, 13, U16, indices);
        return msg;
    }

    static Iap2NotiMsg rightTurn(int component, String road) {
        Iap2NotiMsg msg = new Iap2NotiMsg();
        if (component >= 0) item(msg, 0, U16, component);
        item(msg, 1, U16, INDEX);
        item(msg, 3, ENUM, 2);    // right
        item(msg, 8, ENUM, 0);    // right-hand traffic
        item(msg, 9, ENUM, 0);    // intersection
        item(msg, 11, U8, 90);
        string(msg, 4, road);
        return msg;
    }

    void expect(String line) {
        check(receiver.calls.contains(line), "Expected " + line + ", saw " + receiver.calls);
    }

    boolean has(String prefix) {
        for (String call : receiver.calls) if (call.startsWith(prefix)) return true;
        return false;
    }

    // The recording cluster serializes a received byte frame as hex, trimming trailing zeros.
    static String roadFrame(String road) {
        StringBuilder frame = new StringBuilder("hud f201430000");
        for (byte b : road.getBytes(StandardCharsets.UTF_16LE)) frame.append(String.format("%02x", b & 255));
        return frame.toString();
    }

    void expectTurn(String road, int distance) {
        expect("road [" + road + "]");
        expect("tbt 67 0 0.0 " + distance + ".0 0 0 0 0 0");
        expect("2c 0 0 " + distance + " 0 0 0 0 0 0");
        expect(roadFrame(road));
    }

    void lateManeuver() {
        sender.routeGuidance(route(COMPONENT, INDEX));
        check(!has("tbt 67") && !has("hud f201"), "drawn without maneuver");
        sender.maneuver(rightTurn(-1, ROAD));   // componentless delta to a known route
        expectTurn(ROAD, 200);

        receiver.calls.clear();
        Iap2NotiMsg changedRoad = new Iap2NotiMsg();
        item(changedRoad, 0, U16, COMPONENT);
        item(changedRoad, 1, U16, INDEX);
        string(changedRoad, 4, "을지로");
        sender.maneuver(changedRoad);
        expect("road [을지로]");
        expect(roadFrame("을지로"));
        check(!has("tbt ") && !has("2c "), "road-only update resent unchanged arrow or HUD distance");

        receiver.calls.clear();
        Iap2NotiMsg distance = new Iap2NotiMsg();
        string(distance, 11, "100");
        sender.routeGuidance(distance);
        expect("tbt 67 0 0.0 100.0 0 0 0 0 0");
        expect("2c 0 0 100 0 0 0 0 0 0");
        check(!has("hud f201"), "unchanged HUD road resent on distance-only delta");
    }

    void earlyManeuver() {
        sender.maneuver(rightTurn(0, ROAD));   // component zero is explicitly present
        check(!has("tbt ") && !has("hud "), "idle receiver wrote cluster output");
        sender.routeGuidance(route(0, INDEX));
        expectTurn(ROAD, 200);
    }

    void componentlessFirstRoute() {
        sender.maneuver(rightTurn(COMPONENT, ROAD));
        Iap2NotiMsg m = new Iap2NotiMsg();
        item(m, 1, ENUM, 1);
        item(m, 12, ENUM, 2);
        string(m, 11, "200");
        item(m, 13, U16, INDEX);
        sender.routeGuidance(m);
        expectTurn(ROAD, 200);
    }

    void stopAndDisconnect() {
        sender.stop();
        sender.disconnected();
        check(receiver.calls.isEmpty(), "idle listener cleared OEM guidance");
        sender.maneuver(rightTurn(COMPONENT, ROAD));
        sender.routeGuidance(route(COMPONENT, INDEX));
        receiver.calls.clear();
        Iap2NotiMsg ended = new Iap2NotiMsg();
        item(ended, 0, U16, COMPONENT);
        item(ended, 1, ENUM, 0);
        sender.routeGuidance(ended);
        check(has("tbt 8 "), "phone turn icon was not cleared");
        expect("hud f2");
        receiver.calls.clear();
        sender.stop();
        sender.disconnected();
        check(receiver.calls.isEmpty(), "duplicate teardown wrote to the cluster");
        sender.routeGuidance(route(COMPONENT, INDEX));
        check(!has("tbt 67") && !has("hud f201"), "restored a stale maneuver after disconnect");
    }

    void utf16Boundary() {
        // 38 Hangul syllables occupy 76 bytes. A following surrogate pair cannot fit
        // in the remaining two bytes of the 78-byte HUD road-name field.
        String prefix = String.join("", java.util.Collections.nCopies(38, "길"));
        String longName = prefix + "🚘로";
        sender.routeGuidance(route(COMPONENT, INDEX));
        sender.maneuver(rightTurn(COMPONENT, longName));
        expect("road [" + longName + "]");
        expect(roadFrame(prefix));
    }

    void emptyManeuverList() {
        sender.maneuver(rightTurn(COMPONENT, ROAD));
        sender.routeGuidance(route(COMPONENT));
        check(!has("tbt 67") && !has("hud f201"), "empty list selected a previous turn");
        sender.routeGuidance(route(COMPONENT, INDEX));
        expectTurn(ROAD, 200);
    }

    void pendingManeuverReplacesOldArrow() {
        sender.maneuver(rightTurn(COMPONENT, ROAD));
        sender.routeGuidance(route(COMPONENT, INDEX));
        receiver.calls.clear();
        sender.routeGuidance(route(COMPONENT, INDEX + 1));
        check(has("tbt 8 "), "old right-turn arrow survived an unavailable new maneuver");
        check(!has("tbt 67 "), "new maneuver reused old arrow");
    }

    void missingAngleAndFormattedDistance() {
        Iap2NotiMsg msg = new Iap2NotiMsg();
        item(msg, 0, U16, COMPONENT);
        item(msg, 1, ENUM, 1);
        item(msg, 10, U32, 1500);
        item(msg, 12, ENUM, 0);   // formatted field is advertised as kilometres but unusable
        string(msg, 11, "unavailable");
        item(msg, 13, U16, INDEX);
        sender.routeGuidance(msg);
        Iap2NotiMsg turn = new Iap2NotiMsg();
        item(turn, 0, U16, COMPONENT);
        item(turn, 1, U16, INDEX);
        item(turn, 3, ENUM, 2);
        string(turn, 4, ROAD);
        sender.maneuver(turn);
        expect("tbt 67 0 0.0 1.5 0 0 1 0 0");
        expect("2c 1 5 1 0 0 0 0 0 0");
    }

    void distanceSourcesAndTripStability() {
        Iap2NotiMsg start = route(COMPONENT, INDEX);
        item(start, 7, U32, 12500);
        item(start, 10, U32, 370);
        sender.routeGuidance(start);
        expect("sub 12.5 0 1 -1 -1 -1 -1 0.0 -1 0");
        sender.maneuver(rightTurn(COMPONENT, ROAD));
        expectTurn(ROAD, 370); // first 120 contained a different formatted number: raw meters win
        receiver.calls.clear();
        Iap2NotiMsg localized = new Iap2NotiMsg();
        string(localized, 11, "1.2 ㎞");
        sender.routeGuidance(localized);
        expect("tbt 67 0 0.0 1.2 0 0 1 0 0");
        expect("2c 1 2 1 0 0 0 0 0 0");
        check(!has("sub "), "unchanged trip totals re-emitted on each turn field");

        Iap2NotiMsg missing = new Iap2NotiMsg();
        item(missing, 0, U16, COMPONENT);
        item(missing, 13, U16, INDEX + 1);
        sender.routeGuidance(missing);
        check(has("tbt 8 ") && !has("sub ") && !has("dest "), "temporary missing 121 blanked trip totals");
        receiver.calls.clear();
        Iap2NotiMsg zero = new Iap2NotiMsg();
        item(zero, 10, U32, 0);
        item(zero, 7, U32, 0);
        sender.routeGuidance(zero);
        schedule.advance(1000);
        expect("sub 0.0 0 0 -1 -1 -1 -1 0.0 -1 0");  // zero and not available are distinct
        sender.stop();
        expect("sub 0.0 -1 0 0 0 0 0 0.0 -1 0");
    }

    void zeroTextAndFreshRawDistance() {
        Iap2NotiMsg start = route(COMPONENT, INDEX);
        string(start, 11, "0");
        item(start, 10, U32, 300);
        sender.maneuver(rightTurn(COMPONENT, ROAD));
        sender.routeGuidance(start);
        expectTurn(ROAD, 300);
        receiver.calls.clear();
        Iap2NotiMsg rawOnly = new Iap2NotiMsg();
        item(rawOnly, 10, U32, 200);
        sender.routeGuidance(rawOnly);
        expect("tbt 67 0 0.0 200.0 0 0 0 0 0");
        expect("2c 0 0 200 0 0 0 0 0 0");

        // A queued 121 may precede the index change. Old distance cannot leak
        // into either rendered frame before the next 120 distance field arrives.
        Iap2NotiMsg next = new Iap2NotiMsg();
        item(next, 0, U16, COMPONENT);
        item(next, 1, U16, INDEX+1);
        item(next, 3, ENUM, 2);
        sender.maneuver(next);
        receiver.calls.clear();
        Iap2NotiMsg changeIndex = new Iap2NotiMsg();
        item(changeIndex, 13, U16, INDEX+1);
        sender.routeGuidance(changeIndex);
        expect("tbt 67 0 0.0 0.0 -1 0 0 0 0");
        expect("2c 0 0 0 0 0 0 0 0 0");
        check(!has("sub "), "cleared a trip on index change");
    }

    void localizedDistanceOnly() {
        Iap2NotiMsg start = route(COMPONENT, INDEX);
        string(start, 11, "1,200 미터");
        string(start, 8, "12.5km");
        sender.routeGuidance(start);
        sender.maneuver(rightTurn(COMPONENT, ROAD));
        expectTurn(ROAD, 1200);
        expect("sub 12.5 0 1 -1 -1 -1 -1 0.0 -1 0");
    }

    static Iap2NotiMsg tripDistance(int meters) {
        Iap2NotiMsg msg = new Iap2NotiMsg();
        item(msg, 7, U32, meters);
        return msg;
    }

    void latestTripFlushAndTeardown() {
        Iap2NotiMsg start = route(COMPONENT, INDEX);
        item(start, 7, U32, 900);
        sender.maneuver(rightTurn(COMPONENT, ROAD));
        sender.routeGuidance(start);
        expect("sub 900.0 0 0 -1 -1 -1 -1 0.0 -1 0");
        receiver.calls.clear();
        schedule.advance(10);
        sender.routeGuidance(tripDistance(890));
        schedule.advance(100);
        sender.routeGuidance(tripDistance(880));
        string(start = new Iap2NotiMsg(), 11, "42 m");
        sender.routeGuidance(start);
        expect("tbt 67 0 0.0 42.0 0 0 0 0 0");
        expect("2c 0 0 42 0 0 0 0 0 0");
        check(!has("road ") && !has("sub "), "trip burst redrew until deadline or resent road");
        schedule.advance(889);
        check(!has("sub "), "trip published early");
        schedule.advance(1);
        expect("sub 880.0 0 0 -1 -1 -1 -1 0.0 -1 0");
        receiver.calls.clear();
        sender.routeGuidance(tripDistance(870));
        sender.routeGuidance(tripDistance(880)); // the latest value is back to displayed value
        schedule.advance(1000);
        check(!has("sub "), "redundant trailing trip write");
        sender.routeGuidance(tripDistance(860));
        sender.routeGuidance(tripDistance(850));
        Runnable stale = schedule.work;
        sender.disconnected();
        check(schedule.work == null, "disconnect retained scheduled trip update");
        expect("sub 0.0 -1 0 0 0 0 0 0.0 -1 0"); // clear immediately
        receiver.calls.clear();
        stale.run();
        check(!has("sub "), "stale queued callback wrote warnings over the next owner");
    }

    void nativeTakeoverDuringDelay() {
        Iap2NotiMsg start = route(COMPONENT, INDEX);
        item(start, 7, U32, 500);
        sender.routeGuidance(start);
        sender.routeGuidance(tripDistance(480));
        receiver.calls.clear();
        schedule.owner = null;     // native navigation took over before the deadline
        schedule.advance(1000);
        check(!has("sub "), "pending trip wrote while native navigation owned the display");
        schedule.owner = new Object();
        sender.routeGuidance(tripDistance(460));
        expect("sub 460.0 0 0 -1 -1 -1 -1 0.0 -1 0");
        sender.routeGuidance(tripDistance(450));
        receiver.calls.clear();
        schedule.owner = new Object(); // a replacement binder/session appeared
        schedule.advance(1000);
        check(!has("sub "), "old trip emitted on a different session");
    }

    void show(int type, int junction, Integer angle, String instruction, String road) {
        Iap2NotiMsg msg = new Iap2NotiMsg();
        item(msg, 0, U16, COMPONENT);
        item(msg, 1, U16, INDEX);
        item(msg, 3, ENUM, type);
        item(msg, 9, ENUM, junction);
        if (angle != null) item(msg, 11, U8, angle);
        if (instruction != null) string(msg, 2, instruction);
        if (road != null) string(msg, 4, road);
        sender.routeGuidance(route(COMPONENT, INDEX));
        receiver.calls.clear();
        sender.maneuver(msg);
    }

    void both(int icon, String road) {
        expect("road [" + road + "]");
        check(has("tbt " + icon + " "), "wrong or missing primary icon " + icon + ": " + receiver.calls);
        // Check the independent HUD 0xF2 current glyph and UTF-16LE label.
        String prefix = String.format("hud f201%02x0000", icon);
        expect(prefix + roadFrame(road).substring("hud f201430000".length()));
    }

    void junctions() {
        for (int t : new int[]{4, 18, 26}) {
            ClusterFramesTest f = new ClusterFramesTest();
            f.show(t, 0, -175, null, ROAD);
            f.both(72, "유턴 · " + ROAD);
        }
        check(CpTurns.icon(26, 1, 0, 0, 1) == 73, "left-traffic U-turn");
        show(19, 1, null, null, ROAD);
        both(102, "회전교차로 유턴 · " + ROAD);
        for (int t = 28; t <= 46; t++) {
            // The very same chosen angle applies even for exit 1 and exit 19.
            ClusterFramesTest f = new ClusterFramesTest();
            f.show(t, 1, -90, null, ROAD);
            f.both(105, "9시 방향 · " + ROAD);
        }
        for (int t : new int[]{6, 7}) {
            ClusterFramesTest f = new ClusterFramesTest();
            f.show(t, 1, 90, null, null);
            f.both(99, "3시 방향");
        }
        ClusterFramesTest ordinary = new ClusterFramesTest();
        ordinary.show(2, 0, 45, null, ROAD);
        ordinary.both(144, "1시 30분 방향 · " + ROAD);
        ClusterFramesTest exactlyZero = new ClusterFramesTest();
        exactlyZero.show(30, 1, 0, null, null);
        exactlyZero.both(96, "12시 방향");
        ClusterFramesTest noBearing = new ClusterFramesTest();
        noBearing.show(30, 1, null, null, ROAD);
        noBearing.both(64, "3번째 출구 · " + ROAD);
        // An angle list is not a selected exit and cannot turn the ordinal into 3 o'clock.
        Iap2NotiMsg arms = new Iap2NotiMsg();
        item(arms, 1, U16, INDEX);
        item(arms, 10, U8, 90, 120, -90);
        noBearing.receiver.calls.clear();
        noBearing.sender.maneuver(arms);
        check(!noBearing.has("hud f2") && !noBearing.has("road ["), "unselected arms became selected");
        Iap2NotiMsg exit = new Iap2NotiMsg();
        item(exit, 1, U16, INDEX);
        item(exit, 11, U8, 120);
        noBearing.sender.maneuver(exit);
        noBearing.both(100, "4시 방향 · " + ROAD);

        ClusterFramesTest reroute = new ClusterFramesTest();
        reroute.show(46, 1, 90, null, ROAD);
        Iap2NotiMsg nextType = new Iap2NotiMsg();
        item(nextType, 1, U16, INDEX);
        string(nextType, 4, ROAD); // field order before the new type
        item(nextType, 3, ENUM, 29);
        reroute.receiver.calls.clear();
        reroute.sender.maneuver(nextType);
        reroute.both(64, "2번째 출구 · " + ROAD);
    }

    void currentInstructionFallback() {
        show(0, 0, null, "교차로에서 2시 방향으로 진출하세요", ROAD);
        both(66, "2시 방향 · " + ROAD);
        ClusterFramesTest u = new ClusterFramesTest();
        u.show(0, 0, null, "유턴하세요", ROAD);
        u.both(72, "유턴 · " + ROAD);
        for (String desc : new String[]{"유턴 금지", "우회전 후 유턴", "3시 방향 약국", "13시 방향"}) {
            ClusterFramesTest f = new ClusterFramesTest();
            f.show(0, 0, null, desc, "유턴교차로 3시 방향"); // names never supply a turn
            check(!f.has("tbt ") && !f.has("hud f2"), "invented turn from text: " + desc);
        }
        ClusterFramesTest follow = new ClusterFramesTest();
        follow.show(5, 0, null, "교차로 1시 방향", ROAD);
        follow.both(66, "1시 방향 · " + ROAD);
        ClusterFramesTest complex = new ClusterFramesTest();
        complex.show(5, 0, 60, null, ROAD);
        Iap2NotiMsg arms = new Iap2NotiMsg();
        item(arms, 1, U16, INDEX);
        item(arms, 10, U8, -90, 0, 90);
        complex.receiver.calls.clear();
        complex.sender.maneuver(arms);
        complex.both(66, "2시 방향 · " + ROAD);
        ClusterFramesTest conflict = new ClusterFramesTest();
        conflict.show(1, 0, -90, "교차로에서 3시 방향", ROAD);
        check(!conflict.has("road [3시"), "text overrode contradictory typed turn/angle");
    }

    void instructionLabels() {
        show(30, 1, 150, "회전교차로에서 5시 방향", "회전교차로에서 5시 방향");
        both(100, "회전교차로에서 5시 방향");
        ClusterFramesTest clock = new ClusterFramesTest();
        clock.show(3, 0, 45, "1시 30분 방향", "교차로에서 1시 30분 방향으로 진출하세요");
        clock.both(65, "교차로에서 1시 30분 방향으로 진출하세요");
        ClusterFramesTest roadName = new ClusterFramesTest();
        roadName.show(30, 1, 150, null, "5시 방향 약국");
        roadName.both(100, "5시 방향 · 5시 방향 약국"); // don't delete part of a POI
        ClusterFramesTest ordinary = new ClusterFramesTest();
        ordinary.show(30, 1, 150, null, ROAD);
        ordinary.both(100, "5시 방향 · " + ROAD);
        ClusterFramesTest u = new ClusterFramesTest();
        u.show(4, 0, null, "유턴", "교차로에서 유턴하세요");
        u.both(72, "교차로에서 유턴하세요");

        ClusterFramesTest tunnel = new ClusterFramesTest();
        tunnel.show(3, 0, null, "금정터널 진입", ROAD);
        tunnel.both(65, "금정터널 진입 · " + ROAD);
        ClusterFramesTest sameTunnel = new ClusterFramesTest();
        sameTunnel.show(3, 0, null, "터널 진입", "터널 진입");
        sameTunnel.both(65, "터널 진입");
        ClusterFramesTest noTunnelData = new ClusterFramesTest();
        noTunnelData.show(3, 0, null, null, ROAD);
        noTunnelData.both(65, ROAD); // no invented tunnel or guessed native icon
        ClusterFramesTest noTunnelGuess = new ClusterFramesTest();
        noTunnelGuess.show(0, 0, null, "터널 진입", ROAD);
        check(!noTunnelGuess.has("tbt "), "tunnel text invented an unsupported maneuver icon");

        // A road-only field delta must also remove the duplicate and update both displays once.
        ordinary.receiver.calls.clear();
        Iap2NotiMsg delta = new Iap2NotiMsg();
        item(delta, 1, U16, INDEX);
        string(delta, 4, "회전교차로에서 5시 방향");
        ordinary.sender.maneuver(delta);
        ordinary.expect("road [회전교차로에서 5시 방향]");
        check(!ordinary.has("tbt "), "text-only delta resent the unchanged maneuver glyph");
    }

    void rampsAndCoverage() {
        int[][] cases = {
            {8,CpTurns.NO_ANGLE,86}, {8,0,86}, {8,-45,82}, {8,45,81}, {8,180,86}, {8,-180,86},
            {9,CpTurns.NO_ANGLE,172}, {9,0,172}, {9,-30,174}, {9,30,173}, {9,180,172},
            {22,-90,82}, {22,90,82}, {23,90,81}, {23,-90,81}, // explicit type wins over contrary geometry
            {51,-45,174}, {51,45,173}, {52,-30,174}, {53,30,173}
        };
        for (int[] c : cases) {
            ClusterFramesTest f = new ClusterFramesTest();
            f.show(c[0],0,c[1] == CpTurns.NO_ANGLE ? null : Integer.valueOf(c[1]),null,ROAD);
            f.both(c[2],ROAD);
        }
        for (Integer angle : new Integer[]{null,0,180,-180}) {
            ClusterFramesTest f = new ClusterFramesTest();
            f.show(51,0,angle,null,ROAD);
            check(!f.has("tbt ") && !f.has("hud f2"), "generic highway change invented a branch");
        }
        check(CpTurns.icon(8,0,0,CpTurns.NO_ANGLE,1) == 86, "NO_ANGLE must not mean left off-ramp");
        check(CpTurns.icon(9,0,0,CpTurns.NO_ANGLE,1) == 172, "NO_ANGLE must not mean left on-ramp");
        check(CpTurns.icon(51,0,0,CpTurns.NO_ANGLE,1) == 0, "NO_ANGLE must not mean left highway change");
        // Full public enum audit: all 53 nonzero types are explicitly classified.
        // Zero entries are intentional unresolved/lifecycle-only types, not a claim
        // that an arrival/start/ferry change is an arbitrary destination/ferry icon.
        int[] expectedWithoutBearing = {0,70,67,65,72,172,64,64,86,172,112,0,112,71,66,176,0,0,72,102,70,67,82,81,114,113,72,112,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,69,68,71,66,0,174,173};
        check(expectedWithoutBearing.length == 54, "public maneuver coverage table incomplete");
        for (int type=0;type<=53;type++) {
            Iap2NotiMsg msg = new Iap2NotiMsg();
            item(msg,3,ENUM,type);
            CpTurns.Maneuver maneuver = new CpTurns.Maneuver(INDEX,COMPONENT,msg);
            check(maneuver.primaryIcon(1) == expectedWithoutBearing[type], "unexpected no-bearing result for type " + type);
        }
    }

    public static void main(String[] args) {
        Car.unit = Car.DISTANCE_KM;
        new ClusterFramesTest().rampsAndCoverage();
        new ClusterFramesTest().instructionLabels();
        new ClusterFramesTest().junctions();
        new ClusterFramesTest().currentInstructionFallback();
        System.out.println("PASS maneuver variants: U-turn, clock direction, roundabout ordinal and selected exit on cluster and HUD");
        new ClusterFramesTest().lateManeuver();
        new ClusterFramesTest().earlyManeuver();
        new ClusterFramesTest().componentlessFirstRoute();
        new ClusterFramesTest().stopAndDisconnect();
        new ClusterFramesTest().utf16Boundary();
        new ClusterFramesTest().emptyManeuverList();
        new ClusterFramesTest().pendingManeuverReplacesOldArrow();
        new ClusterFramesTest().missingAngleAndFormattedDistance();
        new ClusterFramesTest().distanceSourcesAndTripStability();
        new ClusterFramesTest().localizedDistanceOnly();
        new ClusterFramesTest().latestTripFlushAndTeardown();
        new ClusterFramesTest().nativeTakeoverDuringDelay();
        new ClusterFramesTest().zeroTextAndFreshRawDistance();
        System.out.println("PASS: U-turn variants, roundabouts exit 1–19, signed/absent bearings, Korean clock text on cluster and HUD, safe current instruction, late/early maneuvers, componentless field deltas, Korean HUD/cluster frames, empty state, stop/disconnect, UTF-16 boundary, pending turn and numeric/locale distances; stable totals, 1s latest-value flush, immediate clears, canceled stale/native takeovers, deduplicated arrow/road/2C");
    }
}
