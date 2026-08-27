package io.copilotlens.parser;

import io.copilotlens.analyzer.TokenCounter;

/**
 * Cursor ve Windsurf gibi VSCode-fork IDE'lerin loglarını parse eder.
 *
 * VsCodeParser ile aynı regex/log mantığını paylaşır; tek fark üretilen
 * kayıtların {@link CopilotRequest.Ide} etiketidir. Construction sırasında
 * Ide zorunlu olarak verilir (VSCODE burada anlamlı değildir).
 *
 * Cascade AI'nın kendi iç logu (Windsurf (Lifeguard).log) serbest biçimli
 * olduğu için parse edilmez; sadece VSCode extension-host logundaki
 * {@code [fetchCompletions]} / {@code ccreq} / usage satırları kullanılır.
 */
public class VsCodeForkParser extends VsCodeParser {

    public VsCodeForkParser(TokenCounter counter, CopilotRequest.Ide ideTag) {
        super(counter, ideTag);
    }
}