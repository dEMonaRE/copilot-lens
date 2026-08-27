package io.copilotlens.parser;

import java.time.LocalDateTime;

/**
 * Bir Copilot API çağrısının parse edilmiş hali.
 * VSCode, IntelliJ, Cursor ve Windsurf parser'ları bu modele dönüştürür.
 *
 * Token alanları eski/standart format için. Yeni IDE mimarilerinde
 * (VSCode Copilot Chat 0.60+, IntelliJ 2025+) token sayıları log'a
 * düşmediği için inputTokens=outputTokens=0 olabilir — bu durumda
 * model/provider/latencyMs alanları aktivite metriği olarak kullanılır.
 *
 * {@link TokenSource} her kayıt için token sayılarının nereden geldiğini
 * belirtir: log-reported (usage satırı / JSON-RPC data), BPE-estimated
 * (yerel tiktoken sayımı) veya none (hiç token yok).
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
        Integer latencyMs,
        TokenSource tokenSource
) {
    public enum Ide { VSCODE, INTELLIJ, CURSOR, WINDSURF }

    /**
     * Token sayısının nereden geldiğini belirtir.
     *  - REPORTED: log'daki "usage"/JSON-RPC "data" alanından
     *  - ESTIMATED: BPE (jtokkit) ile yerel hesaplama
     *  - NONE: token bilgisi yok (eski Format 2 davranışı)
     */
    public enum TokenSource { REPORTED, ESTIMATED, NONE }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean isComplete() {
        return outputTokens > 0;
    }

    /**
     * Backward-compatible factory: token-counted requests.
     * Default tokenSource = ESTIMATED; çağıran kod gerekirse
     * {@link #ofReported} ile overwrite edebilir.
     */
    public static CopilotRequest of(LocalDateTime ts, Ide ide, String endpoint,
                                     int inTok, int outTok, int msgs,
                                     String summary, String workspace) {
        return new CopilotRequest(ts, ide, endpoint, inTok, outTok, msgs,
                summary, workspace, null, null, null, TokenSource.ESTIMATED);
    }

    /**
     * Log'dan gelen usage satırıyla overwrite edilmiş kayıt.
     */
    public static CopilotRequest ofReported(LocalDateTime ts, Ide ide, String endpoint,
                                             int inTok, int outTok, int msgs,
                                             String summary, String workspace) {
        return new CopilotRequest(ts, ide, endpoint, inTok, outTok, msgs,
                summary, workspace, null, null, null, TokenSource.REPORTED);
    }

    /**
     * Look-ahead ile bulunan body'nin BPE sayımı (Format 2 fallback).
     * Model/provider/latencyMs taşır; token sayısı yereldir.
     */
    public static CopilotRequest ofEstimated(LocalDateTime ts, Ide ide, String endpoint,
                                              int inTok, int outTok, int msgs,
                                              String summary, String workspace,
                                              String model, String provider,
                                              Integer latencyMs) {
        return new CopilotRequest(ts, ide, endpoint, inTok, outTok, msgs,
                summary, workspace, model, provider, latencyMs, TokenSource.ESTIMATED);
    }

    /**
     * Backward-compatible factory: activity proxy (no tokens, but model+provider+latency).
     */
    public static CopilotRequest proxy(LocalDateTime ts, Ide ide, String endpoint,
                                       String model, String provider, Integer latencyMs,
                                       String summary) {
        return new CopilotRequest(ts, ide, endpoint, 0, 0, 1,
                summary, null, model, provider, latencyMs, TokenSource.NONE);
    }
}