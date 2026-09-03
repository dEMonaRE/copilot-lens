package io.copilotlens.parser;

import java.time.LocalDateTime;
import java.util.List;

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
 *
 * P0 (VSCode chat-session reader) ek alanlar ekler:
 * sessionId, agent, promptText, responseText, toolsUsed. Hepsi nullable —
 * log-tabanlı parser'lar için null kalır, geriye uyumlu.
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
        TokenSource tokenSource,
        // ---- P0 alanları (nullable) ----
        String sessionId,
        String agent,
        String promptText,
        String responseText,
        List<String> toolsUsed
) {
    public enum Ide { VSCODE, INTELLIJ, CURSOR, WINDSURF }

    /**
     * Token sayısının nereden geldiğini belirtir.
     *  - REPORTED: log'daki "usage"/JSON-RPC "data" alanından
     *  - ESTIMATED: BPE (jtokkit) ile yerel hesaplama — request body
     *    look-ahead ile bulundu, model-aware encoding seçildi
     *  - ESTIMATED_HEURISTIC: body bulunamadı, summary/endpoint metni
     *    üzerinden karakter tabanlı heuristic kullanıldı (düşük güven)
     *  - NONE: token bilgisi yok (log kaynağında token yok, tahmin de
     *    yapılamadı — yeni VSCode log formatında output hep buraya düşer)
     */
    public enum TokenSource { REPORTED, ESTIMATED, ESTIMATED_HEURISTIC, NONE }

    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    public boolean isComplete() {
        return outputTokens > 0;
    }

    /** Convenience for P0 callers: any of the rich-session fields populated? */
    public boolean hasSessionContent() {
        return (sessionId != null && !sessionId.isEmpty())
                || (promptText != null && !promptText.isEmpty())
                || (responseText != null && !responseText.isEmpty())
                || (toolsUsed != null && !toolsUsed.isEmpty());
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
                summary, workspace, null, null, null, TokenSource.ESTIMATED,
                null, null, null, null, null);
    }

    /**
     * Log'dan gelen usage satırıyla overwrite edilmiş kayıt.
     */
    public static CopilotRequest ofReported(LocalDateTime ts, Ide ide, String endpoint,
                                             int inTok, int outTok, int msgs,
                                             String summary, String workspace) {
        return new CopilotRequest(ts, ide, endpoint, inTok, outTok, msgs,
                summary, workspace, null, null, null, TokenSource.REPORTED,
                null, null, null, null, null);
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
                summary, workspace, model, provider, latencyMs, TokenSource.ESTIMATED,
                null, null, null, null, null);
    }

    /**
     * Body bulunamadığında summary veya URL'den yapılan heuristic tahmin.
     * Düşük güvenilirlik: kod/prose ayrımı yapılamadığı için sapma yüksek.
     */
    public static CopilotRequest ofEstimatedHeuristic(LocalDateTime ts, Ide ide, String endpoint,
                                                       int inTok, int outTok, int msgs,
                                                       String summary, String workspace,
                                                       String model, String provider,
                                                       Integer latencyMs) {
        return new CopilotRequest(ts, ide, endpoint, inTok, outTok, msgs,
                summary, workspace, model, provider, latencyMs,
                TokenSource.ESTIMATED_HEURISTIC,
                null, null, null, null, null);
    }

    /**
     * Backward-compatible factory: activity proxy (no tokens, but model+provider+latency).
     */
    public static CopilotRequest proxy(LocalDateTime ts, Ide ide, String endpoint,
                                       String model, String provider, Integer latencyMs,
                                       String summary) {
        return new CopilotRequest(ts, ide, endpoint, 0, 0, 1,
                summary, null, model, provider, latencyMs, TokenSource.NONE,
                null, null, null, null, null);
    }

    /**
     * P0 factory: VSCode chat-session turn. Token counts are unknown
     * (the JSON-RPC body isn't logged), so tokenSource is NONE and the
     * rich prompt/response/tool fields carry the actual content.
     *
     * If a {@code tokenCounter} is supplied, BPE counts are computed
     * from the prompt text so the rest of the report (totals, top-N)
     * stays consistent. Pass {@code null} to leave inputTokens=0.
     */
    public static CopilotRequest ofSession(LocalDateTime ts, Ide ide,
                                           String sessionId, String agent,
                                           String promptText, String responseText,
                                           List<String> toolsUsed,
                                           String title) {
        return new CopilotRequest(ts, ide, "vscode/chat/session", 0, 0,
                toolsUsed == null ? 0 : toolsUsed.size(),
                title == null ? promptText : title,
                null, null, null, null, TokenSource.NONE,
                sessionId, agent, promptText, responseText, toolsUsed);
    }
}
