package nep.timeline.cirno.hooks.android.credentials;

import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.threads.FreezerHandler;
import nep.timeline.cirno.utils.CredentialData;

/**
 * 凭证管理器 / 通行密钥请求会话结束: 注销 provider 会话, 没有其它会话时重新排队冻结.
 *
 * <p>为什么用 Before 而不是 After: {@code RequestSession#finishSession} 内部会执行
 * {@code mProviders.clear()} (见 AOSP 14~17 的 services/credentials/RequestSession.java),
 * After 回调里已经拿不到 ProviderSession 对象, 也就无法按对象身份把登记删掉. 所以必须在
 * 清空之前注销; 冻结本身用 3 秒延迟排队, 效果与 After 一致 (给 provider 收尾 / UI 退出留时间).
 *
 * <p>SDK 依据:
 * <ul>
 *     <li>CredentialManager 从 Android 14 (UPSIDE_DOWN_CAKE, API 34) 才有, 所以 min 34;</li>
 *     <li>Android 14 的签名是 {@code finishSession(boolean propagateCancellation)};
 *     Android 15 (VANILLA_ICE_CREAM, API 35) 起是
 *     {@code finishSession(boolean propagateCancellation, int apiStatus)}.
 *     这里用 findParameterTypesOrDefault 只给 boolean 前缀, 两种签名都能反查到完整参数,
 *     不需要按 SDK_INT 分支.</li>
 * </ul>
 */
public class CredentialRequestSessionFinishHook extends MethodHook {
    private static final String TARGET_CLASS = "com.android.server.credentials.RequestSession";
    private static final String TARGET_METHOD = "finishSession";
    /** 会话结束后延迟多久重新排队冻结 (毫秒). */
    private static final long REFREEZE_DELAY_MS = 3000L;

    public CredentialRequestSessionFinishHook(ClassLoader classLoader) {
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
                TARGET_METHOD, boolean.class);
    }

    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
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
            Log.w("Credential " + TARGET_METHOD + " Hook 安装失败", throwable);
        }
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.BeforeHookCallback callback) {
                handle(callback);
            }
        };
    }

    private static void handle(CakeHooker.BeforeHookCallback callback) {
        try {
            Object requestSession = callback.getThisObject();
            if (requestSession == null)
                return;

            Object providersObject;
            try {
                providersObject = CakeReflection.getObjectField(requestSession, "mProviders");
            } catch (Throwable ignored) {
                Log.w("Credential finishSession: 找不到 mProviders, 跳过");
                return;
            }

            if (!(providersObject instanceof Map<?, ?> providers) || providers.isEmpty())
                return;

            // 先拷贝一份再遍历: 原方法接下来会 mProviders.clear(), 而且并发场景下
            // 其它线程也可能在改这个 ConcurrentHashMap.
            List<Object> providerSessions = new ArrayList<>(providers.values());
            for (Object providerSession : providerSessions) {
                try {
                    releaseProviderSession(providerSession);
                } catch (Throwable throwable) {
                    Log.w("Credential provider session 注销失败", throwable);
                }
            }
        } catch (Throwable throwable) {
            // 回调里的异常绝对不能抛回 system_server.
            Log.w("CredentialRequestSessionFinishHook 处理失败", throwable);
        }
    }

    private static void releaseProviderSession(Object providerSession) {
        AppRecord appRecord = CredentialData.removeSession(providerSession);
        if (appRecord == null)
            return;

        Log.i("Credential provider session 结束 app=" + appRecord.getPackageNameWithUser()
                + " activeSessions=" + CredentialData.getSessionCount());

        if (CredentialData.hasActiveSession(appRecord)) {
            Log.d("Credential provider 保持豁免 app=" + appRecord.getPackageNameWithUser());
            return;
        }

        Log.d("Credential provider " + REFREEZE_DELAY_MS + "ms 后重新排队冻结 app="
                + appRecord.getPackageNameWithUser());
        FreezerHandler.sendFreezeMessageIgnoreMessages(appRecord, REFREEZE_DELAY_MS);
    }
}
