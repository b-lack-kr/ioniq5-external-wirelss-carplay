// SPDX-License-Identifier: AGPL-3.0-or-later
package hmgapi.mods.wireless.dipo;

/** Supplemental-location lifetime; deliberately independent of Bluetooth SPP.
 * A created object is not active until its started callback returns successfully.
 * Opaque object identity distinguishes replacement/reused CarPlay objects. */
final class CarPlaySpeedSession {
    private static final class Token {
        final Object owner;
        boolean active;
        long startAttempt;
        Token(Object owner) { this.owner=owner; }
    }
    static final class Start {
        private final Token token;
        private final long attempt;
        private Start(Token token,long attempt) { this.token=token;this.attempt=attempt; }
    }
    private Token current;

    synchronized Object created(Object owner) {
        current=owner==null?null:new Token(owner);
        return current;
    }
    synchronized Start starting(Object owner) {
        if(current==null||owner==null||current.owner!=owner)return null;
        current.active=false;
        return new Start(current,++current.startAttempt);
    }
    synchronized void started(Start start,boolean success,boolean wireless) {
        if(start==null||current!=start.token||current.startAttempt!=start.attempt)return;
        current.active=success&&wireless;
    }
    synchronized void finalized(Object owner) {
        if(current!=null&&current.owner==owner)current=null;
    }
    synchronized Object identity(Object owner) {
        return current!=null&&owner!=null&&current.owner==owner?current:null;
    }
    synchronized boolean active(Object owner) {
        return current!=null&&owner!=null&&current.owner==owner&&current.active;
    }
}
