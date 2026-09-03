package io.copilotlens.parser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the chat session index from VSCode's per-workspace state.vscdb SQLite
 * files. Source for the {@code chatsession.enabled} feature (P0 in
 * docs/FEATURES_PLAN.md).
 *
 * <p>VSCode stores a {@code chat.ChatSessionStore.index} key inside every
 * {@code state.vscdb} under {@code User\workspaceStorage\<wsId>\}. The value
 * is a JSON object with this shape:
 *
 * <pre>
 * {
 *   "version": 3,
 *   "entries": {
 *     "&lt;sessionId&gt;": {
 *       "sessionId":      "&lt;uuid&gt;",
 *       "title":          "&lt;string&gt;",
 *       "lastMessageDate": &lt;ms-epoch&gt;,
 *       "isEmpty":        true|false,
 *       "isImported":     true|false,
 *       "initialLocation":"panel",
 *       "hasPendingEdits":true|false,
 *       "timing":         { ... },
 *       ...
 *     },
 *     ...
 *   }
 * }
 * </pre>
 *
 * <p>This class only reads the index. The actual session content
 * (requests, response parts, prompt text) lives in sibling
 * {@code chatSessions\<sessionId>.{json,jsonl}} files and is parsed by
 * {@link VsCodeSessionJson} / {@link VsCodeSessionJsonl}.
 *
 * <p>The class is read-only and safe to call while VSCode is running.
 * SQLite is opened with {@code open_mode=1} (readonly) — sqlite-jdbc maps
 * that to {@code SQLITE_OPEN_READONLY} at the C level.
 */
public class VsCodeSessionDb {

    /** Single entry from the index. */
    public record IndexEntry(
            String sessionId,
            String title,
            long lastMessageDate,
            boolean isEmpty,
            String initialLocation,
            boolean hasPendingEdits
    ) {}

    /** All sessions for one workspace. */
    public record WorkspaceSessions(
            String workspaceHash,
            Path stateDb,
            List<IndexEntry> sessions
    ) {}

    /**
     * Walk {@code <userDataRoot>/workspaceStorage/&lt;wsId&gt;/state.vscdb}
     * for each workspace and read {@code chat.ChatSessionStore.index} from
     * each. Returns one record per workspace, in {@code wsId} ascending order.
     *
     * @param userDataRoot VSCode user-data root (the {@code Code} dir under
     *                     {@code %APPDATA%}). Must contain a
     *                     {@code workspaceStorage} subdir.
     */
    public List<WorkspaceSessions> loadAll(Path userDataRoot) {
        Path wsRoot = userDataRoot.resolve("workspaceStorage");
        if (!Files.isDirectory(wsRoot)) return List.of();

        List<WorkspaceSessions> out = new ArrayList<>();
        try (var stream = Files.list(wsRoot)) {
            stream.filter(Files::isDirectory).sorted().forEach(wsDir -> {
                Path dbFile = wsDir.resolve("state.vscdb");
                if (!Files.isRegularFile(dbFile)) return;
                List<IndexEntry> entries = loadIndexFrom(dbFile);
                if (!entries.isEmpty()) {
                    out.add(new WorkspaceSessions(wsDir.getFileName().toString(), dbFile, entries));
                }
            });
        } catch (Exception e) {
            // top-level failure: return whatever we have
        }
        return out;
    }

    /**
     * Open one {@code state.vscdb} and read the chat session index.
     * Returns an empty list if the file is unreadable, the schema has no
     * {@code chat.ChatSessionStore.index} key, or the JSON parse fails.
     * Never throws — callers iterate an empty list.
     */
    private List<IndexEntry> loadIndexFrom(Path dbFile) {
        String sql = "SELECT value FROM ItemTable WHERE key = ?";
        String indexKey = "chat.ChatSessionStore.index";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath() + "?open_mode=1");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, indexKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of();
                byte[] raw = rs.getBytes(1);
                if (raw == null) return List.of();
                return parseIndexJson(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // unreadable db, locked, or corrupt — skip this workspace
            return List.of();
        }
    }

    /**
     * Parse the JSON value of {@code chat.ChatSessionStore.index} and
     * extract one {@link IndexEntry} per session id.
     *
     * <p>The schema is owned by VSCode and is undocumented. We extract
     * only the well-known fields and ignore the rest. If {@code version}
     * is missing or higher than a known value, we still try — newer fields
     * are simply ignored.
     */
    private List<IndexEntry> parseIndexJson(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return List.of();
            JsonObject obj = root.getAsJsonObject();
            JsonElement entriesElem = obj.get("entries");
            if (entriesElem == null || !entriesElem.isJsonObject()) return List.of();
            JsonObject entries = entriesElem.getAsJsonObject();

            List<IndexEntry> out = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : entries.entrySet()) {
                IndexEntry entry = parseOne(e.getKey(), e.getValue());
                if (entry != null) out.add(entry);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private IndexEntry parseOne(String mapKey, JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String sessionId = optString(o, "sessionId", mapKey);
        String title = optString(o, "title", null);
        long lastDate = optLong(o, "lastMessageDate", 0L);
        boolean isEmpty = optBool(o, "isEmpty", true);
        String initialLocation = optString(o, "initialLocation", null);
        boolean hasPendingEdits = optBool(o, "hasPendingEdits", false);
        return new IndexEntry(sessionId, title, lastDate, isEmpty, initialLocation, hasPendingEdits);
    }

    private static String optString(JsonObject o, String k, String fallback) {
        JsonElement v = o.get(k);
        return (v != null && !v.isJsonNull() && v.isJsonPrimitive()) ? v.getAsString() : fallback;
    }

    private static long optLong(JsonObject o, String k, long fallback) {
        JsonElement v = o.get(k);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return fallback;
        try { return v.getAsLong(); } catch (NumberFormatException e) { return fallback; }
    }

    private static boolean optBool(JsonObject o, String k, boolean fallback) {
        JsonElement v = o.get(k);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return fallback;
        try { return v.getAsBoolean(); } catch (Exception e) { return fallback; }
    }
}
