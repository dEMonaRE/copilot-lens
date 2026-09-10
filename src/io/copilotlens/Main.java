package io.copilotlens;

import io.copilotlens.analyzer.Discoverer;
import io.copilotlens.analyzer.Discoverer.Finding;
import io.copilotlens.analyzer.EffectivenessScorer;
import io.copilotlens.analyzer.IncrementalState;
import io.copilotlens.analyzer.StatsAggregator;
import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.analyzer.TokenCounter;
import io.copilotlens.analyzer.TrendAggregator;
import io.copilotlens.analyzer.TrendAggregator.Period;
import io.copilotlens.analyzer.TrendAggregator.TrendPoint;
import io.copilotlens.config.CopilotLensConfig;
import io.copilotlens.detector.IdeDetector;
import io.copilotlens.detector.McpScanner;
import io.copilotlens.parser.CopilotRequest;
import io.copilotlens.parser.IntelliJParser;
import io.copilotlens.parser.LogParser;
import io.copilotlens.parser.VsCodeForkParser;
import io.copilotlens.parser.VsCodeParser;
import io.copilotlens.parser.VsCodeSessionDb;
import io.copilotlens.parser.VsCodeSessionJson;
import io.copilotlens.parser.VsCodeSessionJsonl;
import io.copilotlens.reporter.CliReporter;
import io.copilotlens.reporter.HtmlReporter;
import io.copilotlens.reporter.JsonReporter;
import io.copilotlens.snapshot.Snapshot;
import io.copilotlens.snapshot.SnapshotStore;
import io.copilotlens.util.DebugLog;
import io.copilotlens.watch.LogWatcher;

import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * copilot-lens CLI entry point.
 *
 * Commands (RTK feature parity):
 *   copilot-lens                  One-shot report (console + HTML)
 *   copilot-lens gain             Usage summary
 *   copilot-lens gain --history   Daily history trend
 *   copilot-lens discover         Most expensive patterns
 *   copilot-lens watch            Live terminal dashboard
 *   copilot-lens export json      JSON export
 *   copilot-lens report           HTML-only report
 *   copilot-lens snapshot         Persist today's totals
 *   copilot-lens trend            ASCII trend from snapshots
 *   copilot-lens init             Write default project config
 *   copilot-lens install          Copy wrapper to ~/.local/bin
 *
 * Options:
 *   --ide=vscode|idea|auto   IDE selection (default: auto)
 *   --log=<path>             Manual log file
 *   --period=daily|weekly|monthly   Trend grouping (default: daily)
 *   --days=N                 How many recent buckets to show (default: 30)
 *   --no-ansi                Disable color
 *   --help                   Help
 */
public class Main {

