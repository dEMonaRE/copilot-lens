package io.copilotlens.watch;

import io.copilotlens.analyzer.StatsAggregator;
import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.parser.CopilotRequest;
import io.copilotlens.parser.LogParser;
import io.copilotlens.reporter.CliReporter;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Canlı izleme modu. Log dosyasını periyodik kontrol eder,
 * yeni gelen satırları parse edip dashboard'u tazeler.
 * RTK'nin `watch` komutuna eşdeğer — sıkıştırılmış, canlı output.
 */
public class LogWatcher {

    private final Path logFile;
    private final LogParser parser;
    private final CliReporter reporter;
    private long lastPosition;
    private final List<CopilotRequest> sessionBuffer = new ArrayList<>();

    public LogWatcher(Path logFile, LogParser parser, CliReporter reporter) throws Exception {
        this.logFile = logFile;
        this.parser = parser;
        this.reporter = reporter;
        this.lastPosition = Files.size(logFile);
    }

    public void watch() throws Exception {
        System.out.println("👀 Live monitoring: " + logFile);
        System.out.println("Press Ctrl+C to exit\n");

        // Mevcut dosyayı baseline al; yeni gelenler işlenir
        long startTime = System.currentTimeMillis();

        while (true) {
            try {
                long currentSize = Files.size(logFile);

                if (currentSize < lastPosition) {
                    // Log rotated/truncated — sıfırla
                    lastPosition = 0;
                    sessionBuffer.clear();
                }

                if (currentSize > lastPosition) {
                    List<CopilotRequest> newRequests = readNewRequests();
                    if (!newRequests.isEmpty()) {
                        sessionBuffer.addAll(newRequests);
                        refresh(sessionBuffer);
                    }
                }
            } catch (Exception e) {
                System.err.println("Read error: " + e.getMessage());
            }

            TimeUnit.MILLISECONDS.sleep(500);
        }
    }

    private List<CopilotRequest> readNewRequests() throws Exception {
        long size = Files.size(logFile) - lastPosition;
        if (size <= 0) return List.of();

        try (FileChannel channel = FileChannel.open(logFile, StandardOpenOption.READ)) {
            channel.position(lastPosition);
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            channel.read(buf);
            String content = new String(buf.array());

            Path tempFile = Files.createTempFile("copilot-lens-watch-", ".log");
            Files.writeString(tempFile, content);
            try {
                List<CopilotRequest> result = parser.parse(tempFile);
                lastPosition = Files.size(logFile);
                return result;
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private void refresh(List<CopilotRequest> requests) {
        Report report = new StatsAggregator().aggregate(requests);
        // Ekranı temizle ve yeniden yaz
        System.out.print("[2J[H");
        reporter.print(report);
    }
}
