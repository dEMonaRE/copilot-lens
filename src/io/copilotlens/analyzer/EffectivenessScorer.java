package io.copilotlens.analyzer;

import io.copilotlens.detector.McpScanner;
import io.copilotlens.parser.CopilotRequest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Effectiveness Score: 0-100 score derived purely from already-collected
 * data, with 5 categories weighted equally at 20 points each.
 *
 * <p>Categories ported from the other project's scoring logic
 * (sessions.ts:624-738) and adapted to our data sources:
 *
 * <ul>
 *   <li><b>Prompt Quality</b> — average prompt character length, with a
 *       penalty for very short prompts.</li>
 *   <li><b>Tool Utilization</b> — count of distinct tool names seen across
 *       all chat-session turns.</li>
 *   <li><b>Efficiency</b> — fraction of requests with reported or estimated
 *       tokens (i.e. requests that returned usable data, not tokenless).</li>
 *   <li><b>MCP Utilization</b> — fraction of configured MCP servers that
 *       appear in any turn's tool list.</li>
 *   <li><b>Engagement</b> — median inter-request gap (close to ideal for
 *       sustained sessions) plus day-consistency bonus.</li>
 * </ul>
 *
 * <p>Output is a {@link Score} record with the total, each category, and
 * the tip strings from {@link #generateTips}. Pure CPU, no IO.
 */
public class EffectivenessScorer {

    /** One scoring category with label, raw points, max points, and detail. */
    public record CategoryScore(int score, int maxScore, String label, String detail) {}

    /** Aggregate score: total, categories, and tips. */
    public record Score(
            int total,
            int maxTotal,
            CategoryScore promptQuality,
            CategoryScore toolUtilization,
            CategoryScore efficiency,
            CategoryScore mcpUtilization,
            CategoryScore engagement,
            List<String> tips
    ) {}

    /** Gap cap reused from {@link StatsAggregator}. */
    private static final long MAX_GAP_MS = 5L * 60L * 1000L;

    /**
     * Score the given requests. {@code configuredMcps} is the list of MCP
     * server names from {@link McpScanner#configuredNames()}; pass an empty
     * list when none configured.
     */
    public Score score(List<CopilotRequest> requests, List<String> configuredMcps) {
        CategoryScore pq = scorePromptQuality(requests);
        CategoryScore tu = scoreToolUtilization(requests);
        CategoryScore ef = scoreEfficiency(requests);
        CategoryScore mu = scoreMcpUtilization(requests, configuredMcps);
        CategoryScore en = scoreEngagement(requests);

        List<CategoryScore> all = List.of(pq, tu, ef, mu, en);
        int total = 0;
        int max = 0;
        for (CategoryScore c : all) {
            total += c.score();
            max += c.maxScore();
        }
        List<String> tips = generateTips(pq, tu, ef, mu, en, requests, configuredMcps);
        return new Score(total, max, pq, tu, ef, mu, en, tips);
    }

    // ---- category scoring ----

    private CategoryScore scorePromptQuality(List<CopilotRequest> requests) {
        // Use whichever text field is non-null; prefer promptText (chat session)
        // over summary (log-derived).
        List<Integer> lens = new ArrayList<>();
        for (CopilotRequest r : requests) {
            String text = r.promptText() != null ? r.promptText() : r.summary();
            if (text == null) continue;
            String trimmed = text.strip();
            if (!trimmed.isEmpty()) lens.add(trimmed.length());
        }
        if (lens.isEmpty()) {
            return new CategoryScore(0, 20, "Prompt Quality", "no prompt text in this run");
        }
        int avg = (int) Math.round(lens.stream().mapToInt(Integer::intValue).average().orElse(0));
        int score;
        String tag;
        if (avg >= 100)      { score = 20; tag = "excellent"; }
        else if (avg >= 50)  { score = 15; tag = "good"; }
        else if (avg >= 20)  { score = 10; tag = "fair"; }
        else                 { score = 5;  tag = "short"; }
        return new CategoryScore(score, 20, "Prompt Quality",
                "avg prompt length: " + avg + " chars (" + tag + ")");
    }

    private CategoryScore scoreToolUtilization(List<CopilotRequest> requests) {
        Set<String> tools = new HashSet<>();
        for (CopilotRequest r : requests) {
            if (r.toolsUsed() != null) tools.addAll(r.toolsUsed());
        }
        int count = tools.size();
        int score;
        String tag;
        if (count >= 7)      { score = 20; tag = "excellent diversity"; }
        else if (count >= 5)  { score = 15; tag = "good diversity"; }
        else if (count >= 3)  { score = 10; tag = "moderate"; }
        else if (count >= 1)  { score = 7;  tag = "limited"; }
        else                 { score = 0;  tag = "no tools used"; }
        return new CategoryScore(score, 20, "Tool Utilization",
                count + " distinct tools (" + tag + ")");
    }

    private CategoryScore scoreEfficiency(List<CopilotRequest> requests) {
        if (requests.isEmpty()) {
            return new CategoryScore(0, 20, "Efficiency", "no requests");
        }
        // "Efficiency" proxy: fraction of requests that carry some token
        // signal (reported or estimated), i.e. not tokenless. A 100% ratio
        // means every request returned usable token data.
        int withTokens = 0;
        for (CopilotRequest r : requests) {
            CopilotRequest.TokenSource ts = r.tokenSource() == null
                    ? CopilotRequest.TokenSource.NONE : r.tokenSource();
            if (ts == CopilotRequest.TokenSource.REPORTED
                    || ts == CopilotRequest.TokenSource.ESTIMATED
                    || ts == CopilotRequest.TokenSource.ESTIMATED_HEURISTIC) {
                withTokens++;
            }
        }
        double ratio = (double) withTokens / requests.size();
        int pct = (int) Math.round(ratio * 100);
        int score;
        if (ratio >= 0.9)      score = 18;
        else if (ratio >= 0.7) score = 14;
        else if (ratio >= 0.5) score = 10;
        else if (ratio >= 0.3) score = 6;
        else                   score = 3;
        return new CategoryScore(score, 20, "Efficiency",
                pct + "% of requests carried token data");
    }

    private CategoryScore scoreMcpUtilization(List<CopilotRequest> requests,
                                              List<String> configuredMcps) {
        if (configuredMcps == null || configuredMcps.isEmpty()) {
            // Neutral: no MCP servers configured is not penalised.
            return new CategoryScore(10, 20, "MCP Utilization",
                    "no MCP servers configured (neutral)");
        }
        // Collect every tool name we've seen, plus any agent string
        // (e.g. "github.copilot.editsAgent" — sometimes MCP names leak in).
        Set<String> used = new HashSet<>();
        for (CopilotRequest r : requests) {
            if (r.toolsUsed() != null) used.addAll(r.toolsUsed());
            if (r.agent() != null) used.add(r.agent());
        }
        int usedCount = 0;
        for (String cfg : configuredMcps) {
            String cfgLower = cfg.toLowerCase().replaceAll("[-_\\s]", "");
            for (String u : used) {
                String uLower = u.toLowerCase().replaceAll("[-_\\s]", "");
                if (uLower.contains(cfgLower) || cfgLower.contains(uLower)) {
                    usedCount++;
                    break;
                }
            }
        }
        double ratio = (double) usedCount / configuredMcps.size();
        int score;
        if (ratio >= 0.8)      score = 20;
        else if (ratio >= 0.5) score = 15;
        else if (ratio > 0)    score = 10;
        else                   score = 5;
        return new CategoryScore(score, 20, "MCP Utilization",
                "using " + usedCount + "/" + configuredMcps.size() + " configured MCP servers");
    }

    private CategoryScore scoreEngagement(List<CopilotRequest> requests) {
        if (requests.isEmpty()) {
            return new CategoryScore(0, 20, "Engagement", "no requests");
        }
        // Active minutes via 5-min gap cap (same rule as StatsAggregator).
        long activeMs = 0;
        long[] ts = new long[requests.size()];
        ZoneId zone = ZoneId.systemDefault();
        for (int i = 0; i < ts.length; i++) {
            ts[i] = requests.get(i).timestamp().atZone(zone).toInstant().toEpochMilli();
        }
        Arrays.sort(ts);
        for (int i = 1; i < ts.length; i++) {
            long gap = ts[i] - ts[i - 1];
            if (gap < 0) gap = 0;
            if (gap > MAX_GAP_MS) gap = MAX_GAP_MS;
            activeMs += gap;
        }
        long activeMin = activeMs / 60_000L;

        int score;
        if (activeMin >= 5 && activeMin <= 60)      score = 15;
        else if (activeMin > 60 && activeMin <= 180) score = 12;
        else if (activeMin > 180)                    score = 9;
        else if (activeMin > 0)                      score = 6;
        else                                         score = 2;

        // Consistency bonus: distinct active days
        Set<LocalDate> days = new HashSet<>();
        for (CopilotRequest r : requests) days.add(r.timestamp().toLocalDate());
        if (days.size() >= 7)      score = Math.min(20, score + 5);
        else if (days.size() >= 3) score = Math.min(20, score + 3);

        String detail = "active " + activeMin + " min across " + days.size() + " day(s)";
        return new CategoryScore(score, 20, "Engagement", detail);
    }

    // ---- tips generation ----

    private List<String> generateTips(CategoryScore pq, CategoryScore tu, CategoryScore ef,
                                      CategoryScore mu, CategoryScore en,
                                      List<CopilotRequest> requests,
                                      List<String> configuredMcps) {
        List<String> tips = new ArrayList<>();

        if (pq.score() < 15) {
            int avg = avgPromptLen(requests);
            tips.add("Your prompts average " + avg + " chars — add more context, expected behavior, "
                    + "and constraints to reduce back-and-forth.");
        }
        if (tu.score() < 15) {
            Set<String> used = new HashSet<>();
            for (CopilotRequest r : requests) {
                if (r.toolsUsed() != null) used.addAll(r.toolsUsed());
            }
            List<String> suggestions = Arrays.asList("grep", "glob", "edit", "task", "view");
            List<String> missing = new ArrayList<>();
            for (String s : suggestions) {
                if (!used.contains(s)) missing.add(s);
            }
            if (!missing.isEmpty()) {
                String list = String.join(", ", missing.subList(0, Math.min(3, missing.size())));
                tips.add("Try using " + list + " — these tools can speed up your workflow.");
            } else {
                tips.add("Mix in more diverse tools (search, edit, view) to cover more workflow steps.");
            }
        }
        if (ef.score() < 15) {
            tips.add("Many requests are returning no token data — enable verbose logging in your IDE "
                    + "for richer analysis. See docs/LOG_ACTIVATION.md.");
        }
        if (mu.score() < 15 && configuredMcps != null && !configuredMcps.isEmpty()) {
            tips.add("You have configured MCP servers that aren't being invoked. "
                    + "Try referencing them explicitly in your prompts.");
        } else if (configuredMcps != null && configuredMcps.isEmpty()) {
            tips.add("Consider configuring MCP servers for richer Copilot capabilities "
                    + "(database, API integrations, etc.).");
        }
        if (en.score() < 15) {
            Set<LocalDate> days = new HashSet<>();
            for (CopilotRequest r : requests) days.add(r.timestamp().toLocalDate());
            if (days.size() < 3) {
                tips.add("Use Copilot more regularly to build momentum and improve your workflow.");
            } else {
                tips.add("Try tackling larger tasks with Copilot — your active minutes are "
                        + "below the ideal range for sustained sessions.");
            }
        }
        if (tips.isEmpty()) {
            tips.add("Great job! You are using your AI assistant effectively. Keep it up.");
        }
        return tips;
    }

    private static int avgPromptLen(List<CopilotRequest> requests) {
        int sum = 0, n = 0;
        for (CopilotRequest r : requests) {
            String text = r.promptText() != null ? r.promptText() : r.summary();
            if (text == null) continue;
            String t = text.strip();
            if (t.isEmpty()) continue;
            sum += t.length();
            n++;
        }
        return n == 0 ? 0 : (int) Math.round((double) sum / n);
    }
}
