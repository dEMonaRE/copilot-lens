package io.copilotlens.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays a live VSCode chat session file
 * ({@code workspaceStorage/<wsId>/chatSessions/<sessionId>.jsonl}).
 *
 * <p>Unlike completed sessions ({@link VsCodeSessionJson}), live sessions
 * are written incrementally as the user chats. Each line of the JSONL
 * file is one patch:
 *
 * <pre>
 * {"kind":0,"v": { ...full state... }}                   snapshot
 * {"kind":1,"k": ["requests", 0, "message", "text"],
 *                "v": "hey"}                             key update
 * {"kind":2,"k": ["requests"], "v": [ { ...turn... } ]}  array append
 * </pre>
 *
 * <p>This class replays the patches in order to reconstruct the final
 * session state, then extracts per-turn SessionTurn records using the
 * same logic as {@link VsCodeSessionJson}.
 *
 * <p>Note: the {@code requests} array is typically built by repeated
 * {@code kind:2} appends. Other nested arrays (e.g. response parts) are
 * usually replaced by {@code kind:1} full updates. This implementation
 * handles both shapes.
 */
public class VsCodeSessionJsonl {

    /** Maximum length of any message text we keep. */
    private static final int MAX_TEXT_LENGTH = 10_000;

    /** Re-export so callers can use either parser interchangeably. */
    public record SessionTurn(
            String sessionId,
            String title,
            String workspaceHash,
            java.time.LocalDateTime timestamp,
            String agentId,
            String promptText,
            String responseText,
            List<String> toolNames,
            String requestId
    ) {}

    private final long maxBytes;

    public VsCodeSessionJsonl(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * Replay a JSONL session file into per-turn records. Returns
     * {@code null} when the file is missing, oversized, or its patches
     * fail to parse. Empty list is returned when the file parsed but
     * no turns were extracted.
     */
    public List<SessionTurn> parse(Path sessionFile, String title, String workspaceHash) {
        if (!Files.isRegularFile(sessionFile)) return null;
        long size;
        try { size = Files.size(sessionFile); }
        catch (IOException e) { return null; }
        if (size > maxBytes) return null;

        String content;
        try {
            byte[] bytes = Files.readAllBytes(sessionFile);
            content = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }

        JsonObject state = null;
        for (String line : content.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            JsonElement el;
            try {
                el = JsonParser.parseString(line);
            } catch (Exception e) {
                continue; // skip malformed patches
            }
            if (!el.isJsonObject()) continue;
            JsonObject patch = el.getAsJsonObject();
            int kind = optInt(patch, "kind", -1);
            state = switch (kind) {
                case 0 -> snapshot(patch);
                case 1 -> applyKeyUpdate(state, patch);
                case 2 -> applyArrayAppend(state, patch);
                default -> state; // unknown kinds ignored
            };
            if (state == null) return null;
        }
        if (state == null) return List.of();

        // Now state should have the same shape as VsCodeSessionJson's root
        String sessionId = optString(state, "sessionId", null);
        JsonArray requests = state.has("requests") && state.get("requests").isJsonArray()
                ? state.getAsJsonArray("requests") : new JsonArray();
        if (requests.isEmpty()) return List.of();

        List<SessionTurn> out = new ArrayList<>(requests.size());
        for (JsonElement reqEl : requests) {
            SessionTurn t = extractTurn(reqEl, sessionId, title, workspaceHash);
            if (t != null) out.add(t);
        }
        return out;
    }

    /**
     * kind:0 — full snapshot. The state is replaced by the patch's {@code v}.
     */
    private JsonObject snapshot(JsonObject patch) {
        JsonElement v = patch.get("v");
        if (v != null && v.isJsonObject()) return v.getAsJsonObject();
        return null;
    }

    /**
     * kind:1 — replace state[k[0]][k[1]]...[k[n-1]] with {@code v}.
     */
    @SuppressWarnings("unchecked")
    private JsonObject applyKeyUpdate(JsonObject state, JsonObject patch) {
        if (state == null) return null;
        JsonElement k = patch.get("k");
        JsonElement v = patch.get("v");
        if (k == null || !k.isJsonArray()) return state;
        JsonArray path = k.getAsJsonArray();
        if (path.size() == 0) return v != null && v.isJsonObject() ? v.getAsJsonObject() : state;
        return setAtPath(state, path, 0, v);
    }

    /**
     * kind:2 — append each element of {@code v} to the array at state[k[0]]...[k[n-1]].
     * If the array doesn't exist yet, it is created with the incoming elements.
     */
    private JsonObject applyArrayAppend(JsonObject state, JsonObject patch) {
        if (state == null) return null;
        JsonElement k = patch.get("k");
        JsonElement v = patch.get("v");
        if (k == null || !k.isJsonArray()) return state;
        JsonArray path = k.getAsJsonArray();
        if (path.size() == 0) return state;
        if (v == null || !v.isJsonArray()) return state;

        // Walk to parent
        JsonObject parent = state;
        for (int i = 0; i < path.size() - 1; i++) {
            String key = path.get(i).getAsString();
            JsonElement cur = parent.get(key);
            if (cur == null || !cur.isJsonObject()) return state;
            parent = cur.getAsJsonObject();
        }
        String lastKey = path.get(path.size() - 1).getAsString();
        JsonElement arrEl = parent.get(lastKey);
        JsonArray arr;
        if (arrEl == null || !arrEl.isJsonArray()) {
            arr = new JsonArray();
            parent.add(lastKey, arr);
        } else {
            arr = arrEl.getAsJsonArray();
        }
        for (JsonElement item : v.getAsJsonArray()) {
            arr.add(item);
        }
        return state;
    }

    /**
     * Set {@code path[idx..]} to {@code v} by walking the path. Creates
     * intermediate objects as needed. Returns the (possibly updated) root.
     */
    private JsonObject setAtPath(JsonObject root, JsonArray path, int idx, JsonElement v) {
        String key = path.get(idx).getAsString();
        if (idx == path.size() - 1) {
            root.add(key, v == null ? com.google.gson.JsonNull.INSTANCE : v);
            return root;
        }
        JsonElement cur = root.get(key);
        JsonObject child;
        if (cur != null && cur.isJsonObject()) {
            child = cur.getAsJsonObject();
        } else {
            child = new JsonObject();
            root.add(key, child);
        }
        setAtPath(child, path, idx + 1, v);
        return root;
    }

    /**
     * Apply {@link VsCodeSessionJson}'s request-extraction logic.
     * Mirrors that class field-for-field. Kept as a copy (rather than a
     * shared helper) so each parser can evolve independently as we
     * discover more VSCode format variants.
     */
    private SessionTurn extractTurn(JsonElement reqEl, String sessionId, String title,
                                    String workspaceHash) {
        if (reqEl == null || !reqEl.isJsonObject()) return null;
        JsonObject req = reqEl.getAsJsonObject();

        long tsMs = optLong(req, "timestamp", 0L);
        if (tsMs <= 0) return null;
        java.time.LocalDateTime ts = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(tsMs), java.time.ZoneId.systemDefault());

        String agentId = null;
        JsonElement agentEl = req.get("agent");
        if (agentEl != null && agentEl.isJsonObject()) {
            agentId = optString(agentEl.getAsJsonObject(), "id", null);
        }

        String prompt = extractMessageText(req.get("message"));
        String response = extractResponseText(req.get("response"));
        List<String> tools = extractToolNames(req.get("response"));

        if ((prompt == null || prompt.isBlank())
                && (response == null || response.isBlank())
                && tools.isEmpty()) {
            return null;
        }

        return new SessionTurn(
                sessionId,
                title,
                workspaceHash,
                ts,
                agentId,
                truncate(prompt),
                truncate(response),
                tools,
                optString(req, "requestId", null)
        );
    }

