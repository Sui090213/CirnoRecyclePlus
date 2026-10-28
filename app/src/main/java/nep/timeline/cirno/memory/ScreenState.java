package nep.timeline.cirno.memory;

import android.content.Context;
import android.os.PowerManager;

import nep.timeline.cirno.services.ActivityManagerService;

/**
 * 屏幕状态查询. 只用于「仅息屏时回收」开关.
 *
 * <p>在 system_server 里通过 ActivityManagerService 拿 Context 再取 PowerManager,
 * 任何一步失败都按「亮屏」处理: 宁可少回收, 也不要在用户玩手机时突然回收造成卡顿.
 */
public class ScreenState {
    private ScreenState() {
    }

    /** 当前是否亮屏且可交互. 查询失败时返回 true. */
    public static boolean isInteractive() {
        try {
            Context context = ActivityManagerService.getContext();
            if (context == null)
                return true;

            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null)
                return true;

            return powerManager.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
