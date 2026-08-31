package io.copilotlens.analyzer;

import io.copilotlens.parser.CopilotRequest;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Parse edilmiş Copilot isteklerini özet istatistiklere dönüştürür.
 * gain --history ve gain komutları için temel veri kaynağı.
 */
public class StatsAggregator {

    public record Report(
            int requestCount,
            int totalInputTokens,
            int totalOutputTokens,
            double avgInputTokens,
            double avgOutputTokens,
            int maxInputTokens,
            int maxOutputTokens,
            List<CopilotRequest> largestRequests,
            List<CopilotRequest> allRequests,
            Map<String, Integer> hourlyDistribution,
            Map<LocalDate, Integer> dailyDistribution,
            long firstTimestampMs,
            long lastTimestampMs,
            // P1.4: gap-capped active duration (ms). Sum of inter-event gaps,
            // each capped at 5 min so paused sessions aren't inflated.
            long activeDurationMs,
            // Yeni mimaride token yok, aktivite proxy metrikleri:
            Map<String, Integer> modelDistribution,
            Map<String, Integer> providerDistribution,
            double avgLatencyMs,
            int latencySampleCount,
            // Token kaynak ayrımı (Format 1 usage vs BPE tahmini vs heuristic vs tokenless)
            int reportedRequestCount,
            int estimatedRequestCount,
            int estimatedHeuristicRequestCount,
            int noneTokenRequestCount
    ) {}

    /** P1.4: cap any single inter-event gap at this many milliseconds. */
    private static final long MAX_GAP_MS = 5L * 60L * 1000L;

    public Report aggregate(List<CopilotRequest> requests) {
        if (requests.isEmpty()) {
            return new Report(0, 0, 0, 0, 0, 0, 0, List.of(), List.of(),
                    Map.of(), Map.of(), 0, 0, 0,
                    Map.of(), Map.of(), 0, 0,
                    0, 0, 0, 0);
        }

        int count = requests.size();
        int totalIn = requests.stream().mapToInt(CopilotRequest::inputTokens).sum();
        int totalOut = requests.stream().mapToInt(CopilotRequest::outputTokens).sum();
        int maxIn = requests.stream().mapToInt(CopilotRequest::inputTokens).max().orElse(0);
        int maxOut = requests.stream().mapToInt(CopilotRequest::outputTokens).max().orElse(0);

        List<CopilotRequest> largest = requests.stream()
                .sorted(Comparator.comparingInt(CopilotRequest::inputTokens).reversed())
                .limit(10)
                .collect(Collectors.toList());

        Map<String, Integer> hourly = new TreeMap<>();
        Map<LocalDate, Integer> daily = new TreeMap<>();
        for (CopilotRequest r : requests) {
            String hourKey = r.timestamp().getHour() + ":00";
            hourly.merge(hourKey, 1, Integer::sum);

            LocalDate day = r.timestamp().toLocalDate();
            daily.merge(day, 1, Integer::sum);
        }

        long firstMs = requests.get(0).timestamp()
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long lastMs = requests.get(requests.size() - 1).timestamp()
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

        // P1.4: gap-capped active duration. Sort timestamps ascending, sum
        // each inter-event delta, capping any single gap at MAX_GAP_MS so
        // a paused session isn't counted as hours of activity.
        long activeDurationMs = 0;
        if (requests.size() >= 2) {
            long[] ts = new long[requests.size()];
            for (int i = 0; i < requests.size(); i++) {
                ts[i] = requests.get(i).timestamp()
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            java.util.Arrays.sort(ts);
            for (int i = 1; i < ts.length; i++) {
                long gap = ts[i] - ts[i - 1];
                if (gap < 0) gap = 0;
                if (gap > MAX_GAP_MS) gap = MAX_GAP_MS;
                activeDurationMs += gap;
            }
        }

        // Aktivite proxy metrikleri (yeni mimari için)
        Map<String, Integer> modelDist = new TreeMap<>();
        Map<String, Integer> providerDist = new TreeMap<>();
        long latencySum = 0;
        int latencyCount = 0;
        for (CopilotRequest r : requests) {
            if (r.model() != null && !r.model().isEmpty()) {
                modelDist.merge(r.model(), 1, Integer::sum);
            }
            if (r.provider() != null && !r.provider().isEmpty()) {
                providerDist.merge(r.provider(), 1, Integer::sum);
            }
            if (r.latencyMs() != null) {
                latencySum += r.latencyMs();
                latencyCount++;
            }
        }
        double avgLatency = latencyCount > 0 ? (double) latencySum / latencyCount : 0;

        // Token kaynak ayrımı (REPORTED / ESTIMATED / ESTIMATED_HEURISTIC / NONE)
        int reported = 0, estimated = 0, heuristic = 0, noneTok = 0;
        for (CopilotRequest r : requests) {
            switch (r.tokenSource() == null ? CopilotRequest.TokenSource.NONE : r.tokenSource()) {
                case REPORTED -> reported++;
                case ESTIMATED -> estimated++;
                case ESTIMATED_HEURISTIC -> heuristic++;
                case NONE -> noneTok++;
            }
        }

        return new Report(
                count, totalIn, totalOut,
                (double) totalIn / count,
                (double) totalOut / count,
                maxIn, maxOut, largest, requests, hourly, daily,
                firstMs, lastMs, activeDurationMs,
                modelDist, providerDist, avgLatency, latencyCount,
                reported, estimated, heuristic, noneTok);
    }
}