    private static String extractMessageText(JsonElement msgEl) {
        if (msgEl == null || msgEl.isJsonNull()) return null;
        if (!msgEl.isJsonObject()) return null;
        JsonObject m = msgEl.getAsJsonObject();
        String s = optString(m, "text", null);
        if (s != null && !s.isEmpty()) return s;
        JsonElement parts = m.get("parts");
        if (parts != null && parts.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement p : parts.getAsJsonArray()) {
                if (p != null && p.isJsonObject()) {
                    String t = optString(p.getAsJsonObject(), "text", null);
                    if (t != null && !t.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(t);
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    private static String extractResponseText(JsonElement respEl) {
        if (respEl == null || !respEl.isJsonArray()) return null;
        JsonArray arr = respEl.getAsJsonArray();
        StringBuilder sb = new StringBuilder();
        for (JsonElement part : arr) {
            if (part == null || !part.isJsonObject()) continue;
            JsonObject o = part.getAsJsonObject();
            String value = optString(o, "value", null);
            if (value != null && !value.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(value);
            }
            if ("toolInvocationSerialized".equals(optString(o, "kind", null))) {
                JsonElement inv = o.get("invocationMessage");
                if (inv != null && inv.isJsonObject()) {
                    String v = optString(inv.getAsJsonObject(), "value", null);
                    if (v != null && !v.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(v);
                    }
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static List<String> extractToolNames(JsonElement respEl) {
        if (respEl == null || !respEl.isJsonArray()) return List.of();
        Set<String> tools = new LinkedHashSet<>();
        for (JsonElement part : respEl.getAsJsonArray()) {
            if (part == null || !part.isJsonObject()) continue;
            JsonObject o = part.getAsJsonObject();
            if (!"toolInvocationSerialized".equals(optString(o, "kind", null))) continue;
            String name = optString(o, "originMessage", null);
            if (name == null) {
                JsonElement inv = o.get("invocationMessage");
                if (inv != null && inv.isJsonObject()) {
                    name = optString(inv.getAsJsonObject(), "value", null);
                }
            }
            if (name != null && !name.isBlank()) {
                tools.add(normalizeToolName(name));
            }
        }
        return new ArrayList<>(tools);
    }

    private static String normalizeToolName(String raw) {
        int idx = raw.indexOf(" (MCP Server)");
        if (idx > 0) return raw.substring(0, idx).trim();
        if (raw.startsWith("Reading ") || raw.startsWith("Creating ") || raw.startsWith("Editing ")) {
            return raw.split("\\s+", 2)[0].toLowerCase();
        }
        if (raw.startsWith("Searching ")) return "search";
        if (raw.length() > 40) return raw.substring(0, 40);
        return raw;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_TEXT_LENGTH) return s;
        return s.substring(0, MAX_TEXT_LENGTH) + "\n...(truncated)";
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

    private static int optInt(JsonObject o, String k, int fallback) {
        JsonElement v = o.get(k);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return fallback;
        try { return v.getAsInt(); } catch (NumberFormatException e) { return fallback; }
    }
}
