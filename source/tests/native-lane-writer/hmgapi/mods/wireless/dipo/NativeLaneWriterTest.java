package hmgapi.mods.wireless.dipo;

import android.content.Context;
import hmgapi.hook.Points;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class NativeLaneWriterTest {
    static int checks;
    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static class Target {
        byte[] received;
        int calls;
        boolean fail, linkage, mutate;
        public void write(byte[] frame) {
            calls++;
            if (fail) throw new IllegalStateException("receiver unavailable");
            if (linkage) throw new NoClassDefFoundError("missing optional dependency");
            received = frame;
            if (mutate) frame[19] = 99;
        }
        public static void stat(byte[] frame) {}
        public int result(byte[] frame) { return 0; }
        public void boxed(Byte[] frame) {}
        public void object(Object frame) {}
        public void extra(byte[] frame, int flags) {}
        public void none() {}
    }
    static byte[] frame(int count) {
        byte[] f = new byte[81]; f[0] = (byte)0xf1; f[1] = (byte)count;
        if (count > 0) { f[3] = 2; f[19] = 1; }
        return f;
    }
    public static void main(String[] args) throws Exception {
        Target target = new Target();
        Method good = Target.class.getMethod("write", byte[].class);
        Points.receiver = target; Points.selected = good;
        NativeLaneWriter writer = NativeLaneWriter.create(new Context());
        check(Points.lookups == 0 && Points.constructors == 0, "construction performs no framework resolution");
        for (int count : new int[]{0, 1, 8}) {
            byte[] f = frame(count);
            check(writer.send(f) && Arrays.equals(f, target.received), "valid F1/clear exact invocation " + count);
            check(f != target.received, "framework receives a private buffer");
        }
        check(Points.lookups == 1 && Points.constructors == 1, "successful receiver cached");
        int calls = target.calls, lookups = Points.lookups;
        byte[] badHeader = frame(1); badHeader[0] = 0;
        byte[] unsignedCount = frame(1); unsignedCount[1] = (byte)255;
        for (byte[] bad : new byte[][]{null, new byte[0], new byte[80], new byte[82], badHeader, frame(9), unsignedCount})
            check(!writer.send(bad), "malformed frame rejected");
        check(calls == target.calls && lookups == Points.lookups, "invalid frames cause no IPC or lookup");
        byte[] f = frame(1); target.mutate = true;
        check(writer.send(f) && f[19] == 1 && target.received[19] == 99, "receiver mutation isolated");
        target.mutate = false; target.fail = true;
        check(!writer.send(f), "invocation exception observable");
        target.fail = false; lookups = Points.lookups;
        check(writer.send(f) && Points.lookups == lookups + 1, "same input retries and re-resolves after failure");
        target.linkage = true;
        check(!writer.send(f), "Dalvik linkage error contained");
        target.linkage = false;
        check(writer.send(f), "same input retries after linkage failure");

        NativeLaneWriter deferred = NativeLaneWriter.create(new Context());
        Points.selected = null;
        check(!deferred.send(f), "initial missing map reported");
        Points.selected = good;
        check(deferred.send(f), "initial map failure retried without constructing a new adapter");
        for (Object wrong : new Object[]{null, new Object()}) {
            NativeLaneWriter mismatch = NativeLaneWriter.create(new Context());
            Points.receiver = wrong;
            check(!mismatch.send(f), "receiver mismatch rejected");
            Points.receiver = target;
            check(mismatch.send(f), "receiver recovery retried");
        }
        for (Method method : Target.class.getMethods()) {
            if (!Arrays.asList("stat", "result", "boxed", "object", "extra", "none").contains(method.getName())) continue;
            NativeLaneWriter invalid = NativeLaneWriter.create(new Context());
            Points.selected = method;
            int constructions = Points.constructors;
            check(!invalid.send(f) && Points.constructors == constructions, "bad signature rejected before receiver construction");
            Points.selected = good;
            check(invalid.send(f), "bad signature not permanently cached");
        }
        System.out.println("NativeLaneWriterTest PASS checks=" + checks);
    }
}
