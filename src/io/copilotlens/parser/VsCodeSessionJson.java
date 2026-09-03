package io.copilotlens.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses a completed VSCode chat session file
 * ({@code workspaceStorage/<wsId>/chatSessions/<sessionId>.json}).
 *
 * <p>Completed sessions are stored as full JSON with this shape:
 * <pre>
 * {
 *   "version": 3,
 *   "sessionId": "&lt;uuid&gt;",
 *   "creationDate": &lt;ms-epoch&gt;,
 *   "lastMessageDate": &lt;ms-epoch&gt;,
 *   "initialLocation": "panel",
 *   "requests": [
 *     {
 *       "requestId":  "&lt;uuid&gt;",
 *       "responseId": "&lt;uuid&gt;",
 *       "timestamp":   &lt;ms-epoch&gt;,
 *       "agent":       { "id": "setup.agent", ... },
 *       "message":     { "text": "hey", "parts": [...] },
 *       "response":    [
 *         { "kind": "progressMessage", "value": "..." },
 *         { "kind": "toolInvocationSerialized", "toolCallId": "...", "originMessage": "..." },
 *         ...
 *       ],
 *       "result":      { ... },
 *       "followups":   [ "..." ],
 *       "isCanceled":  false
 *     },
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p>This is the format the other project's {@code readSessionContent()}
 * reads. Unlike the live {@code .jsonl} patch format, completed sessions
 * carry the entire state in one file.
 *
 * <p>Files larger than {@code chatsession.maxBytes} are skipped to keep
 * memory under control; JSON parsing happens after the size check.
 */
public class VsCodeSessionJson {

    /** Maximum length of any message text we keep (longer text is truncated). */
    private static final int MAX_TEXT_LENGTH = 10_000;

    /**
     * One turn within a chat session. The Main wiring converts each
     * {@code SessionTurn} into a {@link CopilotRequest}.
     */
    public record SessionTurn(
            String sessionId,
            String title,
            String workspaceHash,
            LocalDateTime timestamp,
            String agentId,
            String promptText,
            String responseText,
            List<String> toolNames,
            String requestId
    ) {}

    private final long maxBytes;

    public VsCodeSessionJson(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * Parse a single session file. Returns {@code null} when the file is
     * too large, missing, unreadable, or its JSON does not match the
     * expected shape. Never throws — caller treats {@code null} as a skip.
     */
    public List<SessionTurn> parse(Path sessionFile, String title, String workspaceHash) {
        if (!Files.isRegularFile(sessionFile)) return null;
        long size;
        try { size = Files.size(sessionFile); }
        catch (IOException e) { return null; }
        if (size > maxBytes) return null;

        String json;
        try {
            byte[] bytes = Files.readAllBytes(sessionFile);
            json = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }

        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return null;
            root = el.getAsJsonObject();
        } catch (Exception e) {
            return null;
        }

        String sessionId = optString(root, "sessionId", null);
        JsonArray requests = root.has("requests") && root.get("requests").isJsonArray()
                ? root.getAsJsonArray("requests") : new JsonArray();
        if (requests.isEmpty()) return List.of();

        List<SessionTurn> out = new ArrayList<>(requests.size());
        for (JsonElement reqEl : requests) {
            SessionTurn t = parseRequest(reqEl, sessionId, title, workspaceHash);
            if (t != null) out.add(t);
        }
        return out;
    }

    private SessionTurn parseRequest(JsonElement reqEl, String sessionId, String title,
                                     String workspaceHash) {
        if (reqEl == null || !reqEl.isJsonObject()) return null;
        JsonObject req = reqEl.getAsJsonObject();

        long tsMs = optLong(req, "timestamp", 0L);
        if (tsMs <= 0) return null;
        LocalDateTime ts = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(tsMs), ZoneId.systemDefault());

        String agentId = null;
        JsonElement agentEl = req.get("agent");
        if (agentEl != null && agentEl.isJsonObject()) {
            agentId = optString(agentEl.getAsJsonObject(), "id", null);
        }

        String prompt = extractMessageText(req.get("message"));
        String response = extractResponseText(req.get("response"));
        List<String> tools = extractToolNames(req.get("response"));

        // Skip empty pseudo-turns (no prompt, no response, no tools)
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
        // Primary: m.text
        String s = optString(m, "text", null);
        if (s != null && !s.isEmpty()) return s;
        // Fallback: m.parts[].text (chat edit mode)
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
            String kind = optString(o, "kind", null);
            String value = optString(o, "value", null);
            // Only assistant text parts contribute. Skip progress/warning/tool.
            if (value != null && !value.isEmpty()) {
                // Heuristic: progressMessage / warning have empty value by design;
                // assistant text has actual content. Just take any non-empty.
                if (sb.length() > 0) sb.append("\n");
                sb.append(value);
            }
            // For tool invocations we already extract tool name elsewhere.
            // Inline invocationMessage may carry a human-readable label.
            if ("toolInvocationSerialized".equals(kind)) {
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

    /**
     * Extract tool names from response parts. Mirrors the other project's
     * {@code normalizeVSCodeToolName} logic in spirit: take
     * {@code originMessage} or {@code invocationMessage.value}, fall back
     * to {@code kind}.
     */
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
        // Strip " (MCP Server)" suffix like the other project does
        int idx = raw.indexOf(" (MCP Server)");
        if (idx > 0) return raw.substring(0, idx).trim();
        // Drop "Reading [](file://...)" style prefixes — too noisy for analytics
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
}
