package io.copilotlens;

import io.copilotlens.analyzer.Discoverer;
import io.copilotlens.analyzer.Discoverer.Finding;
import io.copilotlens.analyzer.IncrementalState;
import io.copilotlens.analyzer.StatsAggregator;
import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.analyzer.TokenCounter;
import io.copilotlens.config.CopilotLensConfig;
import io.copilotlens.detector.IdeDetector;
import io.copilotlens.parser.CopilotRequest;
import io.copilotlens.parser.IntelliJParser;
import io.copilotlens.parser.LogParser;
import io.copilotlens.parser.VsCodeParser;
import io.copilotlens.reporter.CliReporter;
import io.copilotlens.reporter.HtmlReporter;
import io.copilotlens.reporter.JsonReporter;
import io.copilotlens.watch.LogWatcher;

import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
 *
 * Options:
 *   --ide=vscode|idea|auto   IDE selection (default: auto)
 *   --log=<path>             Manual log file
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

            switch (params.command) {
                case LOG, GAIN, REPORT -> runReport(params);
                case WATCH -> runWatch(params);
                case DISCOVER -> runDiscover(params);
                case EXPORT -> runExport(params);
                case INIT -> runInit(params);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (Boolean.getBoolean("copilot-lens.debug")) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    static void runReport(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        IncrementalState state = new IncrementalState();

        List<CopilotRequest> requests = parseWithCache(state, log, parser, params);

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
        Path configFile = CopilotLensConfig.getHomeDir().resolve("config.properties");

        // Idempotent: skip if already exists to avoid triggering Windows file
        // association prompts when the .properties file gets recreated.
        if (Files.exists(configFile)) {
            System.out.println("Config already exists: " + configFile);
            System.out.println("Edit it manually to change IDE log paths or tool settings.");
            System.out.println("Delete it first if you want to regenerate with new defaults.");
            return;
        }

        CopilotLensConfig.writeDefault();
        System.out.println("Default config written: " + configFile);
        System.out.println("Edit to override IDE log paths and tool settings.");
    }

    static void runDiscover(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parser.parse(log);

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
            System.out.printf("%n[%d] %s%n", i + 1, f.title());
            System.out.printf("    %s%n", f.detail());
            System.out.printf("    Severity: %.1f%n", f.severity());
        }
        System.out.println();
        System.out.println("=".repeat(64));
        System.out.println("Suggestions:");
        System.out.println("  - Reduce number of open files in IDE");
        System.out.println("  - Close .md files (agents.md, claude.md, business docs)");
        System.out.println("  - Prefer inline suggestions over Chat");
        System.out.println("  - Use #selection in custom commands, don't paste whole files");
    }

    static void runExport(Args params) throws Exception {
        Path log = resolveLog(params);
        LogParser parser = createParser(log, params);
        List<CopilotRequest> requests = parser.parse(log);

        String format = params.format != null ? params.format : "json";
        if (!format.equals("json")) {
            System.err.println("Only 'json' format is currently supported.");
            System.exit(1);
        }

        Path output = Paths.get("copilot-lens-export.json");
        new JsonReporter().write(requests, output);
        System.out.println("Export written: " + output.toAbsolutePath());
    }

    static Path resolveLog(Args params) {
        if (params.logFile != null) return params.logFile;

        IdeDetector detector = new IdeDetector();
        Optional<Path> detected;

        switch (params.ide) {
            case IDE_VSCODE -> detected = detector.findVsCodeLog();
            case IDE_INTELLIJ -> detected = detector.findIntelliJLog();
            default -> detected = detector.findAnyLog();
        }

        return detected.orElseThrow(() -> {
            String msg = "Log file not found.\n";
            msg += "  Pass --log=<path> manually, or enable verbose log:\n";
            msg += "  - VSCode: F1 -> 'Developer: Set Log Level' -> 'GitHub Copilot Chat: Trace'\n";
            msg += "  - IntelliJ: Help -> Diagnostic Tools -> Debug Log Settings\n";
            msg += "              -> Add: #com.github.copilot:trace";
            return new RuntimeException(msg);
        });
    }

    static LogParser createParser(Path log, Args params) {
        TokenCounter counter = new TokenCounter();
        String name = log.getFileName().toString().toLowerCase();
        if (params.ide == Args.Ide.IDE_INTELLIJ || name.contains("idea")) {
            return new IntelliJParser(counter);
        }
        return new VsCodeParser(counter);
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

            Options:
              --ide=vscode|idea|auto   IDE selection (default: auto)
              --log=<path>             Manual log file
              --no-ansi                Disable colored terminal output
              --help, -h               Show this help

            Examples:
              copilot-lens
              copilot-lens watch --ide=idea
              copilot-lens discover
              copilot-lens gain --history
              copilot-lens export json
              copilot-lens --log=/path/to/custom.log

            Enable log generation:
              VSCode  : F1 -> Developer: Set Log Level -> GitHub Copilot Chat: Trace
              IntelliJ: Help -> Diagnostic Tools -> Debug Log Settings -> #com.github.copilot:trace
            """;
        System.out.println(help);
    }
}
