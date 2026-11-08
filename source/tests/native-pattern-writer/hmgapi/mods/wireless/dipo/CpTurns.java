package hmgapi.mods.wireless.dipo;

/** Sink contract only: no Android guidance engine or HMG-API v2 dependency. */
final class CpTurns {
    interface PatternSink { boolean send(int[] values); }
}
