package io.copilotlens.parser;

import java.time.LocalDateTime;

/**
 * Bir Copilot API çağrısının parse edilmiş hali.
 * VSCode ve IntelliJ parser'ları bu modele dönüştürür.
 */
public record CopilotRequest(
        LocalDateTime timestamp,
        Ide ide,
        String endpoint,
        int inputTokens,
        int outputTokens,
        int messageCount,
        String summary,
        String workspaceHint
) {
    public enum Ide { VSCODE, INTELLIJ }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean isComplete() {
        return outputTokens > 0;
    }
}
