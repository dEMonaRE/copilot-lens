package io.copilotlens.analyzer;

import io.copilotlens.parser.CopilotRequest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RTK-equivalent `discover` command: finds optimization opportunities in
 * Copilot usage.
 * - Largest single request
 * - Most-frequent context file
 * - Low signal/noise (latency proxy: heavy input, short round-trip)
 * - Average prompt size
 * - Peak hour concentration
 * Kademe 1 SNR heuristics (require chat-session data; silent on log-only):
 * - Bloated summary ratio
 * - Workspace repetition
 * - Prompt repetition fingerprint
 */
public class Discoverer {

    public record Finding(String title, String detail, double severity) {}

    public List<Finding> analyze(List<CopilotRequest> requests) {
        List<Finding> findings = new ArrayList<>();
        if (requests.isEmpty()) return findings;

        // 1) Largest single request
        Optional<CopilotRequest> biggest = requests.stream()
                .max(Comparator.comparingInt(CopilotRequest::inputTokens));
        biggest.ifPresent(r -> findings.add(new Finding(
                "Largest single request",
                String.format("%s -- %,d input tokens (session max). " +
                              "Consider narrowing the context.", r.timestamp(), r.inputTokens()),
                r.inputTokens() / 1000.0)));

        // 2) Most-frequent workspace file in context
        Map<String, Integer> workspaceFreq = requests.stream()
                .map(CopilotRequest::workspaceHint)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(w -> w, Collectors.summingInt(w -> 1)));
        workspaceFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> findings.add(new Finding(
                        "Most-frequent context file",
                        String.format("%s -- appeared in %d requests. " +
                                      "File may be heavy; try closing it in your IDE.", e.getKey(), e.getValue()),
                        e.getValue() * 1.0)));

        // 3) Low signal/noise requests — REPLACED with latency proxy.
        //
        // The old check `r.isComplete() && inputTokens > 2000 && outputTokens < 100`
        // never fires for chat-session turns because their outputTokens is
        // always 0 (TokenSource.NONE). A short round-trip on heavy input is
        // a better SNR proxy because it doesn't depend on token counts.
        long lowSignal = requests.stream()
                .filter(r -> r.inputTokens() > 2000
                        && r.latencyMs() != null && r.latencyMs() < 1500)
                .count();
        if (lowSignal > 0) {
            findings.add(new Finding(
                    "Low signal/noise (latency proxy)",
                    String.format("%d requests: >2k input tokens but completed in <1500ms. " +
                                  "Short answer on heavy context suggests the prompt wasn't " +
                                  "well-scoped; tighten by listing expected behavior and " +
                                  "constraints up front.", lowSignal),
                    lowSignal * 2.0));
        }

        // 4) Average input growing too large
        double avg = requests.stream().mapToInt(CopilotRequest::inputTokens).average().orElse(0);
        if (avg > 3000) {
            findings.add(new Finding(
                    "High average prompt size",
                    String.format("%,.0f tokens/request on average. " +
                                  "Reduce open files in IDE; close .md files.", avg),
                    avg / 500.0));
        }

        // 5) Peak hours
        Map<Integer, Long> hourly = requests.stream()
                .collect(Collectors.groupingBy(r -> r.timestamp().getHour(), Collectors.counting()));
        hourly.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> findings.add(new Finding(
                        "Peak usage hour",
                        String.format("%02d:00 -- %d requests. Heavy usage at this hour.",
                                      e.getKey(), e.getValue()),
                        e.getValue() * 0.5)));

        // --- Kademe 1 SNR heuristics (require chat-session data) ---
        // All four work on promptText / workspaceHint populated by the
        // VSCode chat-session reader; for log-only runs they silently
        // contribute 0 because the fields are null.

        // 6) Bloated summary ratio: avg(summary.length / promptText.length) > 5
        // A long model-generated title riding atop a short user prompt is a
        // sign of low-signal context (e.g. title generator ran on every chat).
        List<Double> ratios = new ArrayList<>();
        for (CopilotRequest r : requests) {
            String p = r.promptText();
            String s = r.summary();
            if (p == null || p.isBlank() || s == null || s.isBlank()) continue;
            if (p.length() < 30) continue; // tiny prompts skew the ratio
            ratios.add((double) s.length() / p.length());
        }
        if (!ratios.isEmpty()) {
            double avgRatio = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (avgRatio > 5.0) {
                findings.add(new Finding(
                        "Bloated summary ratio",
                        String.format("Average summary/prompt length ratio = %.1fx. " +
                                "Long summaries inflate per-turn context; " +
                                "consider disabling auto-title or trimming prefixes.", avgRatio),
                        avgRatio / 2.0));
            }
        }

        // 7) Workspace repetition: same workspaceHint in >60% of last 50
        int window = Math.min(50, requests.size());
        Map<String, Integer> tailWs = new HashMap<>();
        for (int i = requests.size() - window; i < requests.size(); i++) {
            String w = requests.get(i).workspaceHint();
            if (w != null) tailWs.merge(w, 1, Integer::sum);
        }
        if (!tailWs.isEmpty() && window > 0) {
            for (var e : tailWs.entrySet()) {
                double pct = 100.0 * e.getValue() / window;
                if (pct > 60.0) {
                    findings.add(new Finding(
                            "Workspace repetition",
                            String.format("`%s` appears in %.0f%% of last %d requests. " +
                                    "Copilot scope may be too narrow; rotate files or " +
                                    "use #selection to avoid dragging it in.", e.getKey(), pct, window),
                            pct / 20.0));
                    break; // one finding is enough
                }
            }
        }

        // 8) Prompt repetition fingerprint: first-50-char prompt repeated >=8x
        Map<String, Integer> fp = new HashMap<>();
        for (CopilotRequest r : requests) {
            String p = r.promptText();
            if (p == null) continue;
            String key = p.substring(0, Math.min(50, p.length())).trim().toLowerCase();
            if (key.isEmpty()) continue;
            fp.merge(key, 1, Integer::sum);
        }
        int repeatFp = fp.values().stream().filter(c -> c >= 8).mapToInt(Integer::intValue).sum();
        if (repeatFp > 0) {
            findings.add(new Finding(
                    "Prompt repetition",
                    String.format("%d prompt fingerprints repeated >=8x. " +
                            "Copilot is doing the same work repeatedly; " +
                            "cache results or use agents for deterministic tasks.", repeatFp),
                    repeatFp / 4.0));
        }

        // Sort by severity descending
        findings.sort(Comparator.comparingDouble(Finding::severity).reversed());
        return findings;
    }
}