    public static void main(String[] args) {
        try {
            Args params = Args.parse(args);

            if (params.help) {
                printHelp();
                return;
            }

            DebugLog.info("command=" + params.command + " ide=" + params.ide +
                    " log=" + (params.logFile == null ? "<auto>" : params.logFile));

            switch (params.command) {
                case LOG, GAIN, REPORT -> runReport(params);
                case WATCH -> runWatch(params);
                case DISCOVER -> runDiscover(params);
                case EXPORT -> runExport(params);
                case INIT -> runInit(params);
                case SNAPSHOT -> runSnapshot(params);
                case TREND -> runTrend(params);
                case INSTALL -> runInstall(params);
                case SCORE -> runScore(params);
                case MCP -> runMcp(params);
                case SEARCH -> runSearch(params);
                case COST -> runCost(params);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            DebugLog.error("uncaught exception: " + e.getMessage(), e);
            if (DebugLog.isEnabled() || Boolean.getBoolean("copilot-lens.debug")) {
                e.printStackTrace();
                System.err.println("(stack trace logged to ~/.copilot-lens/debug.log)");
            }
            System.exit(1);
        }
    }

    static void runReport(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        IncrementalState state = new IncrementalState();

        List<CopilotRequest> requests = parseWithCache(state, log, parser, params);
        requests = enrichWithChatSessions(requests);

        Report report = new StatsAggregator().aggregate(requests);
        CliReporter cli = new CliReporter(!params.noAnsi);

        if (params.history || params.command == Args.Command.GAIN) {
            cli.printHistory(report);
        } else {
            cli.print(report);
        }

        // Only write HTML when 'report' command is explicitly used.
        // Default run is console-only — avoids Windows auto-opening the file.
        if (params.command == Args.Command.REPORT) {
            Path htmlOut = Paths.get("copilot-lens-report.html");
            new HtmlReporter().write(report, htmlOut);
            System.out.println("HTML report: " + htmlOut.toAbsolutePath());
        }

        // P1.2: append the Effectiveness Score section (categories + tips).
        List<String> configuredMcps;
        try { configuredMcps = new McpScanner().configuredNames(); }
        catch (NoClassDefFoundError | Exception e) { configuredMcps = List.of(); }
        EffectivenessScorer.Score score = new EffectivenessScorer().score(requests, configuredMcps);
        cli.printScore(score);
    }

    /**
     * Incremental parse: onceki cache + dosyanin yeni byte'lari.
     * Ilk calistirma tam parse, sonrakiler delta.
     */
    static List<CopilotRequest> parseWithCache(IncrementalState state, Path log,
                                                LogParser parser, Args params) throws Exception {
        CopilotLensConfig cfg = CopilotLensConfig.load();
        boolean useCache = cfg.getBool("cache.enabled");

        if (!useCache || params.logFile != null) {
            // Manuel log veya cache kapali: full parse
            return parser.parse(log);
        }

        long currentSize = Files.size(log);
        // Use normalized path for state lookup
        String normalizedPath = log.toString().replace('\\', '/');
        long offset = state.getReadOffset(normalizedPath, currentSize);

        if (offset == currentSize) {
            // Yeni veri yok, cache dondur
            List<CopilotRequest> cached = state.getAllCached();
            System.err.println("[cache] Using cached data (" + cached.size() +
                    " requests, no new data since last run)");
            return cached;
        }

        List<CopilotRequest> newRequests;
        if (offset == 0) {
            // First scan or rotated
            newRequests = parser.parse(log);
            System.err.println("[parse] Full scan: " + newRequests.size() + " requests");
        } else {
            // Incremental: read only new bytes
            Path tempSlice = Files.createTempFile("copilot-lens-delta-", ".log");
            try (var channel = java.nio.channels.FileChannel.open(log, java.nio.file.StandardOpenOption.READ)) {
                channel.position(offset);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) (currentSize - offset));
                channel.read(buf);
                Files.writeString(tempSlice, new String(buf.array()));
            }
            newRequests = parser.parse(tempSlice);
            Files.deleteIfExists(tempSlice);
            System.err.println("[parse] Delta scan: " + newRequests.size() +
                    " new requests (offset " + offset + " -> " + currentSize + ")");
        }

        state.addRequests(newRequests);

