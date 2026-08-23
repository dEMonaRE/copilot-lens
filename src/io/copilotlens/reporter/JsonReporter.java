package io.copilotlens.reporter;

import io.copilotlens.analyzer.StatsAggregator;
import io.copilotlens.analyzer.StatsAggregator.Report;
import io.copilotlens.parser.CopilotRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JSON export. Pipe-friendly format, başka araçlarla (jq, vb.) kullanılabilir.
 * RTK'nin proxy moduna benzer: ham veriyi dışarı verir.
 */
public class JsonReporter {

    public void write(List<CopilotRequest> requests, Path output) throws Exception {
        Report report = new StatsAggregator().aggregate(requests);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"generatedAt\": \"").append(java.time.LocalDateTime.now()).append("\",\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"requestCount\": ").append(report.requestCount()).append(",\n");
        sb.append("    \"totalInputTokens\": ").append(report.totalInputTokens()).append(",\n");
        sb.append("    \"totalOutputTokens\": ").append(report.totalOutputTokens()).append(",\n");
        sb.append("    \"avgInputTokens\": ").append((int) report.avgInputTokens()).append(",\n");
        sb.append("    \"maxInputTokens\": ").append(report.maxInputTokens()).append(",\n");
        sb.append("    \"maxOutputTokens\": ").append(report.maxOutputTokens()).append("\n");
        sb.append("  },\n");
        sb.append("  \"requests\": [\n");

        for (int i = 0; i < requests.size(); i++) {
            CopilotRequest r = requests.get(i);
            sb.append("    {\n");
            sb.append("      \"timestamp\": \"").append(r.timestamp()).append("\",\n");
            sb.append("      \"ide\": \"").append(r.ide()).append("\",\n");
            sb.append("      \"endpoint\": \"").append(r.endpoint()).append("\",\n");
            sb.append("      \"inputTokens\": ").append(r.inputTokens()).append(",\n");
            sb.append("      \"outputTokens\": ").append(r.outputTokens()).append(",\n");
            sb.append("      \"messageCount\": ").append(r.messageCount()).append(",\n");
            sb.append("      \"summary\": ").append(jsonString(r.summary())).append(",\n");
            sb.append("      \"workspaceHint\": ").append(jsonString(r.workspaceHint())).append("\n");
            sb.append("    }").append(i < requests.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        Files.writeString(output, sb.toString());
    }

    private String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
