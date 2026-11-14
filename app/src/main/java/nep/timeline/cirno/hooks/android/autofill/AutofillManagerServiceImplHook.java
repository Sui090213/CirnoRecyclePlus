package nep.timeline.cirno.hooks.android.autofill;

import android.content.pm.ServiceInfo;
import android.os.IBinder;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.utils.AutofillData;

/**
 * 自动填充会话开始: 登记「豁免冻结」并解冻 provider 应用.
 *
 * <p>为什么需要: 冻结正在提供自动填充的应用后, system_server 的 startSession / 请求填充会
 * 一直等 Binder 回包, 表现为「点输入框后密码候选不弹, 甚至输入法界面卡住」. 会话期间
 * {@code AppRecord#isProtected()} 会查询 {@link AutofillData}, 从而跳过冻结.
 *
 * <p>签名: AOSP android12-release / android13-release / android14-release / android15-release /
 * android16-release 以及 main 的 {@code AutofillManagerServiceImpl#startSessionLocked} 开头都是
 * (IBinder activityToken, int taskId, int clientUid, IBinder clientCallback, ...); 后半段各版本
 * 有增删 (例如 SDK 37 / Android 17 把末尾的 boolean 换成 String 并多一个 int).
 * 因此这里不写 Build.VERSION.SDK_INT 分支, 而是只拿前 3 个参数当「前缀」交给
 * findParameterTypesOrDefault 反查完整签名: 反查不到时 hook 失败, 由 isIgnoreError 静默跳过,
 * 比按版本硬编码签名更不容易在 OEM / 新版本上挂错.
 */
public class AutofillManagerServiceImplHook extends MethodHook {
    private static final String TARGET_CLASS = "com.android.server.autofill.AutofillManagerServiceImpl";
    private static final String TARGET_METHOD = "startSessionLocked";
    /** AutofillManager 侧 INVALID_SESSION_ID 的取值; startSessionLocked 失败 / 服务禁用时也会返回 0. */
    private static final int INVALID_SESSION_ID = 0x7fffffff;

    public AutofillManagerServiceImplHook(ClassLoader classLoader) {
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
                TARGET_METHOD, IBinder.class, int.class, int.class);
    }

    @Override
    public boolean isIgnoreError() {
        return true;
    }

    @Override
    public void startHook() {
        // 挂不上只记录一条警告, 不能把异常抛回 system_server 的注入流程.
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
                Log.w("Autofill startSessionLocked 原方法异常", callback.throwable);
                return;
            }

            if (!(callback.result instanceof Long sessionResult)) {
                Log.w("Autofill startSessionLocked 返回值类型异常: " + callback.result);
                return;
            }

            Object serviceImpl = callback.getThisObject();
            // 返回 long 时低 32 位就是 sessionId (高 32 位是 flags).
            int sessionId = (int) (sessionResult & 0xffffffffL);
            if (sessionId == 0 || sessionId == INVALID_SESSION_ID) {
                Log.d("Autofill startSession 忽略无效 sessionId=" + sessionId);
                return;
            }

            int userId = CakeReflection.getIntField(serviceImpl, "mUserId");
            String providerPackage = resolveProviderPackage(serviceImpl);
            if (providerPackage == null) {
                Log.w("Autofill provider 解析失败: mInfo/getServiceInfo 为空, userId=" + userId);
                return;
            }

            AppRecord appRecord = AppService.get(providerPackage, userId);
            if (appRecord == null) {
                Log.w("Autofill provider 未找到 AppRecord pkg=" + providerPackage + " userId=" + userId);
                return;
            }

            // 必须在 putSession 之前判断: 这里问的是「本次会话开始前该 provider 是否已经被豁免」,
            // 也就是是否需要解冻 / 取消已排队的冻结消息.
            boolean alreadyActive = AutofillData.hasActiveSession(appRecord);
            AutofillData.putSession(userId, sessionId, appRecord);

            if (alreadyActive) {
                Log.d("Autofill provider 已在豁免中 app=" + appRecord.getPackageNameWithUser()
                        + " activeSessions=" + AutofillData.getActiveSessionCount(appRecord));
                return;
            }

            // 第一个会话: 解冻并顺带取消可能已经排队的冻结消息 (thaw 内部会 removeAppMessage).
            FreezerService.thaw(appRecord);
            Log.i("Autofill session 开始 sessionId=" + sessionId
                    + " app=" + appRecord.getPackageNameWithUser()
                    + " activeSessions=" + AutofillData.getSessionCount());
        } catch (Throwable throwable) {
            // 回调里的异常绝对不能抛回 system_server.
            Log.w("AutofillManagerServiceImplHook 处理失败", throwable);
        }
    }

    /**
     * 从 serviceImpl 的 mInfo 拿 provider 包名.
     *
     * <p>AOSP 里 mInfo 是 AutofillServiceInfo (有 getServiceInfo()); 个别 ROM 直接放 ServiceInfo,
     * 所以两种情况都处理, 拿不到就返回 null 让调用方记日志后跳过.
     */
    private static String resolveProviderPackage(Object serviceImpl) {
        try {
            Object info = CakeReflection.getObjectField(serviceImpl, "mInfo");
            if (info == null)
                return null;

            if (info instanceof ServiceInfo serviceInfo)
                return emptyToNull(serviceInfo.packageName);

            Object serviceInfoObject = CakeReflection.callMethod(info, "getServiceInfo");
            if (serviceInfoObject instanceof ServiceInfo serviceInfo)
                return emptyToNull(serviceInfo.packageName);
        } catch (Throwable ignored) {
            // 字段 / 方法在该 ROM 上不存在, 当作解析失败.
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
