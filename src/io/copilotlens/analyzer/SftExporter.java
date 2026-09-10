package io.copilotlens.analyzer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.copilotlens.parser.CopilotRequest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports real Copilot chat sessions as OpenAI chat-format JSONL, ready
 * for fine-tuning. Reads from the in-memory request list (which the
 * chat-session enricher has already populated with prompt/response text)
 * rather than the on-disk cache, to avoid regex fragility in the cache
 * round-trip.
 *
 * <p>One document per (user prompt, assistant response) pair. Turns within
 * a session are written in timestamp order so consecutive turns share
 * the previous assistant message as context — basic multi-turn training
 * examples without extra bookkeeping.
 *
 * <p>Sessions with &lt;2 turns are skipped (single-turn exports aren't
 * useful for SFT and would dilute the file).
 *
 * <p>Per-document truncation: when the combined user+assistant text
 * exceeds {@code maxTokens}, drop the head of the response (assistant
 * completions are usually where the actual answer lives; cutting the
 * prefix loses less than cutting the suffix).
 */
public class SftExporter {

    private final int maxTokens;
    private final TokenCounter counter;
    private final Gson gson = new Gson();

    /**
     * @param maxTokens per-document token cap. 8000 fits OpenAI's
     *                  default gpt-4o fine-tune input window with margin.
     */
    public SftExporter(int maxTokens) {
        this.maxTokens = maxTokens;
        this.counter = new TokenCounter();
    }

    /**
     * Group by {@code sessionId}, walk each session in chronological
     * order, and write one JSON object per turn that has both prompt
     * and response text. Returns the number of documents written.
     */
    public int write(List<CopilotRequest> requests, Path output) throws IOException {
        Map<String, List<CopilotRequest>> bySession = groupBySession(requests);
        try (BufferedWriter w = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            int written = 0;
            for (var e : bySession.entrySet()) {
                List<CopilotRequest> turns = e.getValue();
                if (turns.size() < 2) continue;
                turns.sort(Comparator.comparing(CopilotRequest::timestamp));
                JsonArray messages = new JsonArray();
                for (int i = 0; i < turns.size(); i++) {
                    CopilotRequest r = turns.get(i);
                    if (r.promptText() == null || r.promptText().isBlank()) continue;
                    // Build messages up to and including this turn.
                    JsonArray turnMessages = deepCopy(messages);
                    turnMessages.add(message("user", r.promptText()));
                    if (r.responseText() != null && !r.responseText().isBlank()) {
                        turnMessages.add(message("assistant",
                                truncate(r.responseText(), remainingBudget(messages, r.promptText()))));
                    } else {
                        // No response -> skip; can't form a complete SFT pair.
                        continue;
                    }
                    if (totalTokens(turnMessages) > maxTokens) continue;

                    JsonObject doc = new JsonObject();
                    doc.addProperty("session_id", r.sessionId());
                    doc.addProperty("agent", r.agent());
                    doc.addProperty("timestamp", r.timestamp().toString());
                    doc.add("messages", turnMessages);
                    w.write(gson.toJson(doc));
                    w.newLine();
                    written++;

                    // Append the assistant message so the NEXT turn in
                    // this session sees prior context.
                    messages.add(message("assistant", r.responseText()));
                    // And seed user for next iteration? No: each turn's
                    // own promptText is the user message for that turn.
                    messages.add(message("user", r.promptText()));
                }
            }
            return written;
        }
    }

    /** Group requests by non-null sessionId; drop nulls. Stable order. */
    private Map<String, List<CopilotRequest>> groupBySession(List<CopilotRequest> requests) {
        Map<String, List<CopilotRequest>> out = new LinkedHashMap<>();
        for (CopilotRequest r : requests) {
            if (r.sessionId() == null || r.sessionId().isBlank()) continue;
            out.computeIfAbsent(r.sessionId(), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    /** Build a single OpenAI chat message object. */
    private JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** Deep-copy a JsonArray (Gson JsonElement is mutable; we need to fork). */
    private JsonArray deepCopy(JsonArray src) {
        JsonArray out = new JsonArray();
        if (src != null) for (var el : src) out.add(el.deepCopy());
        return out;
    }

    private int totalTokens(JsonArray messages) {
        int sum = 0;
        for (var el : messages) {
            sum += counter.count(el.getAsJsonObject().get("content").getAsString());
        }
        return sum;
    }

    /**
     * Remaining budget after reserving space for accumulated context and
     * the current user prompt. Negative result = context alone exceeds
     * the cap (will be filtered out by caller).
     */
    private int remainingBudget(JsonArray context, String prompt) {
        return maxTokens - totalTokens(context) - counter.count(prompt);
    }

    /**
     * Truncate {@code text} so its tokens fit within {@code budget}.
     * Drops the head of the response (assistant completions usually put
     * the answer at the end). Cheap linear cut, no binary search —
     * single calls per turn, not worth the optimisation.
     * # ponytail: linear cut, fine for per-turn exports; tighten if
     * # SFT input is huge and the head-trim wastes compute.
     */
    private String truncate(String text, int budget) {
        if (budget <= 0) return "";
        if (counter.count(text) <= budget) return text;
        // ~3.5 chars/token upper bound; start there, refine if over.
        int cut = Math.max(1, text.length() - (budget * 4));
        while (cut < text.length() && counter.count(text.substring(cut)) > budget) {
            cut = Math.min(text.length(), cut + 256);
        }
        if (cut >= text.length()) return "";
        return "...[truncated]..." + text.substring(cut);
    }
}
