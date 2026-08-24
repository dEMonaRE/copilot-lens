package io.copilotlens.parser;

import java.time.LocalDateTime;

/**
 * Bir Copilot API çağrısının parse edilmiş hali.
 * VSCode ve IntelliJ parser'ları bu modele dönüştürür.
 *
 * Token alanları eski/standart format için. Yeni IDE mimarilerinde
 * (VSCode Copilot Chat 0.60+, IntelliJ 2025+) token sayıları log'a
 * düşmediği için inputTokens=outputTokens=0 olabilir — bu durumda
 * model/provider/latencyMs alanları aktivite metriği olarak kullanılır.
 */
public record CopilotRequest(
        LocalDateTime timestamp,
        Ide ide,
        String endpoint,
        int inputTokens,
        int outputTokens,
        int messageCount,
        String summary,
        String workspaceHint,
        String model,
        String provider,
        Integer latencyMs
) {
    public enum Ide { VSCODE, INTELLIJ }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean isComplete() {
        return outputTokens > 0;
    }

    /** Backward-compatible factory: token-counted requests (legacy fields only). */
    public static CopilotRequest of(LocalDateTime ts, Ide ide, String endpoint,
                                     int inTok, int outTok, int msgs,
                                     String summary, String workspace) {
        return new CopilotRequest(ts, ide, endpoint, inTok, outTok, msgs,
                summary, workspace, null, null, null);
    }

    /** Backward-compatible factory: activity proxy (no tokens, but model+provider+latency). */
    public static CopilotRequest proxy(LocalDateTime ts, Ide ide, String endpoint,
                                       String model, String provider, Integer latencyMs,
                                       String summary) {
        return new CopilotRequest(ts, ide, endpoint, 0, 0, 1,
                summary, null, model, provider, latencyMs);
    }
}