        // Record state (with normalized path)
        BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class);
        state.recordParsed(normalizedPath, currentSize, attrs.lastModifiedTime().toMillis(),
                state.getAllCached().size());

        return state.getAllCached();
    }

    static void runWatch(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        new LogWatcher(log, parser, new CliReporter(!params.noAnsi)).watch();
    }

    static void runInit(Args params) throws Exception {
        // Project-level config (cwd) takes priority; init creates it there.
        // User-level config (~/.copilot-lens/config.properties) is only used as
        // a fallback when no project config exists, and is not touched by init.
        Path configFile = Paths.get("config.properties");

        // Idempotent: skip if already exists to avoid triggering Windows file
        // association prompts when the .properties file gets recreated.
        if (Files.exists(configFile)) {
            System.out.println("Config already exists: " + configFile.toAbsolutePath());
            System.out.println("Edit it manually to change IDE log paths or tool settings.");
            System.out.println("Delete it first if you want to regenerate with new defaults.");
            return;
        }

        CopilotLensConfig.writeDefault();
        System.out.println("Project config written: " + configFile.toAbsolutePath());
        System.out.println("Edit to override IDE log paths and tool settings.");
    }

    /**
     * Install the wrapper into ~/.local/bin so `copilot-lens` is runnable
     * from anywhere on PATH. Equivalent to the old install.sh but goes
     * through the standard wrapper so output stays in the current terminal.
     */
    static void runInstall(Args params) throws Exception {
        // Find wrapper source: same directory the JVM was launched from.
        Path projectRoot = locateProjectRoot();
        Path wrapperSrc = projectRoot.resolve("copilot-lens.sh");
        if (!Files.exists(wrapperSrc)) {
            System.err.println("ERROR: wrapper not found: " + wrapperSrc);
            System.err.println("Run from the copilot-lens project root.");
            System.exit(1);
        }

        Path home = Paths.get(System.getProperty("user.home"));
        Path installDir = home.resolve(".local").resolve("bin");
        Files.createDirectories(installDir);
        Path wrapperDst = installDir.resolve("copilot-lens");

        // Replace existing file or symlink
        try { Files.delete(wrapperDst); } catch (java.nio.file.NoSuchFileException ignored) {}

        Files.copy(wrapperSrc, wrapperDst, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        // Ensure executable bit (best-effort on Windows)
        wrapperDst.toFile().setExecutable(true, false);

        System.out.println("OK Installed: " + wrapperDst);
        System.out.println("   (from " + wrapperSrc + ")");

        // PATH check + .bashrc update
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && pathEnv.contains(";" + installDir.toString())
                            || (pathEnv != null && pathEnv.startsWith(installDir.toString()))) {
            System.out.println("OK " + installDir + " is already in PATH");
        } else {
            Path bashrc = home.resolve(".bashrc");
            if (Files.exists(bashrc)) {
                String marker = "# copilot-lens PATH";
                String existing = Files.readString(bashrc);
                if (!existing.contains(marker)) {
                    String append = System.lineSeparator()
                                   + marker + System.lineSeparator()
                                   + "export PATH=\"$HOME/.local/bin:$PATH\"" + System.lineSeparator();
                    Files.writeString(bashrc, append,
                            java.nio.file.StandardOpenOption.APPEND);
                    System.out.println("OK PATH updated in " + bashrc);
                    System.out.println("   Activate with: source " + bashrc);
                } else {
                    System.out.println("OK PATH entry already present in " + bashrc);
                }
            } else {
                System.out.println("WARN No ~/.bashrc found. Add manually:");
                System.out.println("   export PATH=\"$HOME/.local/bin:$PATH\"");
            }
        }

        System.out.println();
        System.out.println("Install complete. Test with:");
        System.out.println("  copilot-lens --help");
    }

    /**
     * Locate the project root by searching upward from cwd for
     * {@code lib/jtokkit-*.jar} (the same convention the bash wrapper uses).
     */
    static Path locateProjectRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path lib = dir.resolve("lib");
            if (Files.isDirectory(lib)) {
                try (var s = Files.list(lib)) {
                    if (s.anyMatch(p -> p.getFileName().toString().startsWith("jtokkit-"))) {
                        return dir;
                    }
                } catch (Exception ignored) {}
            }
            dir = dir.getParent();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    static void runDiscover(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parser.parse(log);
        requests = enrichWithChatSessions(requests);

        Discoverer d = new Discoverer();
        List<Finding> findings = d.analyze(requests);

        System.out.println("Discovery Report");
        System.out.println();
        System.out.println("=".repeat(64));
        if (findings.isEmpty()) {
            System.out.println("No significant issues detected.");
            return;
        }
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            System.out.printf(Locale.ROOT, "%n[%d] %s%n", i + 1, f.title());
            System.out.printf(Locale.ROOT, "    %s%n", f.detail());
            System.out.printf(Locale.ROOT, "    Severity: %.1f%n", f.severity());
        }
        System.out.println();
        System.out.println("=".repeat(64));
        System.out.println("Suggestions:");
        System.out.println("  - Reduce number of open files in IDE");
        System.out.println("  - Close .md files (agents.md, claude.md, business docs)");
        System.out.println("  - Prefer inline suggestions over Chat");
        System.out.println("  - Use #selection in custom commands, don't paste whole files");
    }

    /**
     * P2.1: dedicated score command. Same parse+enrich+score path as
     * runReport, but stops at printScore so the full report doesn't repeat.
     */
    static void runScore(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parseWithCache(
                new IncrementalState(), log, parser, params);
        requests = enrichWithChatSessions(requests);

        List<String> configuredMcps;
        try { configuredMcps = new McpScanner().configuredNames(); }
        catch (NoClassDefFoundError | Exception e) { configuredMcps = List.of(); }
        EffectivenessScorer.Score score =
                new EffectivenessScorer().score(requests, configuredMcps);
        new CliReporter(!params.noAnsi).printScore(score);
    }

    /**
     * P2.3: list configured MCP servers and how often each was invoked
     * across all chat-session turns in this parse.
     */
    static void runMcp(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parseWithCache(
                new IncrementalState(), log, parser, params);
        requests = enrichWithChatSessions(requests);

        java.util.Map<String, Integer> invoked = new java.util.TreeMap<>();
        for (CopilotRequest r : requests) {
            if (r.toolsUsed() == null) continue;
            for (String t : r.toolsUsed()) invoked.merge(t, 1, Integer::sum);
        }

        List<McpScanner.McpServer> servers = new McpScanner().scanAll();
        System.out.println("Configured MCP servers");
        System.out.println("-----------------------");
        if (servers.isEmpty()) {
            System.out.println("  (none configured)");
            System.out.println();
            System.out.println("Add MCP server entries to one of:");
            System.out.println("  ~/.vscode/mcp.json");
            System.out.println("  %APPDATA%\\Code\\User\\mcp.json");
            System.out.println("  <project>/.vscode/mcp.json");
            return;
        }
        for (McpScanner.McpServer s : servers) {
            int count = mcpInvokeCount(s.name(), invoked);
            String suffix = count == 0 ? "(unused)" : "invoked " + count + "x";
            String src = s.sourceFile() != null ? s.sourceFile().getFileName().toString() : "?";
            System.out.printf("  %-20s %-30s %s%n", s.name(), src, suffix);
        }
    }

    /**
     * Fuzzy match an MCP config name against a set of used tool names.
     * Case-insensitive, strip {@code -_ } so {@code bitbucket-server}
     * matches {@code bitbucketserver} or vice versa.
     */
    private static int mcpInvokeCount(String cfg, java.util.Map<String, Integer> used) {
        String cfgKey = cfg.toLowerCase(java.util.Locale.ROOT).replaceAll("[-_\\s]", "");
        int total = 0;
        for (var e : used.entrySet()) {
            String uKey = e.getKey().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[-_\\s]", "");
            if (uKey.contains(cfgKey) || cfgKey.contains(uKey)) {
                total += e.getValue();
            }
        }
        return total;
    }

    /**
     * P2.2: substring search across prompt text, response text, and summary.
     * Cheap scan over already-collected fields; no index.
     */
    static void runSearch(Args params) throws Exception {
        if (params.query == null || params.query.isBlank()) {
            System.err.println("Usage: copilot-lens search \"<query>\" [--limit=N] [--ide=...]");
            System.exit(1);
        }

        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parseWithCache(
                new IncrementalState(), log, parser, params);
        requests = enrichWithChatSessions(requests);

        List<io.copilotlens.analyzer.SearchIndex.SearchHit> hits =
                new io.copilotlens.analyzer.SearchIndex().search(requests, params.query, params.limit);

        if (hits.isEmpty()) {
            System.out.println("No matches for \"" + params.query + "\".");
            boolean hasSession = requests.stream().anyMatch(CopilotRequest::hasSessionContent);
            if (!hasSession) {
                System.out.println();
                System.out.println("Note: only IDE logs were searched.");
                System.out.println("Set chatsession.enabled=true (Windows only)");
                System.out.println("to enable full-prompt searches.");
            }
            return;
        }
        System.out.printf("Found %d match(es) for \"%s\":%n%n", hits.size(), params.query);
        for (var h : hits) {
            System.out.printf("[%s] %s  %s%n",
                    h.request().timestamp(),
                    ideBadgeShort(h.request().ide()),
                    h.field());
            System.out.println("  ..." + highlight(h.snippet(), params.query) + "...");
            System.out.println();
        }
    }

    /** Short IDE badge (no padding) for inline use. */
    private static String ideBadgeShort(CopilotRequest.Ide ide) {
        return switch (ide) {
            case VSCODE   -> "VSCode";
            case INTELLIJ -> "IDEA";
            case CURSOR   -> "Cursor";
            case WINDSURF -> "Wndsrf";
        };
    }

    /** Highlight the query substring with ANSI yellow bold in {@code text}. */
    private static String highlight(String text, String query) {
        if (text == null || query == null || query.isEmpty()) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String q = query.toLowerCase(java.util.Locale.ROOT);
        int i = 0;
        while (i < text.length()) {
            int idx = lower.indexOf(q, i);
            if (idx < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, idx);
            out.append("[1;33m");
            out.append(text, idx, idx + q.length());
            out.append("[0m");
            i = idx + q.length();
        }
        return out.toString();
    }

    /**
     * P2.5: provider-API cost estimation. Aggregates by period like
     * {@code trend} but uses estimated cost instead of token totals.
     */
    static void runCost(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parseWithCache(
                new IncrementalState(), log, parser, params);
        requests = enrichWithChatSessions(requests);

        io.copilotlens.analyzer.CostEstimator estimator =
                new io.copilotlens.analyzer.CostEstimator();
        List<io.copilotlens.analyzer.CostEstimator.Cost> costs = estimator.estimate(requests);

        System.out.println("Disclaimer: GitHub Copilot bills on premium requests, not tokens.");
        System.out.println("This is what you'd pay calling the provider APIs directly.");
        System.out.println();

        String periodStr = params.period != null ? params.period : "daily";
        if ("weekly".equalsIgnoreCase(periodStr)) {
            printCostTable("weekly", estimator.aggregateWeekly(costs));
        } else if ("monthly".equalsIgnoreCase(periodStr)) {
            printCostTable("monthly", estimator.aggregateMonthly(costs));
        } else {
            printCostTable("daily", estimator.aggregateDaily(costs));
        }
    }

    private static void printCostTable(String periodName,
            List<io.copilotlens.analyzer.CostEstimator.CostBreakdown> rows) {
        System.out.println("Cost by " + periodName + " period:");
        System.out.printf("  %-12s  %12s  %12s  %12s%n",
                periodName, "in $", "out $", "total $");
        if (rows.isEmpty()) {
            System.out.println("  (no data)");
            return;
        }
        for (var b : rows) {
            System.out.printf(Locale.ROOT, "  %-12s  %12.4f  %12.4f  %12.4f%n",
                    b.label(), b.inputCost(), b.outputCost(), b.totalCost());
        }
    }

    static void runExport(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parser.parse(log);
        requests = enrichWithChatSessions(requests);

        String format = params.format != null ? params.format : "json";
        switch (format) {
            case "json" -> {
                Path output = Paths.get("copilot-lens-export.json");
                new JsonReporter().write(requests, output);
                System.out.println("Export written: " + output.toAbsolutePath());
            }
            case "sft" -> {
                if (requests.stream().noneMatch(CopilotRequest::hasSessionContent)) {
                    System.err.println("export sft needs prompt/response text.");
                    System.err.println("Enable chatsession.enabled=true in config.properties");
                    System.err.println("(chat-session reader is Windows-only).");
                    System.exit(1);
                }
                Path output = params.outPath != null
                        ? params.outPath
                        : Paths.get("copilot-lens-export.jsonl");
                int n = new io.copilotlens.analyzer.SftExporter(8_000)
                        .write(requests, output);
                System.out.println("Wrote " + n + " SFT examples to " + output.toAbsolutePath());
            }
            default -> {
                System.err.println("Unsupported format: " + format + " (use json|sft)");
                System.exit(1);
            }
        }
    }

    /**
     * Persist a Snapshot for today derived from the cache.
     * The Report aggregates ALL cached requests; we filter by date so each
     * daily snapshot is self-contained and additive across runs.
     */
    static void runSnapshot(Args params) throws Exception {
        IncrementalState state = new IncrementalState();
        // Force a cache-only read; we only need what was already parsed.
        Path log = params.logFile != null ? params.logFile : resolveLog(params);
        LogParser parser = createParser(log, params);

        List<CopilotRequest> cached = state.getAllCached();
        if (cached.isEmpty()) {
            // No cache: parse once so we have something to snapshot.
            cached = parser.parse(log);
            state.addRequests(cached);
            long size = Files.size(log);
            String normalized = log.toString().replace('\\', '/');
            BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class);
            state.recordParsed(normalized, size,
                    attrs.lastModifiedTime().toMillis(), cached.size());
        }

        LocalDate today = LocalDate.now();
        Snapshot s = Snapshot.forDate(today, cached);

        SnapshotStore store = new SnapshotStore();
        store.save(s);

        CliReporter cli = new CliReporter(!params.noAnsi);
        cli.printSnapshotConfirmation(s, store.dir());
    }

    static void runTrend(Args params) throws Exception {
        SnapshotStore store = new SnapshotStore();
        List<Snapshot> all = store.loadAll();
        if (all.isEmpty()) {
            System.out.println("No snapshots yet.");
            System.out.println("Run `copilot-lens snapshot` to record today's totals.");
            return;
        }

        Period period = TrendAggregator.parse(params.period);
        TrendAggregator agg = new TrendAggregator();
        List<TrendPoint> points = agg.aggregate(all, period);
        points = agg.limit(points, Math.max(1, params.days));

        CliReporter cli = new CliReporter(!params.noAnsi);
        cli.printTrend(points, period, all.size());
    }

    static Path resolveLog(Args params) {
        if (params.logFile != null) return params.logFile;

        IdeDetector detector = new IdeDetector();
        Optional<Path> detected;

        switch (params.ide) {
            case IDE_VSCODE -> detected = detector.findVsCodeLog();
            case IDE_INTELLIJ -> detected = detector.findIntelliJLog();
            case IDE_CURSOR -> detected = detector.findCursorLog();
            case IDE_WINDSURF -> detected = detector.findWindsurfLog();
            default -> detected = detector.findAnyLog();
        }

        return detected.orElseThrow(() -> {
            String msg = "Log file not found.\n";
            msg += "  Pass --log=<path> manually, then enable verbose log\n";
            msg += "  for the IDE you use. See docs/LOG_ACTIVATION.md for\n";
            msg += "  VSCode, IntelliJ, Windsurf, and Cursor setup steps.";
            return new RuntimeException(msg);
        });
    }

    /**
     * P0 step 9: VSCode chat-session reader integration. Walks
     * {@code %APPDATA%\Code\User\workspaceStorage\*\state.vscdb} to find
     * the chat session index, then parses each {@code chatSessions\<id>.{json,jsonl}}
     * file into one {@link CopilotRequest} per turn. Appended to the input
     * list and returned.
     *
     * <p>Gated by {@code chatsession.enabled=true} in config. Default OFF
     * so existing runs are unchanged. Returns the input list unchanged
     * when disabled or when no VSCode install is present.
     *
     * <p>All errors are swallowed: missing VSCode, corrupt SQLite, oversized
     * session files, malformed JSON, or missing gson/sqlite-jdbc on classpath
     * all result in a quiet skip. The flag is opt-in; we never break a run.
     */
    static List<CopilotRequest> enrichWithChatSessions(List<CopilotRequest> existing) {
        CopilotLensConfig cfg = CopilotLensConfig.load();
        if (!cfg.getBool("chatsession.enabled")) return existing;
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isEmpty()) {
            // Non-Windows hosts (macOS, Linux) don't set %APPDATA%.
            // Don't silently swallow: tell the user the feature ran but
            // found no session data, so they know to set it on Windows.
            DebugLog.warn("chatsession.enabled=true but APPDATA is unset "
                    + "(non-Windows host). Session data unavailable. "
                    + "Run on Windows or set APPDATA to your VSCode User dir.");
            return existing;
        }
        Path userRoot = Paths.get(appdata, "Code", "User");
        if (!Files.isDirectory(userRoot)) return existing;

        long maxBytes;
        try { maxBytes = Long.parseLong(cfg.get("chatsession.maxBytes")); }
        catch (NumberFormatException e) { maxBytes = 200_000_000L; }

        VsCodeSessionDb db = new VsCodeSessionDb();
        VsCodeSessionJson jsonParser = new VsCodeSessionJson(maxBytes);
        VsCodeSessionJsonl jsonlParser = new VsCodeSessionJsonl(maxBytes);

        List<CopilotRequest> added = new ArrayList<>();
        List<VsCodeSessionDb.WorkspaceSessions> workspaces;
        try { workspaces = db.loadAll(userRoot); }
        catch (NoClassDefFoundError | Exception e) {
            // sqlite-jdbc or gson missing — silently skip
            return existing;
        }

        int sessionsParsed = 0, sessionsSkipped = 0;
        for (var ws : workspaces) {
            Path chatSessionsDir = ws.stateDb().getParent().resolve("chatSessions");
            if (!Files.isDirectory(chatSessionsDir)) continue;
            for (var entry : ws.sessions()) {
                if (entry.isEmpty()) continue;
                String sessionId = entry.sessionId();
                Path jsonFile = chatSessionsDir.resolve(sessionId + ".json");
                Path jsonlFile = chatSessionsDir.resolve(sessionId + ".jsonl");
                Path sessionFile = Files.exists(jsonFile) ? jsonFile
                        : (Files.exists(jsonlFile) ? jsonlFile : null);
                if (sessionFile == null) {
                    sessionsSkipped++;
                    continue;
                }
                List<? extends Object> turns;
                try {
                    if (sessionFile.toString().endsWith(".json")) {
                        turns = jsonParser.parse(sessionFile, entry.title(), ws.workspaceHash());
                    } else {
                        turns = jsonlParser.parse(sessionFile, entry.title(), ws.workspaceHash());
                    }
                } catch (Exception e) {
                    sessionsSkipped++;
                    continue;
                }
                if (turns == null) {
                    sessionsSkipped++;
                    continue;
                }
                for (Object o : turns) {
                    if (o instanceof VsCodeSessionJson.SessionTurn jt) {
                        added.add(CopilotRequest.ofSession(
                                jt.timestamp(), CopilotRequest.Ide.VSCODE,
                                jt.sessionId(), jt.agentId(),
                                jt.promptText(), jt.responseText(),
                                jt.toolNames(), jt.title(),
                                ws.workspaceHash()));
                    } else if (o instanceof VsCodeSessionJsonl.SessionTurn lt) {
                        added.add(CopilotRequest.ofSession(
                                lt.timestamp(), CopilotRequest.Ide.VSCODE,
                                lt.sessionId(), lt.agentId(),
                                lt.promptText(), lt.responseText(),
                                lt.toolNames(), lt.title(),
                                ws.workspaceHash()));
                    }
                }
                sessionsParsed++;
            }
        }
        if (sessionsParsed > 0 || sessionsSkipped > 0) {
            System.err.println("[chatsession] parsed " + sessionsParsed +
                    " sessions, skipped " + sessionsSkipped);
        }

        List<CopilotRequest> merged = new ArrayList<>(existing.size() + added.size());
        merged.addAll(existing);
        merged.addAll(added);
        return merged;
    }

    static LogParser createParser(Path log, Args params) {
        TokenCounter counter = new TokenCounter();
        return switch (params.ide) {
            case IDE_INTELLIJ -> new IntelliJParser(counter);
            case IDE_CURSOR -> new VsCodeForkParser(counter, CopilotRequest.Ide.CURSOR);
            case IDE_WINDSURF -> new VsCodeForkParser(counter, CopilotRequest.Ide.WINDSURF);
            default -> new VsCodeParser(counter);
        };
    }

    static void printHelp() {
        String help = """
            copilot-lens - GitHub Copilot Token & Premium Analyzer

            Usage:
              copilot-lens [command] [options]

            Commands:
              (none)           One-shot report (console + HTML)
              gain             Usage summary
              gain --history   Daily history trend
              discover         Find most expensive usage patterns
              watch            Live monitoring (RTK 'watch' style)
              export json      JSON export
              report           Generate HTML report only
              snapshot         Persist today's totals to ~/.copilot-lens/snapshots/
              trend            ASCII trend chart from stored snapshots
              init             Write default ./config.properties (idempotent)
              install          Copy wrapper to ~/.local/bin and update PATH
              score            Print the 0-100 Effectiveness Score
              mcp              List configured MCP servers + invocation counts
              search "<q>"     Substring search over prompt/response text
              cost             Provider-API cost estimate (--period=...)
              export sft       OpenAI chat-format JSONL (Windows only)

            Options:
              --ide=vscode|idea|cursor|windsurf|auto   IDE selection (default: auto)
              --log=<path>             Manual log file
              --period=daily|weekly|monthly   Trend grouping (default: daily)
              --days=N                 How many recent buckets to show (default: 30)
              --limit=N                Max search hits (default: 20)
              --out=<path>             Output file (export sft)
              --no-ansi                Disable colored terminal output
              --help, -h               Show this help

            Examples:
              copilot-lens
              copilot-lens watch --ide=idea
              copilot-lens --ide=cursor
              copilot-lens --ide=windsurf
              copilot-lens discover
              copilot-lens gain --history
              copilot-lens snapshot
              copilot-lens trend --period=weekly --days=12
              copilot-lens trend --period=monthly
              copilot-lens export json
              copilot-lens score
              copilot-lens mcp
              copilot-lens search "auth" --limit=5
              copilot-lens cost --period=weekly
              copilot-lens export sft --out=demo.jsonl
              copilot-lens install
              copilot-lens --log=/path/to/custom.log

            Enable verbose log: see docs/LOG_ACTIVATION.md
            (covers VSCode, IntelliJ, Cursor, Windsurf)
            """;
        System.out.println(help);
    }
}
