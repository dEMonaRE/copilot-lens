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
            // Yeni mimaride token yok, aktivite proxy metrikleri:
            Map<String, Integer> modelDistribution,
            Map<String, Integer> providerDistribution,
            double avgLatencyMs,
            int latencySampleCount,
            // Token kaynak ayrımı (Format 1 usage vs BPE tahmini vs tokenless)
            int reportedRequestCount,
            int estimatedRequestCount,
            int noneTokenRequestCount
    ) {}

    public Report aggregate(List<CopilotRequest> requests) {
        if (requests.isEmpty()) {
            return new Report(0, 0, 0, 0, 0, 0, 0, List.of(), List.of(),
                    Map.of(), Map.of(), 0, 0,
                    Map.of(), Map.of(), 0, 0,
                    0, 0, 0);
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

        // Token kaynak ayrımı (REPORTED/ESTIMATED/NONE)
        int reported = 0, estimated = 0, noneTok = 0;
        for (CopilotRequest r : requests) {
            switch (r.tokenSource() == null ? CopilotRequest.TokenSource.NONE : r.tokenSource()) {
                case REPORTED -> reported++;
                case ESTIMATED -> estimated++;
                case NONE -> noneTok++;
            }
        }

        return new Report(
                count, totalIn, totalOut,
                (double) totalIn / count,
                (double) totalOut / count,
                maxIn, maxOut, largest, requests, hourly, daily,
                firstMs, lastMs,
                modelDist, providerDist, avgLatency, latencyCount,
                reported, estimated, noneTok);
    }
}
