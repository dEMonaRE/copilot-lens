package io.copilotlens.reporter;

import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.analyzer.TrendAggregator;
import io.copilotlens.analyzer.TrendAggregator.Period;
import io.copilotlens.analyzer.TrendAggregator.TrendPoint;
import io.copilotlens.parser.CopilotRequest;
import io.copilotlens.snapshot.Snapshot;
import io.copilotlens.snapshot.SnapshotStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Self-contained HTML rapor. Dis CSS, dark mode destekli, inline tum veri.
 * Tarayicida acilinca calisir, dosyaya yazilir.
 */
public class HtmlReporter {

    public void write(Report report, Path output) throws Exception {
        SnapshotStore store = new SnapshotStore();
        List<Snapshot> snapshots = store.loadAll();
        String trendSection = renderTrendSection(snapshots);
        String topRows = renderTopRows(report.largestRequests());

        String html = buildHtml(report, topRows, trendSection);
        Files.writeString(output, html);
    }

    private String buildHtml(Report report, String topRows, String trendSection) {
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
        sb.append("    code { background: var(--card); padding: 2px 6px; border-radius: 3px; font-family: monospace; font-size: 13px; }\n");
        sb.append("    .empty { color: var(--muted); font-style: italic; }\n");
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
        sb.append("  </div>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    private String renderTopRows(List<CopilotRequest> requests) {
        StringBuilder sb = new StringBuilder();
        int max = Math.max(1, requests.stream().mapToInt(CopilotRequest::inputTokens).max().orElse(1));
        for (CopilotRequest r : requests) {
            int widthPct = (int) (100.0 * r.inputTokens() / max);
            String badge = r.ide() == CopilotRequest.Ide.VSCODE
                    ? "<span class='badge badge-vsc'>VSCode</span>"
                    : "<span class='badge badge-id'>IDEA</span>";
            sb.append("    <tr>")
              .append("<td>").append(r.timestamp().toString().substring(11, 19)).append("</td>")
              .append("<td>").append(badge).append("</td>")
              .append("<td>").append(String.format(Locale.ROOT, "%,d", r.inputTokens())).append("</td>")
              .append("<td class='summary'>").append(escape(r.summary())).append("</td>")
              .append("<td><div class='bar' style='width:").append(widthPct).append("%'></div></td>")
              .append("</tr>\n");
        }
        return sb.toString();
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
        sb.append("  <h2>Daily Trend (").append(points.size()).append(" days)</h2>\n");
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
}
