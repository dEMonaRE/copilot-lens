package io.copilotlens.parser;

import java.nio.file.Path;
import java.util.List;

/**
 * IDE log dosyalarını parse eden strateji arayüzü.
 * Her IDE'nin kendi implementasyonu farklı formatı okur.
 */
public interface LogParser {
    List<CopilotRequest> parse(Path logFile) throws Exception;
    CopilotRequest.Ide ide();
}
