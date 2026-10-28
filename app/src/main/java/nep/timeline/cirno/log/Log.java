package nep.timeline.cirno.log;

import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import nep.timeline.cirno.GlobalVars;
import nep.timeline.cirno.threads.Handlers;
import nep.timeline.cirno.utils.RWUtils;

public class Log {
    private static final String LOGCAT_TAG = "CirnoRecycle";

    private final static SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN);
    private final static File currentLog = new File(GlobalVars.LOG_DIR, "current.log");

    /** 文件日志失败次数; 连续失败后只写 logcat, 避免每次调用都尝试写文件. */
    private static int fileLogFailures = 0;
    private static boolean fileLogDisabled = false;

    /** current.log 超过该大小时轮转到 last.log, 防止长期运行把日志写满. */
    private static final long MAX_LOG_BYTES = 2L * 1024 * 1024;
    private static long lastRotateCheckMs = 0L;

    static {
        i("设备Android SDK: " + Build.VERSION.SDK_INT);
    }

    public static void d(String msg) {
        execute("调试", msg);
    }

    public static void d(String msg, Throwable throwable) {
        d(msg + " 失败: " + throwable.getMessage());
    }

    public static void i(String msg) {
        execute("信息", msg);
    }

    public static void w(String msg) {
        execute("警告", msg);
    }

    public static void w(String msg, Throwable throwable) {
        w(msg + " 失败: " + throwable.getMessage());
    }

    public static void e(String msg) {
        execute("错误", msg);
    }

    public static void e(String msg, Throwable throwable) {
        e(msg + " 失败: " + throwable.getMessage());
    }

    public static void execute(String level, String msg) {
        // logcat 里也留一份, 方便 adb logcat -s CirnoRecycle 直接看.
        android.util.Log.println(android.util.Log.INFO, LOGCAT_TAG, level + " -> " + msg);

        if (fileLogDisabled)
            return;

        Handlers.log.post(() -> fileLog(simpleDateFormat.format(new Date()) + " " + level.toUpperCase() + " -> " + msg));
    }

    public static void fileLog(String msg) {
        if (fileLogDisabled)
            return;

        rotateIfNeeded();

        try {
            RWUtils.writeStringToFile(currentLog, msg, true);
            fileLogFailures = 0;
        } catch (IOException | RuntimeException e) {
            // 注意: 这里绝对不能再调用 Log.e(), 否则会再次 post 到同一个 Handler,
            // 在 App 进程没有 root 权限写 /data/misc 时形成无限循环.
            fileLogFailures++;
            android.util.Log.e(LOGCAT_TAG, "写入运行日志失败: " + msg, e);

            if (fileLogFailures >= 3) {
                fileLogDisabled = true;
                android.util.Log.w(LOGCAT_TAG, "文件日志连续失败 3 次, 后续只写 logcat");
            }
        }
    }

    private static void rotateIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRotateCheckMs < 60_000L)
            return;
        lastRotateCheckMs = now;

        try {
            if (!currentLog.exists() || currentLog.length() < MAX_LOG_BYTES)
                return;

            File lastLog = new File(GlobalVars.LOG_DIR, "last.log");
            if (lastLog.exists() && !lastLog.delete())
                return;
            if (!currentLog.renameTo(lastLog))
                return;

            android.util.Log.i(LOGCAT_TAG, "current.log 超过 2MB, 已轮转为 last.log");
        } catch (Throwable ignored) {
            // 轮转失败不影响写日志.
        }
    }
}
