package nep.timeline.cirno.utils;

import android.os.Process;

import java.nio.file.Files;
import java.nio.file.Paths;

public class FrozenRW {
    public static final String cgroupV2 = "/sys/fs/cgroup";
    private static final boolean cgroupV2SysAppIsolated;

    static {
        String path = "/sys/fs/cgroup/uid_1000/cgroup.freeze";
        cgroupV2SysAppIsolated = !Files.exists(Paths.get(path));
    }

    private static boolean writeFrozen(int uid, int pid, int frozenState) {
        if (!cgroupV2SysAppIsolated)
            return RWUtils.writeFrozen(cgroupV2 + "/uid_" + uid + "/pid_" + pid + "/cgroup.freeze", frozenState);

        if (uid < Process.FIRST_APPLICATION_UID)
            return RWUtils.writeFrozen(cgroupV2 + "/system/uid_" + uid + "/pid_" + pid + "/cgroup.freeze", frozenState);
        return RWUtils.writeFrozen(cgroupV2 + "/apps/uid_" + uid + "/pid_" + pid + "/cgroup.freeze", frozenState);
    }

    /**
     * 冻结进程.
     *
     * @return true 表示写 cgroup.freeze 成功 (进程可能已退出时会返回 false)
     */
    public static boolean frozen(int uid, int pid) {
        return writeFrozen(uid, pid, 1);
    }

    /**
     * 解冻进程.
     *
     * @return true 表示写 cgroup.freeze 成功
     */
    public static boolean thaw(int uid, int pid) {
        return writeFrozen(uid, pid, 0);
    }
}
