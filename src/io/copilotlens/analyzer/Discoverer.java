package io.copilotlens.analyzer;

import io.copilotlens.parser.CopilotRequest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RTK-equivalent `discover` command: finds optimization opportunities in
 * Copilot usage.
 * - Largest single request
 * - Most-frequent context file
 * - Low signal/noise requests (big prompt, small response)
 * - Average prompt size
 * - Peak hour concentration
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

        // 3) Low signal/noise requests (big input, small output)
        long lowSignal = requests.stream()
                .filter(r -> r.isComplete() && r.inputTokens() > 2000 && r.outputTokens() < 100)
                .count();
        if (lowSignal > 0) {
            findings.add(new Finding(
                    "Low signal/noise requests",
                    String.format("%d requests: >2k input tokens but <100 output. " +
                                  "These prompts may be bloating context; " +
                                  "write tighter prompts.", lowSignal),
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

        // Sort by severity descending
        findings.sort(Comparator.comparingDouble(Finding::severity).reversed());
        return findings;
    }
}
