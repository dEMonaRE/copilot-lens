package io.copilotlens.reporter;

import io.copilotlens.analyzer.EffectivenessScorer;
import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.analyzer.TrendAggregator;
import io.copilotlens.analyzer.TrendAggregator.Period;
import io.copilotlens.analyzer.TrendAggregator.TrendPoint;
import io.copilotlens.detector.McpScanner;
import io.copilotlens.parser.CopilotRequest;
import io.copilotlens.snapshot.Snapshot;
import io.copilotlens.snapshot.SnapshotStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Self-contained HTML rapor. Dis CSS, dark mode destekli, inline tum veri.
 * Tarayicida acilinca calisir, dosyaya yazilir.
 */
public class HtmlReporter {

    public void write(Report report, Path output) throws Exception {
        SnapshotStore store = new SnapshotStore();
        List<Snapshot> snapshots = store.loadAll();
        String trendSection = renderTrendSection(snapshots);
        String modelDonut = renderModelDonut(report.modelDistribution());
        String cumulativeByIde = renderCumulativeByIde(report.allRequests());
        String scoreSection = renderScoreSection(report);
        String topRows = renderTopRows(report.largestRequests());

        String html = buildHtml(report, topRows, trendSection, modelDonut, cumulativeByIde, scoreSection);
        Files.writeString(output, html);
    }

    private String buildHtml(Report report, String topRows, String trendSection,
                             String modelDonut, String cumulativeByIde, String scoreSection) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("  <meta charset=\"utf-8\">\n");
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("  <title>Copilot Lens Report</title>\n");
        sb.append("  <style>\n");
        sb.append("    :root { --bg: #ffffff; --card: #f6f8fa; --text: #24292f;\n");
        sb.append("            --muted: #57606a; --border: #d0d7de; --accent: #0969da;\n");
        sb.append("            --warn: #cf222e; --ok: #1a7f37; }\n");
        sb.append("    @media (prefers-color-scheme: dark) {\n");
        sb.append("      :root { --bg: #0d1117; --card: #161b22; --text: #c9d1d9;\n");
        sb.append("              --muted: #8b949e; --border: #30363d; --accent: #58a6ff;\n");
        sb.append("              --warn: #f85149; --ok: #3fb950; }\n");
        sb.append("    }\n");
        sb.append("    * { box-sizing: border-box; }\n");
        sb.append("    body { font-family: -apple-system, BlinkMacSystemFont, sans-serif;\n");
        sb.append("           max-width: 1100px; margin: 30px auto; padding: 0 20px;\n");
        sb.append("           background: var(--bg); color: var(--text); }\n");
        sb.append("    h1 { border-bottom: 2px solid var(--border); padding-bottom: 10px; }\n");
        sb.append("    h2 { color: var(--muted); margin-top: 30px; }\n");
        sb.append("    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin: 20px 0; }\n");
        sb.append("    .card { background: var(--card); padding: 20px; border-radius: 8px; border-left: 4px solid var(--accent); }\n");
        sb.append("    .card-label { color: var(--muted); font-size: 13px; text-transform: uppercase; letter-spacing: 0.5px; }\n");
        sb.append("    .card-value { font-size: 28px; font-weight: 700; margin-top: 8px; color: var(--accent); }\n");
        sb.append("    .card-value.warn { color: var(--warn); }\n");
        sb.append("    .card-value.ok { color: var(--ok); }\n");
        sb.append("    table { width: 100%; border-collapse: collapse; margin-top: 12px; background: var(--card); border-radius: 8px; overflow: hidden; }\n");
        sb.append("    th { background: var(--card); color: var(--muted); font-weight: 600; text-align: left; padding: 12px; font-size: 13px; text-transform: uppercase; }\n");
        sb.append("    td { padding: 12px; border-bottom: 1px solid var(--border); }\n");
        sb.append("    tr:last-child td { border-bottom: none; }\n");
        sb.append("    .bar { background: linear-gradient(90deg, var(--accent), #58a6ff); height: 8px; border-radius: 4px; }\n");
        sb.append("    .summary { font-family: 'SF Mono', Monaco, monospace; font-size: 12px; color: var(--muted); max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n");
        sb.append("    .footer { color: var(--muted); font-size: 13px; margin-top: 30px; padding-top: 20px; border-top: 1px solid var(--border); }\n");
        sb.append("    .meta { color: var(--muted); font-size: 14px; }\n");
        sb.append("    .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }\n");
        sb.append("    .badge-vsc { background: #0969da33; color: var(--accent); }\n");
        sb.append("    .badge-id { background: #8957e533; color: #8957e5; }\n");
        sb.append("    .badge-cursor { background: #00b8d433; color: #00b8d4; }\n");
        sb.append("    .badge-windsurf { background: #7c4dff33; color: #7c4dff; }\n");
        sb.append("    code { background: var(--card); padding: 2px 6px; border-radius: 3px; font-family: monospace; font-size: 13px; }\n");
        sb.append("    .empty { color: var(--muted); font-style: italic; }\n");
    sb.append("    .chart-wrap { background: var(--card); padding: 16px; border-radius: 8px; margin: 12px 0; }\n");
    sb.append("    .chart-wrap svg { width: 100%; height: auto; max-height: 240px; display: block; }\n");
    sb.append("    .chart-row { display: grid; grid-template-columns: 1fr 200px; gap: 16px; align-items: center; }\n");
    sb.append("    .score-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin: 12px 0; }\n");
    sb.append("    .score-card { background: var(--card); padding: 14px; border-radius: 8px; border-left: 3px solid var(--accent); }\n");
    sb.append("    .score-card .label { color: var(--muted); font-size: 11px; text-transform: uppercase; }\n");
    sb.append("    .score-card .value { font-size: 22px; font-weight: 700; color: var(--accent); margin-top: 4px; }\n");
    sb.append("    .score-card .detail { color: var(--muted); font-size: 12px; margin-top: 4px; }\n");
    sb.append("    .tips { background: var(--card); padding: 16px; border-radius: 8px; margin: 12px 0; }\n");
    sb.append("    .tips li { margin: 6px 0; }\n");
        sb.append("  </style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");
        sb.append("  <h1>GitHub Copilot Lens Report</h1>\n");
        sb.append("  <p class=\"meta\">Generated: ").append(LocalDateTime.now()).append("</p>\n");

