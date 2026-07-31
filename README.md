# Ledger

[![build](https://github.com/luccahp1/ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/luccahp1/ledger/actions/workflows/ci.yml)

A crash-safe key/value store on an append-only write-ahead log. Java, zero dependencies, about
370 lines.

```java
try (Store store = Store.open(Path.of("data.log"))) {
    store.put("user:1", "lucca");
    store.getString("user:1");     // Optional[lucca]
    store.delete("user:1");
    store.compact();
}
```

Writes only ever append. An update is a new record that shadows the old one; a delete is a
tombstone. Nothing is mutated in place, which is exactly what makes crash recovery tractable: a
torn record can only ever be the **last** one in the file.

## The part that actually matters

Anything can write a hashmap to disk. The difference between that and a store you can trust is
what happens when the process dies half way through a write, and the only way to know is to
actually do it.

These tests do not mock a filesystem. They write a real log, close it, corrupt the real bytes on
disk the way a crash or a bad sector would, then reopen and assert:

```
PASS  CRASH: a torn final record is discarded, committed data survives
PASS  CRASH: the store is writable again after recovering from a tear
PASS  CRASH: not even a full header is handled
PASS  CORRUPTION: a flipped bit is caught by the checksum
PASS  CORRUPTION: an absurd length field cannot cause a huge allocation
PASS  CRASH: an empty and a zero-length log both open cleanly
```

Four failure modes, each a real thing that happens to real logs:

**Torn write.** The process dies mid-append. Recovery replays until a record is truncated or
fails its checksum, then truncates the file back to the end of the last good record. Truncating
is not optional. Leaving the partial bytes there means the next append lands after garbage and
the log is unreadable forever.

**Bit rot.** A byte flips inside a record while the length fields stay valid. Nothing structural
looks wrong, so only the CRC32 can catch it. Without the checksum the store hands back silently
corrupted data, which is worse than crashing. Removing the CRC check makes exactly one test fail:

```
FAIL  CORRUPTION: a flipped bit is caught by the checksum
1 FAILED, 18 passed
```

**Hostile length field.** A corrupt record claims a key length of two billion. A naive reader
allocates that and dies with `OutOfMemoryError`. Lengths are range-checked before a single byte is
allocated.

**Stub record.** Fewer bytes on disk than a header. Handled as a tear, not an exception.

## Record layout

```
[4] CRC32 of everything after it
[4] key length
[4] value length, or -1 for a tombstone
[k] key bytes, UTF-8
[v] value bytes
```

The checksum covers the length fields too, so a corrupt length is caught by the same mechanism
that catches a corrupt payload.

## Durability is a choice, so it is an argument

```java
Store.open(path);        // fsync off
Store.open(path, true);  // fsync on every write
```

With fsync off, a **process** crash is still safe, because the OS owns the page cache and will
flush it. A **power cut** may lose recent writes. With it on, committed writes survive a power
cut, at roughly an order of magnitude cost per write.

Every real store makes this trade. Redis, Postgres and Kafka all expose a version of this knob.
It is worth making explicit rather than silently picking one and calling it durable.

## Compaction

The log grows forever, because that is what append-only means. `compact()` rewrites it with only
live records, dropping shadowed values and tombstones.

```java
if (store.compactionRatio() > 0.5) {
    store.compact();
}
```

It writes to a sibling temp file, forces it to disk, then swaps it in with an **atomic move**. The
atomicity is the whole point: at no instant does the live log contain a half-finished compaction.
A crash mid-compaction leaves the original untouched and orphans the temp file, which the next
`open` ignores. In the test suite, 500 writes to one key compact from a mostly-garbage log down to
under a tenth of its size, with the live values intact.

## What this is not

Single writer, not thread safe, and the index is in memory, so every key must fit in RAM (values
do not). There are no ranges, no transactions, no secondary indexes. It is a Bitcask-style log
store, which is a well understood design with well understood limits, not a database.

## Build and test

Requires JDK 17 or newer. No Maven or Gradle.

```bash
javac -d out $(find src test -name '*.java')
java -cp out ledger.Tests
```

Or `./build.sh` on macOS and Linux, `.\build.ps1` on Windows.

```
All 19 tests passed.
```

## License

MIT. See [LICENSE](LICENSE).
