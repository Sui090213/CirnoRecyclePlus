package nep.timeline.cirno.hooks.android.autofill;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.threads.FreezerHandler;
import nep.timeline.cirno.utils.AutofillData;

/**
 * 自动填充会话结束: 注销登记, 该应用没有其它活跃会话时重新排队冻结.
 *
 * <p>为什么延迟 3 秒: 会话结束后 provider 可能还要收尾 (写日志 / 回写数据), 立即冻结会把
 * 收尾过程卡死; ProcessAddHook 也是按 3 秒量级延迟冻结, 保持一致.
 *
 * <p>签名: AOSP android12-release ~ android16-release 的
 * {@code AutofillManagerServiceImpl#removeSessionLocked} 都只有一个重载
 * (int sessionId), 所以用 findParameterTypesOrDefault 给出 int 前缀即可反查完整签名;
 * 找不到时 hook 失败, 由 isIgnoreError 静默跳过, 只保留 start hook (豁免仍然生效, 只是
 * 会话结束后要等下一次自动冻结事件才会重新冻上).
 */
public class AutofillSessionRemoveHook extends MethodHook {
    private static final String TARGET_CLASS = "com.android.server.autofill.AutofillManagerServiceImpl";
    private static final String TARGET_METHOD = "removeSessionLocked";
    /** 会话结束后延迟多久重新排队冻结 (毫秒). */
    private static final long REFREEZE_DELAY_MS = 3000L;

    public AutofillSessionRemoveHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return TARGET_CLASS;
    }

    @Override
    public String getTargetMethod() {
        return TARGET_METHOD;
    }

    @Override
    public Object[] getTargetParam() {
        return CakeReflection.findParameterTypesOrDefault(
                CakeReflection.findClassIfExists(TARGET_CLASS, classLoader),
                TARGET_METHOD, int.class);
    }

    @Override
    public boolean isIgnoreError() {
        return true;
    }

    @Override
    public void startHook() {
        try {
            super.startHook();
        } catch (Throwable throwable) {
            Log.w("Autofill " + TARGET_METHOD + " Hook 安装失败", throwable);
        }
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.AfterHookCallback callback) {
                handle(callback);
            }
        };
    }

    private static void handle(CakeHooker.AfterHookCallback callback) {
        try {
            if (callback.throwable != null) {
                Log.w("Autofill removeSessionLocked 原方法异常", callback.throwable);
                return;
            }

            Object[] args = callback.getArgs();
            if (args.length < 1 || !(args[0] instanceof Integer sessionIdBoxed)) {
                Log.w("Autofill removeSessionLocked 参数异常: " + args.length);
                return;
            }
            int sessionId = sessionIdBoxed;

            int userId = CakeReflection.getIntField(callback.getThisObject(), "mUserId");
            AppRecord appRecord = AutofillData.removeSession(userId, sessionId);
            if (appRecord == null) {
                Log.d("Autofill removeSession 没有登记 sessionId=" + sessionId + " userId=" + userId);
                return;
            }

            Log.i("Autofill session 结束 sessionId=" + sessionId
                    + " app=" + appRecord.getPackageNameWithUser()
                    + " activeSessions=" + AutofillData.getSessionCount());

            if (AutofillData.hasActiveSession(appRecord)) {
                Log.d("Autofill provider 保持豁免 app=" + appRecord.getPackageNameWithUser()
                        + " activeSessions=" + AutofillData.getActiveSessionCount(appRecord));
                return;
            }

            Log.d("Autofill provider " + REFREEZE_DELAY_MS + "ms 后重新排队冻结 app="
                    + appRecord.getPackageNameWithUser());
            FreezerHandler.sendFreezeMessageIgnoreMessages(appRecord, REFREEZE_DELAY_MS);
        } catch (Throwable throwable) {
            Log.w("AutofillSessionRemoveHook 处理失败", throwable);
        }
    }
}