        sb.append("  <div class=\"grid\">\n");
        sb.append("    <div class=\"card\"><div class=\"card-label\">Requests</div><div class=\"card-value\">")
          .append(report.requestCount()).append("</div></div>\n");
        sb.append("    <div class=\"card\"><div class=\"card-label\">Input Token</div><div class=\"card-value\">")
          .append(String.format(Locale.ROOT, "%,d", report.totalInputTokens())).append("</div></div>\n");
        sb.append("    <div class=\"card\"><div class=\"card-label\">Output Token</div><div class=\"card-value\">")
          .append(String.format(Locale.ROOT, "%,d", report.totalOutputTokens())).append("</div></div>\n");
        sb.append("    <div class=\"card\"><div class=\"card-label\">Avg Input</div><div class=\"card-value\">")
          .append(String.format(Locale.ROOT, "%,.0f", report.avgInputTokens())).append("</div></div>\n");
        sb.append("    <div class=\"card\"><div class=\"card-label\">Max Input</div><div class=\"card-value warn\">")
          .append(String.format(Locale.ROOT, "%,d", report.maxInputTokens())).append("</div></div>\n");
        sb.append("    <div class=\"card\"><div class=\"card-label\">Max Output</div><div class=\"card-value warn\">")
          .append(String.format(Locale.ROOT, "%,d", report.maxOutputTokens())).append("</div></div>\n");
        sb.append("  </div>\n");

        if (scoreSection != null) {
            sb.append(scoreSection);
        }

        if (cumulativeByIde != null) {
            sb.append("  <h2>Cumulative Tokens by IDE</h2>\n");
            sb.append(cumulativeByIde);
        }

        if (modelDonut != null) {
            sb.append("  <h2>Model Distribution</h2>\n");
            sb.append("  <div class='chart-row'><div class='empty'>See donut for share; bar chart below for daily counts.</div></div>\n");
            sb.append(modelDonut);
        }

        if (trendSection != null) {
            sb.append(trendSection);
        }

        sb.append("  <h2>Top 10 Most Expensive Requests</h2>\n");
        sb.append("  <table>\n");
        sb.append("    <tr><th>Time</th><th>IDE</th><th>Input Token</th><th>Message</th><th>Bar</th></tr>\n");
        sb.append(topRows);
        sb.append("  </table>\n");

