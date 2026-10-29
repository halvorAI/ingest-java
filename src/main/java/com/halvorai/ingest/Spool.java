package com.halvorai.ingest;

import com.halvorai.ingest.jcs.Jcs;
import com.halvorai.ingest.jcs.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Disk spool for events that could not be delivered yet. Same layout and limits as the Node.js,
 * Python and Go SDKs: JSON Lines, one signed envelope per line, files named so that lexical order
 * is age order. Batches the API will never accept go to dead/ and are never replayed.
 */
final class Spool {
  static final String PREFIX = "halvor-spool-";
  static final String SUFFIX = ".jsonl";
  static final String DEAD = "dead";
  static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;
  static final Duration DEFAULT_MAX_AGE = Duration.ofDays(7);
  private static final SecureRandom RANDOM = new SecureRandom();

  final Path dir;
  final long maxBytes;
  final Duration maxAge;

  record Eviction(int files, int events) {}

  record SpoolFile(List<Envelope> envelopes, int corrupt) {}

  Spool(Path dir, long maxBytes, Duration maxAge) {
    this.dir = dir;
    this.maxBytes = maxBytes;
    this.maxAge = maxAge;
  }

  static String fileName() {
    byte[] r = new byte[4];
    RANDOM.nextBytes(r);
    return String.format("%s%015d-%s%s", PREFIX, System.currentTimeMillis(), HexFormat.of().formatHex(r), SUFFIX);
  }

  static String serialize(List<Envelope> envelopes) {
    StringBuilder out = new StringBuilder();
    for (Envelope e : envelopes) out.append(Jcs.canonicalize(Map.of("event", e.event(), "sig", e.sig()))).append('\n');
    return out.toString();
  }

  private static void atomicWrite(Path directory, String name, String body) throws IOException {
    Path tmp = Files.createTempFile(directory, "." + name + ".", ".tmp");
    try {
      Files.writeString(tmp, body, StandardCharsets.UTF_8);
      try {
        Files.move(tmp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  static List<String> list(Path directory) {
    List<String> names = new ArrayList<>();
    if (!Files.isDirectory(directory)) return names;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, PREFIX + "*" + SUFFIX)) {
      for (Path p : stream) {
        if (Files.isRegularFile(p)) names.add(p.getFileName().toString());
      }
    } catch (IOException e) {
      return names;
    }
    Collections.sort(names);
    return names;
  }

  /** Pending file names, oldest first. */
  List<String> files() {
    return list(dir);
  }

  Eviction write(List<Envelope> envelopes) throws IOException {
    if (envelopes.isEmpty()) return new Eviction(0, 0);
    Files.createDirectories(dir);
    atomicWrite(dir, fileName(), serialize(envelopes));
    return evict(dir);
  }

  @SuppressWarnings("unchecked")
  SpoolFile read(String name) {
    String text;
    try {
      text = Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return new SpoolFile(List.of(), 0);
    }
    List<Envelope> out = new ArrayList<>();
    int corrupt = 0;
    for (String line : text.split("\n")) {
      if (line.isBlank()) continue;
      try {
        Map<String, Object> obj = Json.parseObject(line);
        if (obj.get("event") instanceof Map<?, ?> event && obj.get("sig") instanceof String sig) {
          out.add(new Envelope((Map<String, Object>) event, sig));
        } else {
          corrupt++;
        }
      } catch (IllegalArgumentException e) {
        corrupt++;
      }
    }
    return new SpoolFile(out, corrupt);
  }

  /** Keeps only these envelopes in the file, in its place in the order. */
  void rewrite(String name, List<Envelope> envelopes) throws IOException {
    if (envelopes.isEmpty()) {
      remove(name);
      return;
    }
    atomicWrite(dir, name, serialize(envelopes));
  }

  void remove(String name) {
    try {
      Files.deleteIfExists(dir.resolve(name));
    } catch (IOException ignored) {
      // a file that cannot be removed is replayed again; the API de-duplicates by event id
    }
  }

  void deadLetter(List<Envelope> envelopes) throws IOException {
    if (envelopes.isEmpty()) return;
    Path dead = dir.resolve(DEAD);
    Files.createDirectories(dead);
    atomicWrite(dead, fileName(), serialize(envelopes));
    evict(dead);
  }

  /** Deletes the oldest files until the folder is under the size limit and none is too old. */
  Eviction evict(Path directory) {
    List<String> names = list(directory);
    long[] sizes = new long[names.size()];
    long[] modified = new long[names.size()];
    long total = 0;
    for (int i = 0; i < names.size(); i++) {
      try {
        Path p = directory.resolve(names.get(i));
        sizes[i] = Files.size(p);
        modified[i] = Files.getLastModifiedTime(p).toMillis();
        total += sizes[i];
      } catch (IOException e) {
        sizes[i] = -1;
      }
    }
    int files = 0;
    int events = 0;
    long now = System.currentTimeMillis();
    for (int i = 0; i < names.size(); i++) {
      if (sizes[i] < 0) continue;
      boolean tooOld = now - modified[i] > maxAge.toMillis();
      if (!tooOld && total <= maxBytes) continue;
      Path p = directory.resolve(names.get(i));
      try {
        String text = Files.readString(p, StandardCharsets.UTF_8);
        Files.delete(p);
        files++;
        for (String line : text.split("\n")) if (!line.isBlank()) events++;
        total -= sizes[i];
      } catch (IOException ignored) {
        // skip a file we cannot read or delete
      }
    }
    return new Eviction(files, events);
  }
}
