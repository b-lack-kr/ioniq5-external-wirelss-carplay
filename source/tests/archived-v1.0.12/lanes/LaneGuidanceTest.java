package hmgapi.mods.wireless.dipo;

import android.content.Context;
import com.tcc.iap2.Iap2NotiMsg;
import hmgapi.hook.Cluster;
import java.util.Arrays;

/** Fixtures exercise incoming iAP2 fields and the actual sender, not a second encoder. */
public final class LaneGuidanceTest {
    static final int U16 = 5, ENUM = 8, GROUPS = 13;
    static int checks;

    static void check(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }

    static Iap2NotiMsg.Iap2ObjGroup lane(Integer status, int[] secondary, int[] primary) {
        Iap2NotiMsg msg = new Iap2NotiMsg();
        Iap2NotiMsg.Iap2ObjGroup group = msg.getItemGroup();
        if (status != null) group.add(1, ENUM, new int[]{status});
        if (secondary != null) group.add(2, U16, secondary);
        if (primary != null) group.add(3, U16, primary);
        return group;
    }

    static void expect(String title, int[] expected, Iap2NotiMsg.Iap2ObjGroup lane) {
        int[] actual = CpTurns.laneOne(lane);
        check(Arrays.equals(actual, expected), title + ": " + Arrays.toString(actual));
    }

    static Iap2NotiMsg guidance(Iap2NotiMsg.Iap2ObjGroup... lanes) {
        Iap2NotiMsg msg = new Iap2NotiMsg();
        msg.getItemGroup().add(2, GROUPS, lanes);
        return msg;
    }

    static int frameByte(String call, int index) {
        String hex = call.substring("lanes ".length());
        return hex.length() <= 2 * index ? 0 : Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
    }

    public static void main(String[] args) {
        // Apple's CPLane contract excludes primaryAngle from secondaryAngles.
        // These are deliberately disjoint, unlike the old duplicated-angle fixture.
        expect("preferred primary-only straight stays blue", new int[]{2,1,0,0},
                lane(2, new int[0], new int[]{0}));
        expect("good primary-only right is white, not blue", new int[]{1,3,0,0},
                lane(1, null, new int[]{90}));
        expect("unverified four-way set folds to verified L/S/R glyphs", new int[]{47,28,22,24},
                lane(2, new int[]{0,-90,45}, new int[]{90}));
        expect("duplicate classes merge route status and use overlaid glyphs", new int[]{11,16,18,0},
                lane(2, new int[]{90,91,0,360}, new int[]{90}));
        expect("omitted status defaults good, with grey non-route arrow", new int[]{7,9,12,0},
                lane(null, new int[]{0}, new int[]{270}));
        expect("not-good greys every arrow", new int[]{15,16,18,0},
                lane(0, new int[]{0,90}, null));
        expect("preferred with no highlight colors all valid arrows blue", new int[]{2,7,0,0},
                lane(2, new int[]{-90}, null));
        expect("good without highlight colors all white", new int[]{1,1,0,0},
                lane(1, new int[]{0}, null));
        expect("unknown status defaults good", new int[]{1,1,0,0},
                lane(99, new int[]{0}, null));
        expect("invalid primary creates no U-turn", new int[]{1,1,0,0},
                lane(null, new int[]{0}, new int[]{65535}));
        expect("unknown lane preserves physical position", new int[]{1,0,0,0},
                lane(null, null, null));
        expect("three slots have independent two-bit colors", new int[]{23,28,22,24},
                lane(1, new int[]{-90}, new int[]{0,90}));
        expect("three selected good arrows have all three white fields", new int[]{21,28,22,24},
                lane(1, null, new int[]{0,90,-90}));
        expect("not-good overrides highlighted arrow", new int[]{15,16,18,0},
                lane(0, new int[]{0}, new int[]{90}));
        check(CpTurns.laneIcon(180) == 5 && CpTurns.laneIcon(-180) == 5
                && CpTurns.laneIcon(270) == 7 && CpTurns.laneIcon(360) == 1, "angle wrap boundaries");
        check(CpTurns.laneIcon(Integer.MIN_VALUE) == 0 && CpTurns.laneIcon(Integer.MAX_VALUE) == 0,
                "sentinels are not directions");

        Cluster cluster = new Cluster();
        CpTurns sender = new CpTurns(new Context(), cluster, null, null);
        sender.displayed = true;
        sender.turnOwner = 0;
        sender.updates.put(0, new CpTurns.Update(0, new Iap2NotiMsg()));
        sender.lanes(guidance(lane(0, new int[]{0}, null), lane(2, null, new int[]{90})));
        String first = cluster.calls.get(0);
        check(frameByte(first, 1) == 2, "two incoming lanes emit two lanes");
        check(frameByte(first, 3) == 3 && frameByte(first, 4) == 2, "recommendation stays on the second lane");
        check(frameByte(first, 19) == 1 && frameByte(first, 22) == 3, "both lanes contain arrows");
        check(frameByte(first, 5) == 0 && frameByte(first, 25) == 0, "unused output slots stay zero");
        Iap2NotiMsg metadata = new Iap2NotiMsg();
        metadata.getItemGroup().add(1, U16, new int[]{8});
        sender.lanes(metadata);
        check(cluster.calls.size() == 1, "metadata-only LaneGuidance must not clear active lanes");
        sender.lanes(guidance());
        check(cluster.calls.get(1).equals("lanes f1"), "explicit empty list clears the frame");
        sender.lanes(guidance(lane(0, new int[]{0}, null), null, lane(2, null, new int[]{90})));
        String unknown = cluster.calls.get(2);
        check(frameByte(unknown, 1) == 3 && frameByte(unknown, 5) == 2 && frameByte(unknown, 25) == 3,
                "missing lane information must not shift the recommended lane position");
        Iap2NotiMsg.Iap2ObjGroup[] many = new Iap2NotiMsg.Iap2ObjGroup[10];
        Arrays.fill(many, lane(0, new int[]{0}, null));
        sender.lanes(guidance(many));
        check(frameByte(cluster.calls.get(3), 1) == 8, "preserve existing eight-lane capacity");
        sender.outputAllowed = false;
        sender.lanes(guidance());
        check(cluster.calls.size() == 4, "disabled output does not overwrite stock guidance");
        int reports = Dipo.logs.size();
        sender.outputAllowed = true;
        sender.lanes(guidance(many));
        check(Dipo.logs.size() == reports, "identical lane shape is not logged again");
        for (int i = 10; i < 40; i++) sender.lanes(guidance(lane(i, new int[]{0}, null)));
        check(Dipo.logs.size() == 12, "lane-shape reports are bounded to twelve per process");
        System.out.println("LaneGuidanceTest: " + checks + " checks passed");
    }
}
