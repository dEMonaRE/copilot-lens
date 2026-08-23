package io.copilotlens.reporter;

import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.parser.CopilotRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RTK-style terminal output. ANSI renkli kutu cizimi, bar grafikleri.
 * RTK'nin `gain` ciktisina benzer format.
 */
public class CliReporter {

    // ANSI escape sequences — Unicode escape avoids source-encoding issues
    private static final String ESC = "";
    private static final String RESET = ESC + "[0m";
    private static final String BOLD = ESC + "[1m";
    private static final String DIM = ESC + "[2m";
    private static final String CYAN = ESC + "[36m";
    private static final String GREEN = ESC + "[32m";
    private static final String YELLOW = ESC + "[33m";
    private static final String RED = ESC + "[31m";
    private static final String BLUE = ESC + "[34m";
    private static final String GRAY = ESC + "[90m";
    private static final String MAGENTA = ESC + "[35m";

    private final boolean ansi;

    public CliReporter(boolean ansi) {
        this.ansi = ansi;
    }

    public void print(Report report) {
        printBanner();
        printStats(report);
        if (!report.dailyDistribution().isEmpty()) {
            printDailyHistory(report.dailyDistribution());
        }
        printTopRequests(report.largestRequests());
        printHourlyDistribution(report.hourlyDistribution());
        printFooter(report);
    }

    public void printHistory(Report report) {
        printBanner();
        c(BOLD, "Daily History (--history)");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();
        if (report.dailyDistribution().isEmpty()) {
            c(GRAY, "  No data yet.");
            System.out.println();
            return;
        }
        int max = report.dailyDistribution().values().stream().max(Integer::compare).orElse(1);
        for (Map.Entry<LocalDate, Integer> e : report.dailyDistribution().entrySet()) {
            int barWidth = (int) (40.0 * e.getValue() / max);
            System.out.printf(Locale.ROOT, "  %s  ", e.getKey());
            c(GREEN, repeat('#', Math.max(0, barWidth)));
            System.out.printf(Locale.ROOT, " %d requests%n", e.getValue());
        }
    }

    private void c(String color, String text) {
        if (ansi) System.out.print(color + text + RESET);
        else System.out.print(text);
    }

    private void printBanner() {
        c(CYAN, "+================================================================+\n");
        c(CYAN, "|                                                                |\n");
        c(CYAN, "|   "); c(BOLD, "GitHub Copilot Lens"); c(CYAN, "                                       |\n");
        c(CYAN, "|   "); c(DIM, "Token & Premium Usage Analyzer"); c(CYAN, "                          |\n");
        c(CYAN, "|                                                                |\n");
        c(CYAN, "+================================================================+\n");
        System.out.println();
    }

    private void printStats(Report r) {
        c(BOLD, "Overall Usage");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();

        System.out.printf(Locale.ROOT, "  Request count     %,d (each = 1 premium request)%n", r.requestCount());
        System.out.printf(Locale.ROOT, "  Total input tok   %,d%n", r.totalInputTokens());
        System.out.printf(Locale.ROOT, "  Total output tok  %,d%n", r.totalOutputTokens());
        System.out.printf(Locale.ROOT, "  Avg input         %,.0f tok/request%n", r.avgInputTokens());
        System.out.printf(Locale.ROOT, "  Avg output        %,.0f tok/request%n", r.avgOutputTokens());
        System.out.printf(Locale.ROOT, "  Max input         %,d tok%n", r.maxInputTokens());
        System.out.printf(Locale.ROOT, "  Max output        %,d tok%n", r.maxOutputTokens());
        System.out.println();

        if (r.requestCount() > 0) {
            long durationMs = r.lastTimestampMs() - r.firstTimestampMs();
            if (durationMs > 0) {
                double perHour = r.requestCount() / (durationMs / 3_600_000.0);
                c(BOLD, "Tempo: ");
                System.out.printf(Locale.ROOT, "%.1f requests/hour%n", perHour);
            }
        }
    }

    private void printDailyHistory(Map<LocalDate, Integer> daily) {
        if (daily.size() < 2) return;
        c(BOLD, "Daily Trend (last " + daily.size() + " days)");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();
        int max = daily.values().stream().max(Integer::compare).orElse(1);
        daily.forEach((d, count) -> {
            int barWidth = (int) (30.0 * count / max);
            System.out.printf(Locale.ROOT, "  %s  ", d);
            color(GREEN, repeat('#', Math.max(0, barWidth)));
            System.out.printf(Locale.ROOT, " %d%n", count);
        });
    }

    private void printTopRequests(List<CopilotRequest> largest) {
        if (largest.isEmpty()) return;
        c(BOLD, "Top 10 Most Expensive Requests");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();

        int maxTokens = Math.max(1, largest.get(0).inputTokens());
        for (int i = 0; i < largest.size(); i++) {
            CopilotRequest r = largest.get(i);
            int barWidth = Math.max(1, (int) (35.0 * r.inputTokens() / maxTokens));

            String ideBadge = r.ide() == CopilotRequest.Ide.VSCODE
                    ? colorStr(BLUE, " VSCode")
                    : colorStr(MAGENTA, " IDEA ");

            System.out.printf(Locale.ROOT, "  %2d. %s%s  %,6d tok  ",
                    i + 1,
                    r.timestamp().toString().substring(11, 19),
                    ideBadge,
                    r.inputTokens());
            c(GREEN, repeat('#', barWidth));
            System.out.println();

            if (r.summary() != null && !r.summary().isEmpty()) {
                c(GRAY, "      +-- ");
                c(DIM, truncate(r.summary(), 70));
                System.out.println();
            }
            if (r.workspaceHint() != null) {
                c(GRAY, "      file: " + r.workspaceHint());
                System.out.println();
            }
        }
    }

    private void printHourlyDistribution(Map<String, Integer> hourly) {
        if (hourly.isEmpty()) return;
        c(BOLD, "Hourly Distribution");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();

        int max = hourly.values().stream().max(Integer::compare).orElse(1);
        for (int h = 0; h < 24; h++) {
            String key = h + ":00";
            int count = hourly.getOrDefault(key, 0);
            if (count == 0) continue;
            int barWidth = (int) (30.0 * count / max);
            if (ansi) System.out.print(BOLD + key + RESET + "  ");
            else System.out.print(key + "  ");
            color(GREEN, repeat('#', barWidth));
            System.out.printf(Locale.ROOT, " %d%n", count);
        }
    }

    private void printFooter(Report r) {
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();

        if (r.totalInputTokens() > 50_000) {
            c(RED, "WARNING: 50k+ tokens used in this session.");
            System.out.println();
            c(GRAY, "   To narrow context: close open files in IDE, close .md files.");
            System.out.println();
            System.out.println();
        }

        c(GRAY, "  HTML report : copilot-lens report");
        System.out.println();
        c(GRAY, "  History     : copilot-lens gain --history");
        System.out.println();
        c(GRAY, "  Discover    : copilot-lens discover");
        System.out.println();
        c(GRAY, "  Live watch  : copilot-lens watch");
        System.out.println();
        c(GRAY, "  JSON export : copilot-lens export json");
        System.out.println();
    }

    private void color(String color, String text) {
        if (ansi) System.out.print(color + text + RESET);
        else System.out.print(text);
    }

    private String colorStr(String color, String text) {
        return ansi ? color + text + RESET : text;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String repeat(char c, int n) {
        return repeat(String.valueOf(c), n);
    }

    private static String repeat(String s, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
