package nep.timeline.cirno.utils;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.os.UserHandle;

public class PKGUtils {
    public static int getUserId(int uid) {
        return UserHandle.getUserHandleForUid(uid).hashCode();
    }

    public static boolean isSystemApp(ApplicationInfo applicationInfo) {
        if (applicationInfo == null)
            return true;

        if (applicationInfo.uid < Process.FIRST_APPLICATION_UID)
            return true;
        if ((applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
            return true;

        // 有些 OEM 预装应用只装在 product / vendor / odm 分区, 但没有带 FLAG_SYSTEM;
        // 只按 flags 判断会把它们当第三方应用冻结 / 回收. 这里再按安装路径兜一层.
        return isSystemPartitionPath(applicationInfo.sourceDir)
                || isSystemPartitionPath(applicationInfo.publicSourceDir);
    }

    /** 安装路径是否位于系统分区 (只做字符串前缀判断, 不访问文件系统). */
    public static boolean isSystemPartitionPath(String path) {
        if (path == null || path.isEmpty())
            return false;

        return path.startsWith("/system/")
                || path.startsWith("/system_ext/")
                || path.startsWith("/product/")
                || path.startsWith("/vendor/")
                || path.startsWith("/odm/")
                || path.startsWith("/apex/");
    }
}
