package io.copilotlens.analyzer;

import io.copilotlens.parser.CopilotRequest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RTK'nin `discover` komutuna eşdeğer: copilot kullanımında "fırsatları" bulur.
 * - En çok token yiyen context dosyaları
 * - Çok uzun chat oturumları
 * - Yanıtı kısa olan ama prompt'u şişman olan istekler (kötü signal/noise)
 */
public class Discoverer {

    public record Finding(String title, String detail, double severity) {}

    public List<Finding> analyze(List<CopilotRequest> requests) {
        List<Finding> findings = new ArrayList<>();
        if (requests.isEmpty()) return findings;

        // 1) En şişman tek istek
        Optional<CopilotRequest> biggest = requests.stream()
                .max(Comparator.comparingInt(CopilotRequest::inputTokens));
        biggest.ifPresent(r -> findings.add(new Finding(
                "En şişman tek istek",
                String.format("%s — %,d input token (bu oturumdaki max). " +
                              "Context'i daraltmayı düşün.", r.timestamp(), r.inputTokens()),
                r.inputTokens() / 1000.0)));

        // 2) Workspace dosyası en çok tekrar eden
        Map<String, Integer> workspaceFreq = requests.stream()
                .map(CopilotRequest::workspaceHint)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(w -> w, Collectors.summingInt(w -> 1)));
        workspaceFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> findings.add(new Finding(
                        "En sık context'e giren dosya",
                        String.format("%s — %d istekte context'te. " +
                                      "Bu dosya şişman olabilir, IDE'de kapatmayı dene.", e.getKey(), e.getValue()),
                        e.getValue() * 1.0)));

        // 3) Signal/noise oranı düşük istekler (büyük input, küçük output)
        long lowSignal = requests.stream()
                .filter(r -> r.isComplete() && r.inputTokens() > 2000 && r.outputTokens() < 100)
                .count();
        if (lowSignal > 0) {
            findings.add(new Finding(
                    "Signal/noise oranı düşük istekler",
                    String.format("%d istek: >2k input token ama <100 output. " +
                                  "Bu prompt'lar context şişiriyor olabilir, " +
                                  "daha dar prompt'lar yaz.", lowSignal),
                    lowSignal * 2.0));
        }

        // 4) Ortalama input şişiyorsa
        double avg = requests.stream().mapToInt(CopilotRequest::inputTokens).average().orElse(0);
        if (avg > 3000) {
            findings.add(new Finding(
                    "Ortalama prompt boyutu yüksek",
                    String.format("%,.0f token/istek ortalama. " +
                                  "IDE'de açık dosya sayısını azalt, .md dosyalarını kapat.", avg),
                    avg / 500.0));
        }

        // 5) Yoğun saatler
        Map<Integer, Long> hourly = requests.stream()
                .collect(Collectors.groupingBy(r -> r.timestamp().getHour(), Collectors.counting()));
        hourly.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> findings.add(new Finding(
                        "En yoğun saat dilimi",
                        String.format("%02d:00 — %d istek. Günün bu saatlerinde yoğun kullanım.",
                                      e.getKey(), e.getValue()),
                        e.getValue() * 0.5)));

        // Severity'ye göre sırala
        findings.sort(Comparator.comparingDouble(Finding::severity).reversed());
        return findings;
    }
}
