package nep.timeline.cirno.utils;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import com.topjohnwu.superuser.io.SuFileInputStream;
import com.topjohnwu.superuser.io.SuFileOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import nep.timeline.cirno.log.Log;

public class RWUtils {
    public static String readConfig(SuFile file) {
        try {
            return IOUtils.toString(() -> SuFileInputStream.open(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e("Read Config", e);
        }

        return null;
    }

    public static String readConfig(String name) {
        try {
            return String.join("\n", FileUtils.readLines(new File(name), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e("Read Config", e);
        }

        return "";
    }

    public static void writeStringToFile(File file, String value) throws IOException {
        writeStringToFile(file, value + "\n", false);
    }

    public static void writeStringToFile(File file, String value, boolean append) throws IOException {
        FileUtils.write(file, value + "\n", StandardCharsets.UTF_8, append);
    }

    public static void writeStringToFileSU(SuFile file, String value, boolean append) throws IOException {
        try (PrintWriter writer = new PrintWriter(SuFileOutputStream.open(file), append)) {
            writer.write(value);
        }
    }

    /**
     * 以「写临时文件 + mv 原子替换」的方式写配置 (App 进程, root shell).
     *
     * <p>system_server 的 {@code ConfigFileObserver} 可能在任何时刻读到文件; 直接截断重写
     * 会让它读到半个 JSON. 先写 {@code xxx.tmp}, 再用 root 的 {@code mv -f} 覆盖, 读方
     * 要么看到旧文件, 要么看到完整的新文件.
     */
    public static void writeStringToFileSUAtomic(SuFile file, String value) throws IOException {
        if (file == null)
            throw new IOException("file == null");

        String target = file.getAbsolutePath();
        String temp = target + ".tmp";

        try (PrintWriter writer = new PrintWriter(SuFileOutputStream.open(new SuFile(temp)), false)) {
            writer.write(value);
        }

        Shell.Result result = Shell.cmd("mv -f '" + temp + "' '" + target + "'").exec();
        if (!result.isSuccess()) {
            // 某些环境可能禁止 root shell 的 mv (极少数), 退化为直接覆盖写.
            try (PrintWriter writer = new PrintWriter(SuFileOutputStream.open(file), false)) {
                writer.write(value);
            }
        }
    }

    public static boolean writeFrozen(String path, int value) {
        try (PrintWriter writer = new PrintWriter(path)) {
            writer.write(Integer.toString(value));
            return true;
        } catch (FileNotFoundException ignored) {
            Log.e(path + " | 文件不存在, 此进程可能已死亡, 或者你的设备不支持cgroup v2");
            return false;
        }
    }
}