        sb.append("  <div class=\"footer\">\n");
        sb.append("    <p>Generated by <code>copilot-lens</code>.</p>\n");
        sb.append("    <p>Refresh: <code>copilot-lens report</code></p>\n");
        sb.append("    <p>Live monitoring: <code>copilot-lens watch</code></p>\n");
        sb.append("    <p>Snapshot: <code>copilot-lens snapshot</code> &middot; Trend: <code>copilot-lens trend</code></p>\n");
        appendTokenSourceFooter(sb, report);
        sb.append("  </div>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    private String renderTopRows(List<CopilotRequest> requests) {
        StringBuilder sb = new StringBuilder();
        int max = Math.max(1, requests.stream().mapToInt(CopilotRequest::inputTokens).max().orElse(1));
        java.time.format.DateTimeFormatter timeFmt =
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
        for (CopilotRequest r : requests) {
            int widthPct = (int) (100.0 * r.inputTokens() / max);
            String badge = ideBadge(r.ide());
            // Output rozeti: response body'si logda yoksa uyarı rozeti göster
            String outBadge = "";
            if (r.outputTokens() == 0 && r.inputTokens() > 0) {
                outBadge = " <span class='badge' style='background:#cf222e22;color:#cf222e;"
                        + "@media(prefers-color-scheme:dark){background:#f8514922;color:#f85149}"
                        + "'>response not logged</span>";
            }
            sb.append("    <tr>")
              .append("<td>").append(r.timestamp().toLocalTime().format(timeFmt)).append("</td>")
              .append("<td>").append(badge).append("</td>")
              .append("<td>").append(String.format(Locale.ROOT, "%,d", r.inputTokens())).append(outBadge).append("</td>")
              .append("<td class='summary'>").append(escape(r.summary())).append("</td>")
              .append("<td><div class='bar' style='width:").append(widthPct).append("%'></div></td>")
              .append("</tr>\n");
        }
        return sb.toString();
    }

    private String ideBadge(CopilotRequest.Ide ide) {
        return switch (ide) {
            case VSCODE   -> "<span class='badge badge-vsc'>VSCode</span>";
            case INTELLIJ -> "<span class='badge badge-id'>IDEA</span>";
            case CURSOR   -> "<span class='badge badge-cursor'>Cursor</span>";
            case WINDSURF -> "<span class='badge badge-windsurf'>Windsurf</span>";
        };
    }

    private void appendTokenSourceFooter(StringBuilder sb, Report report) {
        int sources = 0;
        if (report.reportedRequestCount() > 0) sources++;
        if (report.estimatedRequestCount() > 0) sources++;
        if (report.estimatedHeuristicRequestCount() > 0) sources++;
        if (report.noneTokenRequestCount() > 0) sources++;
        if (sources <= 1) return;
        sb.append("    <p class=\"meta\">Token source: ")
          .append(String.format(Locale.ROOT, "%,d reported", report.reportedRequestCount()))
          .append(" / ")
          .append(String.format(Locale.ROOT, "%,d BPE-estimated", report.estimatedRequestCount()))
          .append(" / ")
          .append(String.format(Locale.ROOT, "%,d heuristic", report.estimatedHeuristicRequestCount()))
          .append(" / ")
          .append(String.format(Locale.ROOT, "%,d unknown", report.noneTokenRequestCount()))
          .append(" (reported = log usage; BPE = jtokkit local count;")
          .append(" heuristic = char-based estimate; unknown = tokenless)</p>\n");
    }

    /**
     * Render the daily trend section from stored snapshots.
     * Returns {@code null} when no snapshots exist so the caller can skip.
     */
    private String renderTrendSection(List<Snapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return "  <h2>Daily Trend</h2>\n"
                 + "  <p class=\"empty\">No snapshots yet. Run <code>copilot-lens snapshot</code> to start tracking daily totals.</p>\n";
        }

        TrendAggregator agg = new TrendAggregator();
        List<TrendPoint> points = agg.aggregate(snapshots, Period.DAILY);
        int max = Math.max(1, points.stream().mapToInt(TrendPoint::totalTokens).max().orElse(1));

        StringBuilder sb = new StringBuilder();
        sb.append("  <h2 id='trend'>Daily Trend (").append(points.size()).append(" days)</h2>\n");

        // SVG bar chart above the table — visual first, numbers on demand.
        List<String> labels = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        for (TrendPoint p : points) {
            labels.add(p.label());
            vals.add(p.totalTokens());
        }
        sb.append("  <div class='chart-wrap'>")
          .append(SvgChart.barChart(labels, vals, 720, 200))
          .append("</div>\n");

        sb.append("  <table>\n");
        sb.append("    <tr><th>Date</th><th>Requests</th><th>Tokens</th><th>Bar</th></tr>\n");
        for (TrendPoint p : points) {
            int widthPct = (int) (100.0 * p.totalTokens() / max);
            sb.append("    <tr>")
              .append("<td>").append(p.label()).append("</td>")
              .append("<td>").append(String.format(Locale.ROOT, "%,d", p.requestCount())).append("</td>")
              .append("<td>").append(String.format(Locale.ROOT, "%,d", p.totalTokens())).append("</td>")
              .append("<td><div class='bar' style='width:").append(widthPct).append("%'></div></td>")
              .append("</tr>\n");
        }
        sb.append("  </table>\n");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Render the donut chart for the model distribution. Returns {@code null}
     * when there's nothing to plot so the caller can skip the section.
     */
    private String renderModelDonut(Map<String, Integer> distribution) {
        if (distribution == null || distribution.isEmpty()) return null;
        return "  <div class='chart-wrap'>"
                + SvgChart.donut(distribution, 160)
                + "</div>\n";
    }

