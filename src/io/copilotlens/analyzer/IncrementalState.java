package io.copilotlens.analyzer;

import io.copilotlens.config.CopilotLensConfig;
import io.copilotlens.parser.CopilotRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Incremental scan state. Tracks last read byte offset per log file.
 * Avoids re-parsing the entire log on every run.
 *
 * State file: ~/.copilot-lens/state.json
 * Cache file: ~/.copilot-lens/cache.json — list of all parsed requests
 *
 * On run:
 *   - Load cache (previous parsed requests)
 *   - For each tracked file: read only new bytes (offset .. currentSize)
 *   - Parse new bytes, append to cache
 *   - If file shrunk (rotation): re-read from 0, replace cache for that file
 *   - Save state
 */
public class IncrementalState {

    public record FileEntry(long lastSize, long lastModified, int requestCount) {}

    private final Path stateFile;
    private final Path cacheFile;
    private final Map<String, FileEntry> files = new HashMap<>();
    private final List<CopilotRequest> cachedRequests = new ArrayList<>();

    public IncrementalState() {
        CopilotLensConfig cfg = CopilotLensConfig.load();
        this.stateFile = cfg.getStateFile();
        this.cacheFile = cfg.getCacheFile();
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (Files.exists(cacheFile)) {
            try {
                String content = Files.readString(cacheFile);
                parseCachedRequests(content);
            } catch (IOException ignored) {}
        }
        if (Files.exists(stateFile)) {
            try {
                String content = Files.readString(stateFile);
                parseState(content);
            } catch (IOException ignored) {}
        }
    }

    /**
     * Get previously cached requests for this file (or empty if first scan).
     */
    public List<CopilotRequest> getCachedFor(String filePath) {
        return cachedRequests.stream()
                .filter(r -> filePath.equals(r.summary()))  // simple heuristic
                .toList();
    }

    /**
     * Get the byte offset from which to start reading the file.
     * Returns 0 if file is new or was rotated.
     */
    public long getReadOffset(String filePath, long currentSize) {
        FileEntry entry = files.get(filePath);
        if (entry == null) return 0L;
        if (currentSize < entry.lastSize()) return 0L;  // rotated
        return entry.lastSize();
    }

    /**
     * Update state after a successful parse.
     */
    public void recordParsed(String filePath, long currentSize, long lastModified, int requestCount) {
        files.put(filePath, new FileEntry(currentSize, lastModified, requestCount));
        save();
    }

    public void addRequests(List<CopilotRequest> requests) {
        cachedRequests.addAll(requests);
    }

    public List<CopilotRequest> getAllCached() {
        return new ArrayList<>(cachedRequests);
    }

    public void clear() {
        files.clear();
        cachedRequests.clear();
    }

    public void save() {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, serializeState());
            Files.writeString(cacheFile, serializeCache());
        } catch (IOException ignored) {}
    }

    public long lastModified(String filePath) {
        FileEntry entry = files.get(filePath);
        return entry != null ? entry.lastModified() : 0L;
    }

    public void touchWithAttributes(String filePath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    Path.of(filePath), BasicFileAttributes.class);
            recordParsed(filePath, attrs.size(), attrs.lastModifiedTime().toMillis(), 0);
        } catch (Exception e) {
            // ignore
        }
    }

    private String serializeState() {
        StringBuilder sb = new StringBuilder("{\n  \"files\": {\n");
        boolean first = true;
        for (var e : files.entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            // Normalize path to forward-slash form to avoid Windows backslash escape issues
            String key = e.getKey().replace('\\', '/');
            sb.append("    \"").append(escape(key)).append("\": {")
              .append("\"size\": ").append(e.getValue().lastSize())
              .append(", \"mtime\": ").append(e.getValue().lastModified())
              .append(", \"count\": ").append(e.getValue().requestCount())
              .append("}");
        }
        sb.append("\n  }\n}");
        return sb.toString();
    }

    private String serializeCache() {
        StringBuilder sb = new StringBuilder("{\n  \"requests\": [\n");
        for (int i = 0; i < cachedRequests.size(); i++) {
            CopilotRequest r = cachedRequests.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("    {\"timestamp\": \"").append(r.timestamp())
              .append("\", \"ide\": \"").append(r.ide())
              .append("\", \"endpoint\": \"").append(escape(r.endpoint()))
              .append("\", \"inputTokens\": ").append(r.inputTokens())
              .append(", \"outputTokens\": ").append(r.outputTokens())
              .append(", \"messageCount\": ").append(r.messageCount())
              .append(", \"summary\": \"").append(escape(r.summary()))
              .append("\", \"workspaceHint\": \"").append(escape(r.workspaceHint()))
              .append("\", \"model\": \"").append(escape(r.model()))
              .append("\", \"provider\": \"").append(escape(r.provider()))
              .append("\", \"latencyMs\": ").append(r.latencyMs() == null ? "null" : r.latencyMs().toString())
              .append("}");
        }
        sb.append("\n  ]\n}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void parseCachedRequests(String content) {
        // Simple regex-based JSON parsing (works for our own output).
        // Two formats supported: legacy (8 fields) and current (11 fields).
        // Backward-compatible: missing optional fields default to null/0.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\\{\"timestamp\":\\s*\"([^\"]+)\",\\s*\"ide\":\\s*\"(\\w+)\"," +
            "\\s*\"endpoint\":\\s*\"([^\"]*)\"," +
            "\\s*\"inputTokens\":\\s*(\\d+),\\s*\"outputTokens\":\\s*(\\d+)," +
            "\\s*\"messageCount\":\\s*(\\d+),\\s*\"summary\":\\s*\"([^\"]*)\"," +
            "\\s*\"workspaceHint\":\\s*\"([^\"]*)\"" +
            "(?:,\\s*\"model\":\\s*\"([^\"]*)\")?" +
            "(?:,\\s*\"provider\":\\s*\"([^\"]*)\")?" +
            "(?:,\\s*\"latencyMs\":\\s*(\\d+|null))?" +
            "\\}");
        java.util.regex.Matcher m = p.matcher(content);
        while (m.find()) {
            try {
                LocalDateTime ts = LocalDateTime.parse(m.group(1));
                CopilotRequest.Ide ide = CopilotRequest.Ide.valueOf(m.group(2));
                String endpoint = m.group(3);
                int in = Integer.parseInt(m.group(4));
                int out = Integer.parseInt(m.group(5));
                int msgs = Integer.parseInt(m.group(6));
                String summary = m.group(7);
                String ws = m.group(8);
                String model = m.group(9);
                String provider = m.group(10);
                String latencyStr = m.group(11);
                Integer latency = null;
                if (latencyStr != null && !latencyStr.equals("null")) {
                    try { latency = Integer.parseInt(latencyStr); } catch (Exception ignored) {}
                }
                cachedRequests.add(new CopilotRequest(ts, ide, endpoint, in, out, msgs,
                        summary, ws, model, provider, latency));
            } catch (Exception ignored) {}
        }
    }

    private void parseState(String content) {
        // Match "filepath": {"size": N, "mtime": N, "count": N}
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\"([^\"]+)\":\\s*\\{\"size\":\\s*(\\d+),\\s*\"mtime\":\\s*(\\d+),\\s*\"count\":\\s*(\\d+)\\}");
        java.util.regex.Matcher m = p.matcher(content);
        while (m.find()) {
            String path = m.group(1);
            long size = Long.parseLong(m.group(2));
            long mtime = Long.parseLong(m.group(3));
            int count = Integer.parseInt(m.group(4));
            files.put(path, new FileEntry(size, mtime, count));
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public static LocalDateTime fromEpochMillis(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }
}
