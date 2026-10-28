package nep.timeline.cirno.memory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * 读取内核 PSI (Pressure Stall Information): {@code /proc/pressure/memory}
 *
 * <p>格式:
 * <pre>
 * some avg10=0.00 avg60=0.00 avg300=0.00 total=12345
 * full avg10=0.00 avg60=0.00 avg300=0.00 total=12345
 * </pre>
 *
 * <p>{@code avg10} 表示最近 10 秒内有任务因为内存而停摆的时间占比 (百分比), 是目前
 * 最准确的低内存信号. 需要内核开启 CONFIG_PSI, Android 12+ 的设备基本都满足.
 *
 * <p>直接读 procfs, 不依赖任何隐藏 API / lmkd 内部接口.
 */
public class PsiMonitor {
    private static final String PSI_MEMORY_PATH = "/proc/pressure/memory";

    /**
     * 一次采样的结果. 由 {@link #read(Stats)} 填充, 调用方自己持有实例即可, 线程不安全.
     */
    public static final class Stats {
        public double someAvg10;
        public double someAvg60;
        public double someAvg300;
        public long someTotal;

        public double fullAvg10;
        public double fullAvg60;
        public double fullAvg300;
        public long fullTotal;

        /** 内核是否输出了 full 行 (极少数老内核没有). */
        public boolean fullPresent;

        public String toShortString() {
            return "PSI(memory) some avg10=" + format(someAvg10) + "% full avg10=" + format(fullAvg10) + "%";
        }

        @Override
        public String toString() {
            return "PSI(memory) some avg10=" + format(someAvg10) + "% avg60=" + format(someAvg60)
                    + "% avg300=" + format(someAvg300) + "% | full avg10=" + format(fullAvg10)
                    + "% avg60=" + format(fullAvg60) + "% avg300=" + format(fullAvg300) + "%";
        }

        private static String format(double value) {
            return String.format(java.util.Locale.US, "%.2f", value);
        }
    }

    private static Boolean supported;

    private PsiMonitor() {
    }

    public static boolean isSupported() {
        Boolean cached = supported;
        if (cached != null)
            return cached;

        File file = new File(PSI_MEMORY_PATH);
        supported = file.exists() && file.canRead();
        return supported;
    }

    /**
     * 采样一次并填充 {@code stats}.
     *
     * @return 成功返回 true; 文件不存在 / 读取失败 / 格式不认识返回 false, 此时不要使用 stats 里的值.
     */
    public static boolean read(Stats stats) {
        if (stats == null || !isSupported())
            return false;

        try (BufferedReader reader = new BufferedReader(new FileReader(PSI_MEMORY_PATH))) {
            StringBuilder builder = new StringBuilder(160);
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return parse(builder.toString(), stats);
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * 解析 PSI 文本. 独立出来是为了能在 JVM 单元测试里覆盖各种内核输出格式.
     */
    static boolean parse(String text, Stats stats) {
        if (text == null || stats == null)
            return false;

        boolean some = false;
        boolean full = false;
        stats.fullPresent = false;

        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty())
                continue;

            if (line.startsWith("some")) {
                some = parseLine(line, stats, true);
            } else if (line.startsWith("full")) {
                full = parseLine(line, stats, false);
                stats.fullPresent = full;
            }
        }

        // 个别内核只输出 some 行, 这种情况也算读取成功, full 保持 0.
        return some || full;
    }

    private static boolean parseLine(String line, Stats stats, boolean isSome) {
        // some avg10=0.00 avg60=0.00 avg300=0.00 total=0
        String[] parts = line.split("\\s+");
        if (parts.length < 5)
            return false;

        try {
            double avg10 = parseValue(parts[1]);
            double avg60 = parseValue(parts[2]);
            double avg300 = parseValue(parts[3]);
            long total = (long) parseValue(parts[4]);

            if (isSome) {
                stats.someAvg10 = avg10;
                stats.someAvg60 = avg60;
                stats.someAvg300 = avg300;
                stats.someTotal = total;
            } else {
                stats.fullAvg10 = avg10;
                stats.fullAvg60 = avg60;
                stats.fullAvg300 = avg300;
                stats.fullTotal = total;
            }
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static double parseValue(String keyValue) {
        int index = keyValue.indexOf('=');
        if (index < 0 || index == keyValue.length() - 1)
            return 0.0;
        return Double.parseDouble(keyValue.substring(index + 1).trim());
    }
}
