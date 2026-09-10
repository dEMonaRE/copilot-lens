package io.copilotlens.analyzer;

import io.copilotlens.parser.CopilotRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Substring search over the prompt/response/summary fields of an in-memory
 * {@link CopilotRequest} list. Ponytail: no inverted index; the cache is
 * single-digit MB so a single linear scan is fast enough and avoids
 * serialisation fragility.
 *
 * <p>Snippet policy: ±60 chars around the match, trimmed to word boundary,
 * max {@value #MAX_HITS_PER_RECORD} hits per record so a single runaway
 * request doesn't flood the result list.
 */
public class SearchIndex {

    /** Default snippet half-window. */
    public static final int WINDOW = 60;
    /** Max hits recorded per request, regardless of how many matches it has. */
    public static final int MAX_HITS_PER_RECORD = 3;

    /** One search result: which request, which field, and the snippet text. */
    public record SearchHit(CopilotRequest request, String field, String snippet) {}

    /**
     * Case-insensitive substring search. Returns up to {@code limit}
     * hits, ordered by request timestamp (insertion order = chronological
     * for the live parse path).
     */
    public List<SearchHit> search(List<CopilotRequest> requests, String query, int limit) {
        List<SearchHit> out = new ArrayList<>();
        if (query == null || query.isBlank()) return out;
        String q = query.toLowerCase(java.util.Locale.ROOT);

        for (CopilotRequest r : requests) {
            int hitsForRecord = 0;
            if (hitsForRecord < MAX_HITS_PER_RECORD) {
                addHits(out, r, "prompt", r.promptText(), q, limit - out.size());
                hitsForRecord = countFieldHits(out, r);
            }
            if (hitsForRecord < MAX_HITS_PER_RECORD && out.size() < limit) {
                addHits(out, r, "response", r.responseText(), q, limit - out.size());
                hitsForRecord = countFieldHits(out, r);
            }
            if (hitsForRecord < MAX_HITS_PER_RECORD && out.size() < limit) {
                addHits(out, r, "summary", r.summary(), q, limit - out.size());
            }
            if (out.size() >= limit) break;
        }
        return out;
    }

    private void addHits(List<SearchHit> out, CopilotRequest r, String field,
                         String text, String q, int remainingBudget) {
        if (text == null || text.isEmpty() || remainingBudget <= 0) return;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int added = 0;
        int from = 0;
        while (added < MAX_HITS_PER_RECORD) {
            int idx = lower.indexOf(q, from);
            if (idx < 0) break;
            out.add(new SearchHit(r, field, snippet(text, idx, q.length())));
            added++;
            if (out.size() >= remainingBudget + added - 1) {
                // hard stop on global cap; rollback the just-added hit
                // because we exceeded budget.
                out.remove(out.size() - 1);
                return;
            }
            from = idx + q.length();
        }
    }

    /**
     * Word-boundary-trimmed ±{@value #WINDOW}-char window. Falls back to
     * the raw slice if the trimmed version is empty.
     */
    private String snippet(String text, int idx, int matchLen) {
        int start = Math.max(0, idx - WINDOW);
        int end = Math.min(text.length(), idx + matchLen + WINDOW);
        String s = text.substring(start, end);
        // Trim to word boundary at the leading edge
        if (start > 0) {
            int sp = s.indexOf(' ');
            if (sp >= 0 && sp < 20) s = s.substring(sp + 1);
            else s = "..." + s;
        } else {
            s = s.stripLeading();
        }
        if (end < text.length()) {
            int sp = s.lastIndexOf(' ');
            if (sp >= 0 && sp > s.length() - 20) s = s.substring(0, sp);
            else s = s + "...";
        } else {
            s = s.stripTrailing();
        }
        return s;
    }

    private int countFieldHits(List<SearchHit> out, CopilotRequest r) {
        int n = 0;
        for (SearchHit h : out) if (h.request() == r) n++;
        return n;
    }
}
