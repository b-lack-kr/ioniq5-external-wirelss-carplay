package kr.ioniq5.usbcp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

public final class PowerTraceFileTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("power-trace-test-").toRealPath();
        File file = root.resolve("power-input.log").toFile();
        File old = root.resolve("power-input.log.1").toFile();
        PowerTraceFile sink = new PowerTraceFile(file);
        String line = "AP_POWER_INPUT state=ON polls=1";
        check(sink.append(line), "initial append");
        check(new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII).equals(line+"\n"), "exact ASCII single line");
        long size = file.length();
        check(!sink.append(line+"\nforged") && !sink.append(line+"\rforged"), "no record injection");
        check(!sink.append("wrong prefix") && !sink.append(null), "prefix and null rejected");
        check(!sink.append(line+"한글"), "fixed ASCII only");
        check(!sink.append(line+new String(new char[2050]).replace('\0','x')), "bounded record");
        check(file.length()==size, "invalid input leaves file unchanged");
        Files.write(file.toPath(), new byte[PowerTraceFile.LIMIT-1]);
        check(sink.append(line), "rotate before overflowing");
        check(old.length()==PowerTraceFile.LIMIT-1 && file.length()==size, "one backup and fresh file");
        Files.write(file.toPath(), new byte[PowerTraceFile.LIMIT+10]);
        Files.write(old.toPath(), new byte[PowerTraceFile.LIMIT+20]);
        check(sink.append(line) && file.length()==size && !old.exists(), "oversized prior files not retained");
        Files.delete(file.toPath());
        Path target = root.resolve("outside"); Files.write(target, "untouched".getBytes(StandardCharsets.US_ASCII));
        Files.createSymbolicLink(file.toPath(),target);
        check(!sink.append(line), "current symlink refused");
        Files.delete(file.toPath()); Files.createSymbolicLink(old.toPath(),target);
        check(!sink.append(line) && !file.exists(), "backup symlink refused");
        check(new String(Files.readAllBytes(target),StandardCharsets.US_ASCII).equals("untouched"), "symlink target untouched");
        Files.delete(old.toPath()); Files.createDirectory(file.toPath());
        check(!sink.append(line), "nonregular current refused");
        Files.delete(file.toPath());
        check(sink.append(line), "recovery after unavailable sink");
        check(Files.getPosixFilePermissions(file.toPath()).toString().contains("OWNER_WRITE") &&
            Files.getPosixFilePermissions(file.toPath()).size()==2, "private 0600 mode");
        for (Path p : new Path[]{file.toPath(),old.toPath(),target}) Files.deleteIfExists(p);
        Files.delete(root);
        System.out.println("PowerTraceFileTest PASS checks="+checks);
    }
}
