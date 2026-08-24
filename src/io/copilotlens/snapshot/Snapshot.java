package io.copilotlens.snapshot;

import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.parser.CopilotRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of one day's Copilot usage.
 *
 * Stored at ~/.copilot-lens/snapshots/YYYY-MM-DD.json
 *
 * The Report record carries ALL cached requests across many days; a Snapshot
 * is built from the subset of requests whose timestamp falls on {@link #date}.
 * That keeps each snapshot self-contained and additive across runs.
 */
public record Snapshot(
        String date,           // YYYY-MM-DD
        String createdAt,      // ISO local datetime
        String ide,            // "vscode" | "idea" | "mixed"
        int requestCount,
        int totalInputTokens,
        int totalOutputTokens
) {

    public LocalDate localDate() {
        return LocalDate.parse(date);
    }

    public int totalTokens() {
        return totalInputTokens + totalOutputTokens;
    }

    /** Build a Snapshot for {@code date} from the cached requests. */
    public static Snapshot forDate(LocalDate date, List<CopilotRequest> requests) {
        List<CopilotRequest> today = requests.stream()
                .filter(r -> r.timestamp().toLocalDate().equals(date))
                .collect(Collectors.toList());

        String ide = detectIde(today);
        int count = today.size();
        int in = today.stream().mapToInt(CopilotRequest::inputTokens).sum();
        int out = today.stream().mapToInt(CopilotRequest::outputTokens).sum();

        return new Snapshot(
                date.toString(),
                LocalDateTime.now().toString(),
                ide,
                count,
                in,
                out);
    }

    /** Build a Snapshot from an already-aggregated Report (no per-request filter). */
    public static Snapshot fromReport(LocalDate date, Report report, CopilotRequest.Ide ide) {
        return new Snapshot(
                date.toString(),
                LocalDateTime.now().toString(),
                ide == null ? "mixed" : ide.name().toLowerCase(Locale.ROOT),
                report.requestCount(),
                report.totalInputTokens(),
                report.totalOutputTokens());
    }

    private static String detectIde(List<CopilotRequest> reqs) {
        if (reqs.isEmpty()) return "mixed";
        boolean hasVscode = reqs.stream().anyMatch(r -> r.ide() == CopilotRequest.Ide.VSCODE);
        boolean hasIdea = reqs.stream().anyMatch(r -> r.ide() == CopilotRequest.Ide.INTELLIJ);
        if (hasVscode && hasIdea) return "mixed";
        if (hasVscode) return "vscode";
        return "idea";
    }
}
