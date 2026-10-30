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
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("写入临时配置文件失败: " + temp);
            }
        }

        Shell.Result result = Shell.cmd("mv -f '" + temp + "' '" + target + "'").exec();
        if (!result.isSuccess()) {
            // 某些环境可能禁止 root shell 的 mv (极少数), 退化为直接覆盖写.
            try (PrintWriter writer = new PrintWriter(SuFileOutputStream.open(file), false)) {
                writer.write(value);
                writer.flush();
                if (writer.checkError()) {
                    throw new IOException("直接写入配置文件失败: " + target);
                }
            }
        }

        // 文件是 root shell 创建的 (属主 root, 权限受 umask 影响). system_server 以 system 用户
        // 读这些文件 (配置热重载 / 手动冻结操作), 如果 umask 是 077 就会读不到, 表现为
        // 「App 里改了设置但 system_server 一直不生效」. 这里显式放开读权限, 保证跨进程可读.
        Shell.Result chmodResult = Shell.cmd("chmod 0644 '" + target + "'").exec();
        if (!chmodResult.isSuccess()) {
            // 不抛异常: 文件本身已经写成功了 (App 自己能读回校验); 只提示排查方向.
            Log.w("chmod 0644 失败, system_server 可能读不到 " + target + ": " + chmodResult.getErr());
        }

        // 读回校验: PrintWriter / root shell 都可能静默失败, 不校验的话 UI 会以为保存成功.
        String readBack = readConfig(new SuFile(target));
        if (readBack == null || !readBack.equals(value)) {
            throw new IOException("配置写入校验失败: " + target);
        }
    }

    public static boolean writeFrozen(String path, int value) {
        try {
            PrintWriter writer = new PrintWriter(path);
            try {
                writer.write(Integer.toString(value));
                writer.flush();
                // PrintWriter 会吞掉写失败 (比如 cgroup 只读 / 进程已退出), 必须自己检查.
                return !writer.checkError();
            } finally {
                writer.close();
            }
        } catch (FileNotFoundException ignored) {
            Log.e(path + " | 文件不存在, 此进程可能已死亡, 或者你的设备不支持cgroup v2");
            return false;
        }
    }
}
