package ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * A crash-safe key/value store built on an append-only write-ahead log.
 *
 * <pre>{@code
 * try (Store store = Store.open(Path.of("data.log"))) {
 *     store.put("user:1", "lucca");
 *     store.getString("user:1");        // Optional[lucca]
 *     store.delete("user:1");
 * }
 * }</pre>
 *
 * <p>Writes only ever append. An update is a new record that shadows the old one, and a delete is
 * a tombstone record. Nothing is ever mutated in place, which is what makes crash recovery
 * tractable: a torn record can only ever be the last one in the file.
 *
 * <h2>Record layout</h2>
 * <pre>
 *   [4] CRC32 of everything after it
 *   [4] key length
 *   [4] value length, or -1 for a tombstone
 *   [k] key bytes, UTF-8
 *   [v] value bytes
 * </pre>
 *
 * <h2>Recovery</h2>
 * On {@link #open}, the log is replayed from byte zero to rebuild the in-memory index. Replay
 * stops at the first record that is either truncated or fails its checksum, and the file is
 * truncated back to the end of the last good record. That is the correct behaviour rather than a
 * shortcut: a partial record means the process died mid-append, so everything committed before it
 * is intact and there is nothing valid after it. Leaving the partial bytes in place would corrupt
 * the next append.
 *
 * <h2>Durability</h2>
 * {@link #put} and {@link #delete} write through to the OS. Whether they also force the disk cache
 * is controlled by {@code fsync} on {@link #open}. With fsync off, a process crash is still safe
 * because the OS owns the page cache; a power cut may lose recent writes. With it on, committed
 * writes survive a power cut, at roughly an order of magnitude cost per write. Every real store
 * makes this same trade, and it is worth making explicit rather than silently picking one.
 *
 * <p>Not thread safe. Wrap it or give each thread its own file.
 */
public final class Store implements AutoCloseable {

    private static final int HEADER_BYTES = 12;     // crc + keyLen + valLen
    private static final int TOMBSTONE = -1;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;

    private final Path path;
    private final boolean fsync;
    private final Map<String, Long> index = new HashMap<>();
    private FileChannel channel;
    private long writeOffset;

    /** Bytes of log occupied by records that are now dead. Drives {@link #compactionRatio}. */
    private long deadBytes;

    private Store(Path path, boolean fsync) {
        this.path = path;
        this.fsync = fsync;
    }

    /** Opens (or creates) a store, replaying and repairing the log. fsync off by default. */
    public static Store open(Path path) throws IOException {
        return open(path, false);
    }

    public static Store open(Path path, boolean fsync) throws IOException {
        Store store = new Store(path, fsync);
        store.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        store.recover();
        return store;
    }

    // ---------------- public API ----------------

    public void put(String key, byte[] value) throws IOException {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");
        append(key, value);
    }

    public void put(String key, String value) throws IOException {
        put(key, value.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<byte[]> get(String key) throws IOException {
        Long offset = index.get(key);
        if (offset == null) {
            return Optional.empty();
        }
        return Optional.of(readValueAt(offset));
    }

    public Optional<String> getString(String key) throws IOException {
        return get(key).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    /** Removes a key. Returns true if it was present. Writes a tombstone either way. */
    public boolean delete(String key) throws IOException {
        boolean existed = index.containsKey(key);
        append(key, null);
        return existed;
    }

    public boolean containsKey(String key) {
        return index.containsKey(key);
    }

    public int size() {
        return index.size();
    }

    public Set<String> keys() {
        return Set.copyOf(index.keySet());
    }

    public long fileSizeBytes() throws IOException {
        return channel.size();
    }

    /** Fraction of the log that is dead weight, from 0.0 to 1.0. Compact when this gets high. */
    public double compactionRatio() throws IOException {
        long total = channel.size();
        return total == 0 ? 0.0 : (double) deadBytes / total;
    }

    /**
     * Rewrites the log with only live records, dropping shadowed values and tombstones.
     *
     * <p>Writes to a sibling temp file, forces it to disk, then swaps it in with an atomic move.
     * The swap is the important part: at no point does the real log contain a half-written
     * compaction. A crash during compaction leaves the original log untouched and orphans the
     * temp file, which the next {@link #open} ignores.
     */
    public void compact() throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".compact");
        Map<String, Long> rebuilt = new HashMap<>();

        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long offset = 0;
            for (Map.Entry<String, Long> entry : index.entrySet()) {
                byte[] value = readValueAt(entry.getValue());
                ByteBuffer record = encode(entry.getKey(), value);
                int length = record.remaining();
                writeFully(out, record);
                rebuilt.put(entry.getKey(), offset);
                offset += length;
            }
            out.force(true);
        }

        channel.close();
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        index.clear();
        index.putAll(rebuilt);
        writeOffset = channel.size();
        deadBytes = 0;
    }

    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.force(true);
            channel.close();
        }
    }

    // ---------------- internals ----------------

    private void append(String key, byte[] value) throws IOException {
        ByteBuffer record = encode(key, value);
        int length = record.remaining();

        channel.position(writeOffset);
        writeFully(channel, record);
        if (fsync) {
            channel.force(false);
        }

        Long previous = (value == null) ? index.remove(key) : index.put(key, writeOffset);
        if (previous != null) {
            deadBytes += recordLengthAt(previous);
        }
        if (value == null) {
            deadBytes += length;   // the tombstone itself is dead weight after compaction
        }
        writeOffset += length;
    }

    private static ByteBuffer encode(String key, byte[] value) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int valueLength = (value == null) ? TOMBSTONE : value.length;
        int payload = 8 + keyBytes.length + Math.max(0, valueLength);

        ByteBuffer buffer = ByteBuffer.allocate(4 + payload);
        buffer.position(4);                       // leave room for the checksum
        buffer.putInt(keyBytes.length);
        buffer.putInt(valueLength);
        buffer.put(keyBytes);
        if (value != null) {
            buffer.put(value);
        }

        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 4, payload);
        buffer.putInt(0, (int) crc.getValue());

        buffer.rewind();
        return buffer;
    }

    private static void writeFully(FileChannel out, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            out.write(buffer);
        }
    }

    private ByteBuffer readFully(long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                return null;                      // hit end of file: the record is truncated
            }
            position += read;
        }
        return buffer.flip();
    }

    private int recordLengthAt(long offset) throws IOException {
        ByteBuffer header = readFully(offset, HEADER_BYTES);
        if (header == null) {
            throw new CorruptLogException("cannot read a record header at offset " + offset);
        }
        header.getInt();                          // skip crc
        int keyLength = header.getInt();
        int valueLength = header.getInt();
        return HEADER_BYTES + keyLength + Math.max(0, valueLength);
    }

    private byte[] readValueAt(long offset) throws IOException {
        ByteBuffer header = readFully(offset, HEADER_BYTES);
        if (header == null) {
            throw new CorruptLogException("cannot read a record header at offset " + offset);
        }
        header.getInt();
        int keyLength = header.getInt();
        int valueLength = header.getInt();
        if (valueLength == TOMBSTONE) {
            throw new CorruptLogException("index points at a tombstone at offset " + offset);
        }
        ByteBuffer body = readFully(offset + HEADER_BYTES + keyLength, valueLength);
        if (body == null) {
            throw new CorruptLogException("truncated value at offset " + offset);
        }
        byte[] value = new byte[valueLength];
        body.get(value);
        return value;
    }

    /**
     * Replays the log, rebuilding the index and truncating any trailing garbage.
     *
     * <p>Every exit from the loop other than a clean end-of-file is treated the same way: stop,
     * and cut the file back to the last byte that was known good.
     */
    private void recover() throws IOException {
        long offset = 0;
        long fileSize = channel.size();
        index.clear();
        deadBytes = 0;

        while (offset < fileSize) {
            ByteBuffer header = readFully(offset, HEADER_BYTES);
            if (header == null) {
                break;                            // torn: not even a full header
            }
            int expectedCrc = header.getInt();
            int keyLength = header.getInt();
            int valueLength = header.getInt();

            // Guard before allocating: a corrupt length field must not become a huge allocation.
            if (keyLength < 0 || keyLength > MAX_RECORD_BYTES
                    || valueLength < TOMBSTONE || valueLength > MAX_RECORD_BYTES) {
                break;
            }
            int payload = 8 + keyLength + Math.max(0, valueLength);
            ByteBuffer body = readFully(offset + 4, payload);
            if (body == null) {
                break;                            // torn mid-record
            }

            CRC32 crc = new CRC32();
            crc.update(body.array(), 0, payload);
            if ((int) crc.getValue() != expectedCrc) {
                break;                            // bit rot, or a partially flushed record
            }

            body.position(8);
            byte[] keyBytes = new byte[keyLength];
            body.get(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            int recordLength = 4 + payload;
            Long previous = (valueLength == TOMBSTONE) ? index.remove(key) : index.put(key, offset);
            if (previous != null) {
                deadBytes += recordLengthAt(previous);
            }
            if (valueLength == TOMBSTONE) {
                deadBytes += recordLength;
            }
            offset += recordLength;
        }

        if (offset < fileSize) {
            // Trailing bytes are unusable. Cut them, or the next append lands after garbage and
            // the log is unreadable forever.
            channel.truncate(offset);
        }
        writeOffset = offset;
    }

    /** How many bytes recovery discarded when this store was last opened, for tests and logs. */
    public long writeOffset() {
        return writeOffset;
    }

    static UncheckedIOException wrap(IOException e) {
        return new UncheckedIOException(e);
    }
}
