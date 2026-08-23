package io.copilotlens;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI argüman parsing. Komutlar ve flag'ler burada tanımlı.
 * RTK uyumlu isimlendirme: gain, gain --history, discover, proxy, watch.
 */
public class Args {

    public enum Command { LOG, WATCH, GAIN, DISCOVER, EXPORT, REPORT, INIT }
    public enum Ide { IDE_AUTO, IDE_VSCODE, IDE_INTELLIJ }

    public Command command = Command.LOG;
    public Ide ide = Ide.IDE_AUTO;
    public Path logFile;
    public boolean noAnsi = false;
    public boolean help = false;
    public boolean history = false;
    public String format;

    public static Args parse(String[] argv) {
        Args a = new Args();

        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            switch (arg) {
                case "watch" -> a.command = Command.WATCH;
                case "gain" -> a.command = Command.GAIN;
                case "discover" -> a.command = Command.DISCOVER;
                case "init" -> a.command = Command.INIT;
                case "export" -> {
                    a.command = Command.EXPORT;
                    if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                        a.format = argv[++i];
                    }
                }
                case "report" -> a.command = Command.REPORT;
                case "--no-ansi" -> a.noAnsi = true;
                case "--help", "-h" -> a.help = true;
                case "--history" -> a.history = true;
                default -> {
                    if (arg.startsWith("--ide=")) {
                        String val = arg.substring(6).toLowerCase();
                        a.ide = switch (val) {
                            case "vscode", "vsc", "code" -> Ide.IDE_VSCODE;
                            case "idea", "intellij", "jetbrains" -> Ide.IDE_INTELLIJ;
                            default -> Ide.IDE_AUTO;
                        };
                    } else if (arg.startsWith("--log=")) {
                        a.logFile = Paths.get(arg.substring(6));
                    }
                }
            }
        }
        return a;
    }
}
