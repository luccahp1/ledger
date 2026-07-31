package ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite, with its own runner. No JUnit, so a JDK is the entire toolchain.
 *
 * <p>The crash tests do not mock anything. They write a real log, close it, corrupt the real bytes
 * on disk the way a real crash or a real bad sector would, then reopen and assert. A recovery test
 * against a fake filesystem proves considerably less.
 */
public final class Tests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();
    private static Path tempDir;

    public static void main(String[] args) throws Exception {
        tempDir = Files.createTempDirectory("ledger-tests");

        // ---- basics ----
        check("put and get round trip", () -> {
            try (Store s = Store.open(log("basic"))) {
                s.put("a", "one");
                s.put("b", "two");
                assertEquals("one", s.getString("a").orElse(null), "a");
                assertEquals("two", s.getString("b").orElse(null), "b");
                assertTrue(s.get("missing").isEmpty(), "missing key should be empty");
            }
        });

        check("update shadows the older value", () -> {
            try (Store s = Store.open(log("update"))) {
                s.put("k", "first");
                s.put("k", "second");
                s.put("k", "third");
                assertEquals("third", s.getString("k").orElse(null), "latest write wins");
                assertEquals(1, s.size(), "an update must not add a key");
            }
        });

        check("delete removes the key and reports whether it existed", () -> {
            try (Store s = Store.open(log("delete"))) {
                s.put("k", "v");
                assertTrue(s.delete("k"), "deleting a present key returns true");
                assertTrue(s.get("k").isEmpty(), "key should be gone");
                assertFalse(s.delete("k"), "deleting an absent key returns false");
                assertFalse(s.containsKey("k"), "containsKey should agree");
            }
        });

        check("binary values survive intact", () -> {
            byte[] raw = new byte[256];
            for (int i = 0; i < 256; i++) raw[i] = (byte) i;
            try (Store s = Store.open(log("binary"))) {
                s.put("blob", raw);
                assertArrayEquals(raw, s.get("blob").orElseThrow(), "all 256 byte values");
            }
        });

        check("unicode keys and values survive intact", () -> {
            try (Store s = Store.open(log("unicode"))) {
                s.put("chave-ação", "こんにちは 🐕");
                assertEquals("こんにちは 🐕", s.getString("chave-ação").orElse(null), "utf-8");
            }
        });

        check("empty value is distinct from a missing key", () -> {
            try (Store s = Store.open(log("empty"))) {
                s.put("k", new byte[0]);
                assertTrue(s.get("k").isPresent(), "empty value is still a value");
                assertEquals(0, s.get("k").orElseThrow().length, "and it is empty");
            }
        });

        // ---- durability across a clean close ----
        check("data survives close and reopen", () -> {
            Path p = log("reopen");
            try (Store s = Store.open(p)) {
                s.put("a", "one");
                s.put("b", "two");
                s.delete("a");
                s.put("c", "three");
            }
            try (Store s = Store.open(p)) {
                assertTrue(s.get("a").isEmpty(), "the delete must survive the reopen");
                assertEquals("two", s.getString("b").orElse(null), "b");
                assertEquals("three", s.getString("c").orElse(null), "c");
                assertEquals(2, s.size(), "two live keys");
            }
        });

        check("many keys survive a reopen", () -> {
            Path p = log("many");
            try (Store s = Store.open(p)) {
                for (int i = 0; i < 1_000; i++) s.put("key" + i, "value" + i);
            }
            try (Store s = Store.open(p)) {
                assertEquals(1_000, s.size(), "all keys recovered");
                assertEquals("value500", s.getString("key500").orElse(null), "spot check");
                assertEquals("value999", s.getString("key999").orElse(null), "last key");
            }
        });

        // ================= crash recovery, the reason this exists =================

        check("CRASH: a torn final record is discarded, committed data survives", () -> {
            Path p = log("torn");
            long goodSize;
            try (Store s = Store.open(p)) {
                s.put("a", "one");
                s.put("b", "two");
                goodSize = s.fileSizeBytes();
                s.put("c", "three");          // this is the write we will tear
            }
            long fullSize = Files.size(p);
            // Simulate dying part way through appending "c": keep only half of that record.
            truncate(p, goodSize + (fullSize - goodSize) / 2);

            try (Store s = Store.open(p)) {
                assertEquals("one", s.getString("a").orElse(null), "a must survive");
                assertEquals("two", s.getString("b").orElse(null), "b must survive");
                assertTrue(s.get("c").isEmpty(), "the torn write must not be visible");
                assertEquals(goodSize, s.fileSizeBytes(),
                        "the log must be cut back to the last good record");
            }
        });

        check("CRASH: the store is writable again after recovering from a tear", () -> {
            Path p = log("torn-then-write");
            try (Store s = Store.open(p)) {
                s.put("a", "one");
                s.put("b", "two");
            }
            truncate(p, Files.size(p) - 3);       // tear the tail of "b"

            try (Store s = Store.open(p)) {
                assertEquals("one", s.getString("a").orElse(null), "a survives");
                assertTrue(s.get("b").isEmpty(), "torn b is gone");
                s.put("c", "three");              // appending after a repair must work
            }
            try (Store s = Store.open(p)) {
                assertEquals("one", s.getString("a").orElse(null), "a still there");
                assertEquals("three", s.getString("c").orElse(null), "c persisted cleanly");
            }
        });

        check("CRASH: not even a full header is handled", () -> {
            Path p = log("stub");
            try (Store s = Store.open(p)) {
                s.put("a", "one");
            }
            long good = Files.size(p);
            appendGarbage(p, new byte[]{1, 2, 3});   // 3 bytes, shorter than a 12 byte header

            try (Store s = Store.open(p)) {
                assertEquals("one", s.getString("a").orElse(null), "a survives");
                assertEquals(good, s.fileSizeBytes(), "the stub must be truncated away");
            }
        });

        check("CORRUPTION: a flipped bit is caught by the checksum", () -> {
            Path p = log("bitrot");
            long afterFirst;
            try (Store s = Store.open(p)) {
                s.put("a", "one");
                afterFirst = s.fileSizeBytes();
                s.put("b", "two");
            }
            // Flip a bit inside b's payload. The length fields stay valid, so only the CRC
            // can catch this. Without the checksum the store would serve silent garbage.
            flipBitAt(p, afterFirst + 14);

            try (Store s = Store.open(p)) {
                assertEquals("one", s.getString("a").orElse(null), "a is before the damage");
                assertTrue(s.get("b").isEmpty(), "the corrupt record must be rejected");
                assertEquals(afterFirst, s.fileSizeBytes(), "log cut back to the last good record");
            }
        });

        check("CORRUPTION: an absurd length field cannot cause a huge allocation", () -> {
            Path p = log("badlen");
            try (Store s = Store.open(p)) {
                s.put("a", "one");
            }
            long good = Files.size(p);
            // crc, then a key length of 2 billion. A naive reader allocates that and dies.
            ByteBuffer evil = ByteBuffer.allocate(12);
            evil.putInt(0);
            evil.putInt(Integer.MAX_VALUE - 1);
            evil.putInt(5);
            appendGarbage(p, evil.array());

            try (Store s = Store.open(p)) {          // must not throw OutOfMemoryError
                assertEquals("one", s.getString("a").orElse(null), "a survives");
                assertEquals(good, s.fileSizeBytes(), "the bogus record is truncated");
            }
        });

        check("CRASH: an empty and a zero-length log both open cleanly", () -> {
            try (Store s = Store.open(log("brandnew"))) {
                assertEquals(0, s.size(), "a new store is empty");
                s.put("a", "one");
            }
            Path zero = log("zerobyte");
            Files.write(zero, new byte[0]);
            try (Store s = Store.open(zero)) {
                assertEquals(0, s.size(), "a zero byte log is not an error");
            }
        });

        // ---- compaction ----
        check("compaction reclaims space and preserves live data", () -> {
            Path p = log("compact");
            try (Store s = Store.open(p)) {
                for (int i = 0; i < 500; i++) s.put("hot", "value-" + i);   // 499 dead versions
                s.put("cold", "kept");
                long before = s.fileSizeBytes();
                assertTrue(s.compactionRatio() > 0.9, "log should be mostly garbage by now");

                s.compact();

                long after = s.fileSizeBytes();
                assertTrue(after < before / 10,
                        "compaction should shrink the log a lot (" + before + " -> " + after + ")");
                assertEquals("value-499", s.getString("hot").orElse(null), "latest value kept");
                assertEquals("kept", s.getString("cold").orElse(null), "other key kept");
                assertEquals(2, s.size(), "two live keys");
                assertTrue(s.compactionRatio() < 0.01, "nothing dead right after a compaction");
            }
        });

        check("compaction survives a reopen and drops tombstones", () -> {
            Path p = log("compact-reopen");
            try (Store s = Store.open(p)) {
                s.put("a", "one");
                s.put("b", "two");
                s.put("c", "three");
                s.delete("b");
                s.compact();
            }
            try (Store s = Store.open(p)) {
                assertEquals(2, s.size(), "b should be gone for good");
                assertEquals("one", s.getString("a").orElse(null), "a");
                assertEquals("three", s.getString("c").orElse(null), "c");
                assertTrue(s.get("b").isEmpty(), "deleted key stays deleted");
            }
        });

        check("the store is still writable after compaction", () -> {
            Path p = log("compact-write");
            try (Store s = Store.open(p)) {
                s.put("a", "one");
                s.compact();
                s.put("b", "two");
            }
            try (Store s = Store.open(p)) {
                assertEquals("one", s.getString("a").orElse(null), "a");
                assertEquals("two", s.getString("b").orElse(null), "b appended post compaction");
            }
        });

        // ---- durability mode ----
        check("fsync mode round trips", () -> {
            Path p = log("fsync");
            try (Store s = Store.open(p, true)) {
                s.put("a", "one");
            }
            try (Store s = Store.open(p, true)) {
                assertEquals("one", s.getString("a").orElse(null), "durable write survived");
            }
        });

        // ---- argument checking ----
        check("null arguments are rejected", () -> {
            try (Store s = Store.open(log("nulls"))) {
                assertThrows(NullPointerException.class, () -> {
                    try { s.put(null, "v"); } catch (IOException e) { throw new RuntimeException(e); }
                }, "null key");
                assertThrows(NullPointerException.class, () -> {
                    try { s.put("k", (byte[]) null); } catch (IOException e) { throw new RuntimeException(e); }
                }, "null value");
            }
        });

        report();
    }

    // ---------- corruption helpers: real bytes, real damage ----------

    private static void truncate(Path p, long size) throws IOException {
        try (FileChannel c = FileChannel.open(p, StandardOpenOption.WRITE)) {
            c.truncate(size);
        }
    }

    private static void appendGarbage(Path p, byte[] bytes) throws IOException {
        Files.write(p, bytes, StandardOpenOption.APPEND);
    }

    private static void flipBitAt(Path p, long offset) throws IOException {
        try (FileChannel c = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            c.read(one, offset);
            one.flip();
            byte flipped = (byte) (one.get(0) ^ 0x01);
            c.write(ByteBuffer.wrap(new byte[]{flipped}), offset);
        }
    }

    private static Path log(String name) {
        return tempDir.resolve(name + ".log");
    }

    // ---------- runner ----------

    private interface Case {
        void run() throws Exception;
    }

    private static void check(String name, Case body) {
        try {
            body.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            String detail = t.getMessage() == null ? t.toString() : t.getMessage();
            failures.add(name + "\n          " + detail);
            System.out.println("  FAIL  " + name + "\n          " + detail);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        if (expected.length != actual.length) {
            throw new AssertionError(message + " (length " + expected.length + " vs " + actual.length + ")");
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new AssertionError(message + " (differs at index " + i + ")");
            }
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, Runnable body, String message) {
        try {
            body.run();
        } catch (Throwable t) {
            Throwable actual = (t instanceof RuntimeException && t.getCause() != null) ? t.getCause() : t;
            if (expected.isInstance(actual) || expected.isInstance(t)) return;
            throw new AssertionError(message + " (threw " + t.getClass().getSimpleName() + ")");
        }
        throw new AssertionError(message + " (nothing was thrown)");
    }

    private static void report() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("All " + passed + " tests passed.");
            return;
        }
        System.out.println(failures.size() + " FAILED, " + passed + " passed:");
        for (String f : failures) System.out.println("  - " + f);
        System.exit(1);
    }
}
