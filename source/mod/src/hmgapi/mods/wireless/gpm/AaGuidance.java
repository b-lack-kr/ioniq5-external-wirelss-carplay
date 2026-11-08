// HMG-Seogaemo: independently implemented stock guidance policy for Android Auto.
// Adapted from HMG-Seogaemo v0.1.47, commit 7616e43f669916445f151a7aeb279b32ea09cd4b.
// https://github.com/seogaemo/hmg-seogaemo (AGPL-3.0-or-later).
// NEEV: preserve HMG-API owner/focus gates, monotonic timers and private-data-free evidence.
package hmgapi.mods.wireless.gpm;

/**
 * Android Auto's next turn in the stock Navigation.DisplayType format (cluster AR view, HUD
 * hologram). Ordinary guidance uses the KOR ILS static300+margin50 baseline,
 * shared with CarPlay;600m highway is the retained approximation, not a native road-class claim.
 * Android Auto sends no junction geometry and no exit angle for turns, so only maneuvers whose
 * shape follows from the event and its side map; the rest keep the projection icon.
 * Pure policy; display units never determine the approach window.
 */
final class AaGuidance {
    static final int TURN_WINDOW_M = 300, HIGHWAY_WINDOW_M = 600;
    /** ARRIVED 3: the flag on the destination ring, car-verified. */
    static final int ARRIVED = 3;

    static boolean highway(int ev) {
        return ev == AaTurns.ON_RAMP || ev == AaTurns.OFF_RAMP || ev == AaTurns.FORK || ev == AaTurns.MERGE;
    }

    static int window(int ev) { return highway(ev) ? HIGHWAY_WINDOW_M : TURN_WINDOW_M; }

    /** Inside the approach window: a fresh distance in metres to this maneuver, at most its window. */
    static boolean approach(int ev, int meters) {
        return meters >= 0 && meters <= window(ev);
    }

    /** The bar beside the AR view: 100 at the window's entry, 0 at the maneuver. */
    static int bar(int meters, int window) {
        return Math.max(0, Math.min(100, Math.round(meters * 100f / window)));
    }

    /** The exit's clock position for an exit angle, in stock GPM's bins (180 straight on), or -1. */
    static int roundaboutDirection(int a) {
        if (a <= 0 || a > 360) return -1;
        if (a == 180) return 0;
        if (a >= 135 && a < 180) return 45;
        if (a >= 90 && a < 135) return 90;
        if (a >= 45 && a < 90) return 135;
        if (a < 45 || a > 315) return 180;
        if (a > 270) return 225;
        if (a > 225) return 270;
        return 315;
    }

    /** {DisplayType, direction}, or null to keep the projection icon. */
    static int[] stock(int ev, int side, int angle) {
        boolean r = side == AaTurns.RIGHT, l = side == AaTurns.LEFT;
        switch (ev) {
            case AaTurns.STRAIGHT:
                // with a side it is a keep (Car App Library KEEP_LEFT/RIGHT: "more than one option
                // into the same general direction"): the projection icon, as CarPlay keeps
                return r || l ? null : new int[] {13, 0};
            case AaTurns.NAME_CHANGE:
                // a name change to one side is not a turn of known shape
                return r || l ? null : new int[] {13, 0};
            case AaTurns.SLIGHT_TURN:
                return r ? new int[] {13, 45} : l ? new int[] {13, 315} : null;
            case AaTurns.TURN:
                return r ? new int[] {13, 90} : l ? new int[] {13, 270} : null;
            case AaTurns.SHARP_TURN:
                return r ? new int[] {13, 135} : l ? new int[] {13, 225} : null;
            case AaTurns.U_TURN:
                // the Korean left loop unless the phone says right
                return new int[] {31, r ? 90 : 270};
            case AaTurns.ON_RAMP: case AaTurns.OFF_RAMP:
                // EXIT_RIGHT 17 / EXIT_LEFT 18; Android Auto's lanes carry no recommendation the
                // lane-change exits 19 / 20 could be chosen from
                return r ? new int[] {17, 90} : l ? new int[] {18, 270} : null;
            case AaTurns.ROUNDABOUT_EXIT: case AaTurns.ROUNDABOUT_ENTER_AND_EXIT:
                // 32 = counter-clockwise travel (right-hand traffic) with the exit's clock position.
                // The angle (the callback's fifth argument; stock GPM's setRoundAboutDisplayType
                // reads it so) is travelled around the roundabout from the entry: 90 the exit on
                // the right, 180 straight on, 270 left, 360 back (AOSP CarNavigationStatusManager,
                // Car App Library getRoundaboutExitAngle). Stock GPM draws right-hand roundabouts
                // only for side RIGHT; other sides and a missing angle (0 / -1) keep the icon.
                if (ev != AaTurns.ROUNDABOUT_ENTER_AND_EXIT || !r) return null;
                int d = roundaboutDirection(angle);
                return d < 0 ? null : new int[] {32, d};
            case AaTurns.DESTINATION:
                // the stock destination arrow: 2 at 90 / 270 points at the side of the road
                return r ? new int[] {2, 90} : l ? new int[] {2, 270} : new int[] {ARRIVED, 0};
            default:
                // forks and merges: the side is a lane recommendation, not a Y split on the road
                // (CarPlay drive 2026-11-07); depart, ferries, unknown: the icon
                return null;
        }
    }
}
