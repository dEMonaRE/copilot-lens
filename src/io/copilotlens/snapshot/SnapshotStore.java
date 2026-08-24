package io.copilotlens.snapshot;

import io.copilotlens.config.CopilotLensConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read/write {@link Snapshot} files under
 * {@code ~/.copilot-lens/snapshots/YYYY-MM-DD.json}.
 *
 * Each snapshot is one small JSON file per day. Saves are atomic:
 * write to {@code YYYY-MM-DD.json.download} first then rename, so
 * partial writes never replace an existing snapshot.
 */
public class SnapshotStore {

    private static final Pattern SNAPSHOT_FILE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\.json$");

    private final Path dir;

    public SnapshotStore() {
        this.dir = CopilotLensConfig.load().getStateDir().resolve("snapshots");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // directory may already exist
        }
    }

    public Path dir() {
        return dir;
    }

    /** Save (or overwrite) the snapshot for the given date. */
    public void save(Snapshot s) throws IOException {
        Path target = dir.resolve(s.date() + ".json");
        Path tmp = dir.resolve(s.date() + ".json.download");
        Files.writeString(tmp, serialize(s));
        try {
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Fallback for filesystems without atomic move (rare on local FS)
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Load the snapshot for a specific date, or {@code null} if none. */
    public Snapshot loadForDate(LocalDate date) {
        Path file = dir.resolve(date.toString() + ".json");
        if (!Files.exists(file)) return null;
        try {
            return deserialize(Files.readString(file));
        } catch (IOException e) {
            return null;
        }
    }

    /** All snapshots on disk, sorted by date ascending. */
    public List<Snapshot> loadAll() {
        List<Snapshot> all = new ArrayList<>();
        if (!Files.isDirectory(dir)) return all;
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                Matcher m = SNAPSHOT_FILE.matcher(name);
                if (!m.matches()) return;
                try {
                    Snapshot s = deserialize(Files.readString(p));
                    if (s != null) all.add(s);
                } catch (IOException ignored) {
                    // skip unreadable
                }
            });
        } catch (IOException ignored) {
            // dir may not exist
        }
        all.sort(Comparator.comparing(Snapshot::date));
        return all;
    }

    /** Snapshots within {@code [from, to]} inclusive. */
    public List<Snapshot> loadRange(LocalDate from, LocalDate to) {
        return loadAll().stream()
                .filter(s -> {
                    LocalDate d = s.localDate();
                    return !d.isBefore(from) && !d.isAfter(to);
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /** Most recent snapshot, or {@code null} if none exist. */
    public Snapshot latest() {
        List<Snapshot> all = loadAll();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    // ---- serialization (hand-rolled, matches the project's existing style) ----

    private String serialize(Snapshot s) {
        return "{\n"
             + "  \"date\": \"" + s.date() + "\",\n"
             + "  \"createdAt\": \"" + s.createdAt() + "\",\n"
             + "  \"ide\": \"" + s.ide() + "\",\n"
             + "  \"requestCount\": " + s.requestCount() + ",\n"
             + "  \"totalInputTokens\": " + s.totalInputTokens() + ",\n"
             + "  \"totalOutputTokens\": " + s.totalOutputTokens() + "\n"
             + "}\n";
    }

    private Snapshot deserialize(String content) {
        String date = extract(content, "date");
        String createdAt = extract(content, "createdAt");
        String ide = extract(content, "ide");
        if (date == null || ide == null) return null;
        int count = parseInt(extractRaw(content, "requestCount"));
        int in = parseInt(extractRaw(content, "totalInputTokens"));
        int out = parseInt(extractRaw(content, "totalOutputTokens"));
        return new Snapshot(date, createdAt == null ? "" : createdAt, ide, count, in, out);
    }

    private static String extract(String content, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\":\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static String extractRaw(String content, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\":\\s*([^,\\n}]+)");
        Matcher m = p.matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    private static int parseInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
