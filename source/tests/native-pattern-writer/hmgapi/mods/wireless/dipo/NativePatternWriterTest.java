package hmgapi.mods.wireless.dipo;

import android.content.Context;
import hmgapi.hook.Points;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class NativePatternWriterTest {
    private static int checks;
    private static final int[] ACTIVE = { 19, 20, 65535, 0, 0 };
    private static final int[] CLEAR = { 65535, 65535, 65535, 0, 0 };

    public static class Receiver {
        int calls;
        int[] last;
        Throwable failure;
        public void updateCluDataNew_14(int guide, int arrow0, int arrow1, int lanes, int color) throws Throwable {
            calls++;
            if (failure != null) throw failure;
            last = new int[] { guide, arrow0, arrow1, lanes, color };
        }
        public static void staticFive(int a, int b, int c, int d, int e) {}
        public boolean returnsBoolean(int a, int b, int c, int d, int e) { return true; }
        public void four(int a, int b, int c, int d) {}
        public void six(int a, int b, int c, int d, int e, int f) {}
        public void boxed(int a, int b, int c, int d, Integer e) {}
        public void floating(int a, int b, int c, int d, float e) {}
        public void varargs(int... values) {}
        private void inaccessible(int a, int b, int c, int d, int e) {}
    }

    public static final class ChildReceiver extends Receiver {}

    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    private static Method exactMethod() throws Exception {
        return Receiver.class.getMethod("updateCluDataNew_14", int.class, int.class, int.class, int.class, int.class);
    }

    private static Receiver prepare() throws Exception {
        Points.reset();
        Dipo.reset();
        Receiver receiver = new Receiver();
        Points.selected = exactMethod();
        Points.receiver = receiver;
        return receiver;
    }

    private static long failures() {
        long count = 0;
        for (String event : Dipo.events) if (event.equals("USB_CP_TBT_JUNCTION event=write_failed")) count++;
        return count;
    }

    private static void exactInstanceAndTuples() throws Exception {
        Receiver receiver = prepare();
        Context context = new Context();
        CpTurns.PatternSink writer = NativePatternWriter.create(context);
        check(writer != null, "exact instance five-int void method available");
        check(Points.methodCalls == 1 && Points.receiverCalls == 1
                && Points.constructorContext == context, "exact mapped instance constructed with original context");
        check(Dipo.events.equals(Arrays.asList("USB_CP_TBT_JUNCTION event=ready")), "one ready record");
        int[] input = ACTIVE.clone();
        check(writer.send(input) && Arrays.equals(receiver.last, ACTIVE), "arguments delivered in native tuple order");
        check(Arrays.equals(input, ACTIVE), "input tuple unchanged");
        check(writer.send(CLEAR) && Arrays.equals(receiver.last, CLEAR), "native initial/clear tuple delivered intact");
        check(writer.send(new int[] { 0, 0, 0, 0, 0 }), "zero lower bounds accepted");
        check(writer.send(new int[] { 65535, 65535, 65535, 255, 15 }), "wire-width upper bounds accepted");
        check(receiver.calls == 4, "one reflective invocation per successful send");
        prepare();
        ChildReceiver child = new ChildReceiver();
        Points.receiver = child;
        writer = NativePatternWriter.create(context);
        check(writer != null && writer.send(ACTIVE) && child.calls == 1, "declaring-class-compatible subclass receiver accepted");
    }

    private static void signatureAndAvailability() throws Exception {
        for (String name : new String[] { "staticFive", "returnsBoolean", "four", "six", "boxed", "floating", "varargs" }) {
            prepare();
            for (Method method : Receiver.class.getDeclaredMethods())
                if (method.getName().equals(name)) Points.selected = method;
            check(NativePatternWriter.create(new Context()) == null, "reject mismatched signature: " + name);
            check(Points.receiverCalls == 0, "invalid signature does not construct receiver: " + name);
            check(Dipo.events.equals(Arrays.asList("USB_CP_TBT_JUNCTION event=unavailable")), "unavailable recorded: " + name);
        }
        prepare();
        Points.selected = null;
        check(NativePatternWriter.create(new Context()) == null && Points.receiverCalls == 0, "missing mapped method unavailable");
        for (Object invalid : new Object[] { null, new Object(), "wrong receiver" }) {
            prepare();
            Points.receiver = invalid;
            check(NativePatternWriter.create(new Context()) == null, "wrong/null instance unavailable");
        }
        for (Throwable failure : new Throwable[] { new ReflectiveOperationException("missing"), new LinkageError("missing class"),
                new SecurityException("blocked lookup") }) {
            prepare();
            Points.methodFailure = failure;
            check(NativePatternWriter.create(new Context()) == null && Points.receiverCalls == 0,
                    "lookup failure returns null without construction");
            prepare();
            Points.receiverFailure = failure;
            check(NativePatternWriter.create(new Context()) == null, "constructor failure returns null");
        }
        Receiver receiver = prepare();
        Points.selected = Receiver.class.getDeclaredMethod("inaccessible", int.class, int.class, int.class, int.class, int.class);
        CpTurns.PatternSink writer = NativePatternWriter.create(new Context());
        check(writer != null && !writer.send(ACTIVE) && receiver.calls == 0, "inaccessible reflection write fails closed");
    }

    private static void malformedValues() throws Exception {
        Receiver receiver = prepare();
        CpTurns.PatternSink writer = NativePatternWriter.create(new Context());
        for (int[] values : new int[][] { null, new int[0], new int[4], new int[6] })
            check(!writer.send(values), "exact tuple arity required");
        for (int field = 0; field < 5; field++) {
            int max = field < 3 ? 65535 : field == 3 ? 255 : 15;
            for (int invalid : new int[] { Integer.MIN_VALUE, -1, max + 1, Integer.MAX_VALUE }) {
                int[] tuple = ACTIVE.clone();
                tuple[field] = invalid;
                check(!writer.send(tuple), "field bound rejected at " + field + ":" + invalid);
            }
        }
        check(receiver.calls == 0 && failures() == 0, "malformed tuples never reach reflection or emit write failure");
    }

    private static void retryAndLogging() throws Exception {
        Receiver receiver = prepare();
        CpTurns.PatternSink writer = NativePatternWriter.create(new Context());
        receiver.failure = new IllegalStateException("receiver unavailable");
        check(!writer.send(ACTIVE) && !writer.send(ACTIVE), "receiver failure returns false on each attempt");
        check(receiver.calls == 2 && failures() == 1, "failed writes retry without repeated failure logs");
        receiver.failure = null;
        check(writer.send(ACTIVE) && receiver.calls == 3, "same tuple succeeds after receiver recovers");
        receiver.failure = new AssertionError("receiver failed again");
        check(!writer.send(CLEAR) && receiver.calls == 4 && failures() == 2,
                "success resets error suppression and failed clear remains retryable");
        receiver.failure = null;
        check(writer.send(CLEAR) && Arrays.equals(receiver.last, CLEAR), "clear retries without cached-success assumption");

        receiver = prepare();
        Dipo.logFails = true;
        writer = NativePatternWriter.create(new Context());
        check(writer != null && writer.send(ACTIVE), "broken ready recorder does not disable valid writer");
        receiver.failure = new IllegalStateException("receiver unavailable");
        check(!writer.send(ACTIVE), "broken failure recorder cannot escape send false contract");
        receiver.failure = null;
        check(writer.send(ACTIVE), "writer still retries after simultaneous receiver and recorder failure");
        prepare();
        Dipo.logFails = true;
        Points.methodFailure = new LinkageError("standalone unavailable");
        check(NativePatternWriter.create(new Context()) == null, "unavailable remains optional even with failed logging");
    }

    public static void main(String[] args) throws Exception {
        exactInstanceAndTuples();
        signatureAndAvailability();
        malformedValues();
        retryAndLogging();
        System.out.println("NativePatternWriterTest PASS checks=" + checks);
    }
}
