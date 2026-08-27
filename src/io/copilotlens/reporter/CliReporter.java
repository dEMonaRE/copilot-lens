package io.copilotlens.reporter;

import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.analyzer.TrendAggregator.Period;
import io.copilotlens.analyzer.TrendAggregator.TrendPoint;
import io.copilotlens.parser.CopilotRequest;
import io.copilotlens.snapshot.Snapshot;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
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
        printContextSnapshot(report);
        printModelDistribution(report);
        printProviderDistribution(report);
        printLatencySummary(report);
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

            String ideBadge = ideBadge(r.ide());

            System.out.printf(Locale.ROOT, "  %2d. %s%s  %,6d tok  ",
                    i + 1,
                    formatTime(r.timestamp()),
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

    private void printModelDistribution(Report r) {
        if (r.modelDistribution().isEmpty()) return;
        c(BOLD, "Model Distribution");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();
        int total = r.modelDistribution().values().stream().mapToInt(Integer::intValue).sum();
        for (var e : r.modelDistribution().entrySet()) {
            int pct = (int) (100.0 * e.getValue() / total);
            int barWidth = (int) (40.0 * pct / 100);
            System.out.printf(Locale.ROOT, "  %-30s  ", truncate(e.getKey(), 30));
            color(GREEN, repeat('#', Math.max(0, barWidth)));
            System.out.printf(Locale.ROOT, " %,4d (%d%%)%n", e.getValue(), pct);
        }
    }

    private void printProviderDistribution(Report r) {
        if (r.providerDistribution().isEmpty()) return;
        c(BOLD, "Provider Distribution");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();
        int total = r.providerDistribution().values().stream().mapToInt(Integer::intValue).sum();
        for (var e : r.providerDistribution().entrySet()) {
            int pct = (int) (100.0 * e.getValue() / total);
            int barWidth = (int) (40.0 * pct / 100);
            System.out.printf(Locale.ROOT, "  %-30s  ", truncate(e.getKey(), 30));
            color(GREEN, repeat('#', Math.max(0, barWidth)));
            System.out.printf(Locale.ROOT, " %,4d (%d%%)%n", e.getValue(), pct);
        }
    }

    private void printLatencySummary(Report r) {
        if (r.latencySampleCount() == 0) return;
        c(BOLD, "Latency");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();
        System.out.printf(Locale.ROOT, "  Avg latency      %,.0f ms  (over %,d samples)%n",
                r.avgLatencyMs(), r.latencySampleCount());
    }

    /**
     * IntelliJ session.usage_info eventlerinden context snapshot özeti.
     * Endpoint = "backgroundAgent/sessionUpdate" olan isteklerden summary içindeki
     * "ctx=X/Y conv=Z sys=W tools=V" parse edilir.
     */
    private void printContextSnapshot(Report r) {
        java.util.regex.Pattern SUMMARY_CTX = java.util.regex.Pattern.compile(
                "ctx=(\\d+)/(\\d+)\\s*\\+(\\d+)\\s*conv=(\\d+)\\s*sys=(\\d+)\\s*tools=(\\d+)");

        List<CopilotRequest> events = new ArrayList<>();
        for (CopilotRequest req : r.allRequests()) {
            if ("backgroundAgent/sessionUpdate".equals(req.endpoint())
                    && req.summary() != null && req.summary().contains("ctx=")) {
                events.add(req);
            }
        }
        if (events.isEmpty()) return;

        c(BOLD, "Context Snapshots (IntelliJ session.usage_info)");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();

        int firstCtx = 0, firstLimit = 0, firstConv = 0, firstSys = 0, firstTools = 0;
        int lastCtx = 0, lastLimit = 0, lastConv = 0;
        int turnCount = 0;
        int totalDelta = 0;

        for (int i = 0; i < events.size(); i++) {
            java.util.regex.Matcher m = SUMMARY_CTX.matcher(events.get(i).summary());
            if (!m.find()) continue;
            int ctx = Integer.parseInt(m.group(1));
            int limit = Integer.parseInt(m.group(2));
            int delta = Integer.parseInt(m.group(3));
            int conv = Integer.parseInt(m.group(4));
            int sys = Integer.parseInt(m.group(5));
            int tools = Integer.parseInt(m.group(6));

            if (i == 0) {
                firstCtx = ctx; firstLimit = limit; firstConv = conv; firstSys = sys; firstTools = tools;
            }
            lastCtx = ctx; lastLimit = limit; lastConv = conv;
            totalDelta += delta;
            turnCount++;
        }

        System.out.printf(Locale.ROOT, "  Token limit        %,d%n", lastLimit);
        System.out.printf(Locale.ROOT, "  Context now        %,d / %,d  (%.1f%%)%n",
                lastCtx, lastLimit, 100.0 * lastCtx / lastLimit);
        System.out.printf(Locale.ROOT, "  Conversation       %,d -> %,d  (+%,d tokens)%n",
                firstConv, lastConv, lastConv - firstConv);
        System.out.printf(Locale.ROOT, "  System overhead    %,d tokens (constant)%n", firstSys);
        System.out.printf(Locale.ROOT, "  Tool definitions   %,d tokens (constant)%n", firstTools);
        System.out.printf(Locale.ROOT, "  Turns              %d  (+%,d tokens total)%n",
                turnCount, totalDelta);
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

        // Token kaynak ayrımı — yalnızca birden fazla kaynak varsa göster
        int total = r.reportedRequestCount() + r.estimatedRequestCount() + r.noneTokenRequestCount();
        if (total > 0 && (r.reportedRequestCount() + r.estimatedRequestCount() + r.noneTokenRequestCount()) > 0) {
            int sources = 0;
            if (r.reportedRequestCount() > 0) sources++;
            if (r.estimatedRequestCount() > 0) sources++;
            if (r.noneTokenRequestCount() > 0) sources++;
            if (sources > 1) {
                c(GRAY, "  Token source: ");
                System.out.printf(Locale.ROOT, "%,d reported / %,d estimated / %,d unknown%n",
                        r.reportedRequestCount(), r.estimatedRequestCount(), r.noneTokenRequestCount());
                c(GRAY, "    (reported = log usage line; estimated = local BPE; unknown = tokenless)");
                System.out.println();
            }
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

    /** IDE renkli badge. VSCode mavi, IntelliJ mor, Cursor cyan, Windsurf magenta. */
    private String ideBadge(CopilotRequest.Ide ide) {
        return switch (ide) {
            case VSCODE   -> colorStr(BLUE, " VSCode");
            case INTELLIJ -> colorStr(MAGENTA, " IDEA ");
            case CURSOR   -> colorStr(CYAN, " Cursor");
            case WINDSURF -> colorStr(RED, " Wndsrf");
        };
    }

    /**
     * LocalDateTime.toString() saniye 0 olduğunda HH:MM (16 char) döner; biz
     * her zaman HH:MM:SS istiyoruz. DateTimeFormatter ile güvenli biçimde formatlar.
     */
    private static String formatTime(java.time.LocalDateTime ts) {
        return ts.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    /** Render the ASCII trend chart for the given points. */
    public void printTrend(List<TrendPoint> points, Period period, int totalSnapshots) {
        printBanner();
        String title = switch (period) {
            case DAILY   -> "Daily Trend";
            case WEEKLY  -> "Weekly Trend";
            case MONTHLY -> "Monthly Trend";
        };
        c(BOLD, title + " (last " + points.size() + " of " + totalSnapshots + " snapshots)");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();

        if (points.isEmpty()) {
            c(GRAY, "  No snapshots in range.");
            System.out.println();
            return;
        }

        int max = points.stream().mapToInt(TrendPoint::totalTokens).max().orElse(1);
        int barWidth = Math.min(40, Math.max(8, 64 - 18));
        for (TrendPoint p : points) {
            int w = (int) ((double) p.totalTokens() / max * barWidth);
            System.out.printf(Locale.ROOT, "  %-12s  ", p.label());
            color(GREEN, repeat('#', Math.max(0, w)));
            System.out.printf(Locale.ROOT, " %5d req  %,7d tok%n",
                    p.requestCount(), p.totalTokens());
        }
        System.out.println();
        c(GRAY, "  Tip: copilot-lens trend --period=weekly|monthly --days=N");
        System.out.println();
    }

    /** Confirmation line after a snapshot is persisted. */
    public void printSnapshotConfirmation(Snapshot s, Path dir) {
        printBanner();
        c(BOLD, "Snapshot saved");
        System.out.println();
        c(GRAY, repeat('-', 64));
        System.out.println();
        System.out.printf(Locale.ROOT, "  Date              %s%n", s.date());
        System.out.printf(Locale.ROOT, "  IDE               %s%n", s.ide());
        System.out.printf(Locale.ROOT, "  Requests          %,d%n", s.requestCount());
        System.out.printf(Locale.ROOT, "  Input tokens      %,d%n", s.totalInputTokens());
        System.out.printf(Locale.ROOT, "  Output tokens     %,d%n", s.totalOutputTokens());
        System.out.printf(Locale.ROOT, "  Total tokens      %,d%n", s.totalTokens());
        System.out.println();
        c(GRAY, "  File: " + dir.resolve(s.date() + ".json"));
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
