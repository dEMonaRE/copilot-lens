package io.copilotlens.parser;

import io.copilotlens.analyzer.TokenCounter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IntelliJ IDEA GitHub Copilot plugin log formatını parse eder.
 * Format: düz metin + zaman damgası + copilot kategori.
 * Tipik log konumu: %LOCALAPPDATA%\JetBrains\IntelliJIdea*\log\idea.log
 */
public class IntelliJParser implements LogParser {

    // 2026-08-23 09:15:22,123 [1234567] INFO - copilot... gibi satırlar
    private static final Pattern TIMESTAMP = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}),\\d+");
    // copilot.request - POST /v1/chat/completions
    private static final Pattern REQUEST = Pattern.compile("copilot\\.(request|completion|chat)\\b.*?POST\\s+(/v\\d+/[\\w/-]+)");
    // usage: prompt_tokens=1500,completion_tokens=250
    private static final Pattern USAGE = Pattern.compile("prompt_tokens=(\\d+).*?completion_tokens=(\\d+)|completion_tokens=(\\d+).*?prompt_tokens=(\\d+)");

    private final TokenCounter counter;

    public IntelliJParser(TokenCounter counter) {
        this.counter = counter;
    }

    @Override
    public List<CopilotRequest> parse(Path logFile) throws Exception {
        List<CopilotRequest> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(logFile);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            LocalDateTime ts = extractTimestamp(line, fmt);
            if (ts == null) continue;

            // İstek satırı
            if (line.contains("copilot") && line.contains("POST")) {
                Matcher m = REQUEST.matcher(line);
                String endpoint = m.find() ? m.group(2) : "/v1/chat/completions";

                // Body sonraki birkaç satırda olabilir
                StringBuilder body = new StringBuilder(line);
                for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
                    body.append('\n').append(lines.get(j));
                }

                int tokens = counter.count(body.toString());
                result.add(new CopilotRequest(
                        ts, CopilotRequest.Ide.INTELLIJ,
                        endpoint, tokens, 0, 1, extractSummary(body.toString()), null));
            }

            // Usage satırı
            if (line.contains("usage")) {
                Matcher m = USAGE.matcher(line);
                if (m.find()) {
                    int pTok = parseGroup(m, 1, 4);
                    int cTok = parseGroup(m, 2, 3);

                    if (!result.isEmpty()) {
                        CopilotRequest last = result.get(result.size() - 1);
                        if (last.outputTokens() == 0) {
                            result.set(result.size() - 1,
                                    new CopilotRequest(last.timestamp(), last.ide(),
                                            last.endpoint(),
                                            pTok > 0 ? pTok : last.inputTokens(),
                                            cTok, last.messageCount(),
                                            last.summary(), last.workspaceHint()));
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public CopilotRequest.Ide ide() { return CopilotRequest.Ide.INTELLIJ; }

    private LocalDateTime extractTimestamp(String line, DateTimeFormatter fmt) {
        Matcher m = TIMESTAMP.matcher(line);
        if (!m.find()) return null;
        try {
            return LocalDateTime.parse(m.group(1), fmt);
        } catch (Exception e) {
            return null;
        }
    }

    private int parseGroup(Matcher m, int... groups) {
        for (int g : groups) {
            String val = m.group(g);
            if (val != null) return Integer.parseInt(val);
        }
        return 0;
    }

    private String extractSummary(String body) {
        Matcher m = Pattern.compile("content=([^,]{0,80})").matcher(body);
        return m.find() ? m.group(1).trim() : "(intellij request)";
    }
}