    /**
     * Render the cumulative-tokens-by-IDE line chart. Buckets by date,
     * one series per IDE. Last 14 days max so the chart stays readable.
     */
    private String renderCumulativeByIde(List<CopilotRequest> requests) {
        if (requests == null || requests.isEmpty()) return null;
        TreeMap<LocalDate, int[]> daily = new TreeMap<>();
        for (CopilotRequest r : requests) {
            LocalDate d = r.timestamp().toLocalDate();
            int ideIdx = ideIndex(r.ide());
            int[] agg = daily.computeIfAbsent(d, k -> new int[4]);
            if (ideIdx >= 0) agg[ideIdx] += r.totalTokens();
        }
        // tail 14 days
        List<LocalDate> days = new ArrayList<>(daily.keySet());
        int from = Math.max(0, days.size() - 14);
        days = days.subList(from, days.size());

        Map<String, List<Integer>> series = new java.util.LinkedHashMap<>();
        List<Integer> vscSeries = new ArrayList<>();
        List<Integer> ideaSeries = new ArrayList<>();
        List<Integer> cursorSeries = new ArrayList<>();
        List<Integer> windSeries = new ArrayList<>();
        for (LocalDate d : days) {
            int[] a = daily.getOrDefault(d, new int[4]);
            vscSeries.add(a[0]);
            ideaSeries.add(a[1]);
            cursorSeries.add(a[2]);
            windSeries.add(a[3]);
        }
        series.put("VSCode", vscSeries);
        series.put("IntelliJ", ideaSeries);
        series.put("Cursor", cursorSeries);
        series.put("Windsurf", windSeries);

        List<String> xLabels = new ArrayList<>();
        for (LocalDate d : days) xLabels.add(d.toString());

        return "  <div class='chart-wrap'>"
                + SvgChart.lineChart(series, 720, 200, xLabels)
                + "</div>\n";
    }

    /** 0=VSCode, 1=IntelliJ, 2=Cursor, 3=Windsurf. */
    private static int ideIndex(CopilotRequest.Ide ide) {
        return switch (ide) {
            case VSCODE   -> 0;
            case INTELLIJ -> 1;
            case CURSOR   -> 2;
            case WINDSURF -> 3;
        };
    }

    /**
     * Render the Effectiveness Score section. Mirrors CLI {@code printScore}
     * so HTML and CLI report the same numbers.
     */
    private String renderScoreSection(Report report) {
        try {
            List<String> configuredMcps;
            try { configuredMcps = new McpScanner().configuredNames(); }
            catch (Exception e) { configuredMcps = List.of(); }
            EffectivenessScorer scorer = new EffectivenessScorer();
            EffectivenessScorer.Score score =
                    scorer.score(report.allRequests(), configuredMcps);

            StringBuilder s = new StringBuilder();
            s.append("  <h2 id='score'>Effectiveness Score</h2>\n");
            s.append("  <p class='meta'>Total: <b>").append(score.total())
             .append(" / ").append(score.maxTotal()).append("</b></p>\n");
            s.append("  <div class='score-grid'>\n");
            appendScoreCard(s, score.promptQuality());
            appendScoreCard(s, score.toolUtilization());
            appendScoreCard(s, score.efficiency());
            appendScoreCard(s, score.mcpUtilization());
            appendScoreCard(s, score.engagement());
            s.append("  </div>\n");
            if (!score.tips().isEmpty()) {
                s.append("  <div class='tips'><b>Tips</b><ul>\n");
                for (String tip : score.tips()) {
                    s.append("    <li>").append(escape(tip)).append("</li>\n");
                }
                s.append("  </ul></div>\n");
            }
            return s.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void appendScoreCard(StringBuilder s, EffectivenessScorer.CategoryScore c) {
        s.append("    <div class='score-card'>")
         .append("<div class='label'>").append(escape(c.label())).append("</div>")
         .append("<div class='value'>").append(c.score()).append(" / ").append(c.maxScore()).append("</div>")
         .append("<div class='detail'>").append(escape(c.detail())).append("</div>")
         .append("</div>\n");
    }
}
