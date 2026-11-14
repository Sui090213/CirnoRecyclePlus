package nep.timeline.cirno.hooks.android.credentials;

import android.content.ComponentName;
import android.content.pm.ServiceInfo;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.AppService;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.utils.CredentialData;

/**
 * 凭证管理器 / 通行密钥会话开始: 登记「豁免冻结」并解冻 provider 应用.
 *
 * <p>为什么需要: 请求通行密钥 / 密码时, provider 应用需要持续响应 system_server 的 Binder 调用,
 * 被冻结会表现为「选择通行密钥时一直转圈 / 超时」. 会话期间 {@code AppRecord#isProtected()}
 * 会查询 {@link CredentialData}.
 *
 * <p>入口选择 (按 AOSP 源码确认, 见 android14-release ~ android16-release / main 的
 * services/credentials):
 * <ul>
 *     <li>真正的入口是 {@code CredentialManagerServiceImpl#initiateProviderSessionForRequestLocked
 *     (RequestSession, List<String>)}, 每个 provider 调用一次, 返回 ProviderSession;
 *     请求 (get) 和创建 (create) 流程都走它, 所以优先挂这个;</li>
 *     <li>AOSP 的 CredentialManagerServiceImpl 上**没有** startRequest / startCreateRequest
 *     (请求入口在 CredentialManagerService, 由它调用上面的 per-provider 方法), 但个别 ROM /
 *     未来版本可能改名, 主入口挂不上时再按这两个名字兜底.</li>
 * </ul>
 *
 * <p>SDK 依据: CredentialManager 是 Android 14 (UPSIDE_DOWN_CAKE, API 34) 引入的,
 * Android 12/13 的 system_server 里没有这个类, 所以 getMinVersion() 给 34.
 */
public class CredentialManagerServiceImplHook extends MethodHook {
    private static final String TARGET_CLASS = "com.android.server.credentials.CredentialManagerServiceImpl";
    /** AOSP 14~17 真正创建 ProviderSession 的方法. */
    private static final String PRIMARY_METHOD = "initiateProviderSessionForRequestLocked";
    /** 主入口不存在时尝试的 ROM / 新版本别名 (按顺序, 挂上一个就停). */
    private static final String[] FALLBACK_METHODS = {"startRequest", "startCreateRequest"};

    public CredentialManagerServiceImplHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return TARGET_CLASS;
    }

    @Override
    public String getTargetMethod() {
        return PRIMARY_METHOD;
    }

    @Override
    public Object[] getTargetParam() {
        // AOSP 14-17: (RequestSession requestSession, List<String> requestOptions).
        // 用「参数类名 + List」当前缀反查完整签名, 反查不到时返回前缀并由 isIgnoreError 静默跳过.
        return CakeReflection.findParameterTypesOrDefault(
                CakeReflection.findClassIfExists(TARGET_CLASS, classLoader),
                PRIMARY_METHOD, "com.android.server.credentials.RequestSession", List.class);
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
        int minVersion = getMinVersion();
        if (minVersion != ANY_VERSION && Build.VERSION.SDK_INT < minVersion)
            return;

        // 先挂 AOSP 主入口; 失败只记录, 不向外抛.
        try {
            super.startHook();
        } catch (Throwable throwable) {
            Log.w("Credential 主入口 " + PRIMARY_METHOD + " Hook 安装失败", throwable);
        }

        if (unhooker != null)
            return;

        // 主入口不存在 (ROM 改名 / 新版本重构): 按别名兜底, 挂不上就跳过.
        for (String methodName : FALLBACK_METHODS) {
            if (hookAllOverloads(methodName)) {
                Log.i("Credential " + methodName + " -> 成功Hook完毕! (ROM 兼容入口)");
                return;
            }
        }

        Log.w("Credential 未找到可挂载的 provider session 入口, 通行密钥豁免不可用");
    }

    /**
     * 挂载 clazz 上所有同名重载.
     *
     * <p>别名入口的签名事先无法确定, 只能全挂; 回调里先看返回值是不是 ProviderSession,
     * 不是再从参数 / this 的 mProviders 里找, 都找不到就只记调试日志, 不影响原请求.
     */
    private boolean hookAllOverloads(String methodName) {
        Class<?> clazz = CakeReflection.findClassIfExists(TARGET_CLASS, classLoader);
        if (clazz == null)
            return false;

        boolean hooked = false;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName))
                continue;

            Class<?>[] parameterTypes = method.getParameterTypes();
            Object[] parameterTypesAndCallback = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, parameterTypesAndCallback, 0, parameterTypes.length);
            parameterTypesAndCallback[parameterTypes.length] = getTargetHook();

            try {
                unhooker = CakeReflection.findAndHookMethod(clazz, methodName, parameterTypesAndCallback);
                hooked = true;
            } catch (Throwable throwable) {
                Log.w("Credential " + methodName + " 重载安装失败", throwable);
            }
        }
        return hooked;
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.AfterHookCallback callback) {
                handleStart(callback);
            }
        };
    }

    private static void handleStart(CakeHooker.AfterHookCallback callback) {
        try {
            if (callback.throwable != null) {
                Log.w("Credential provider session 原方法异常", callback.throwable);
                return;
            }

            Object serviceImpl = callback.getThisObject();
            List<Object> providerSessions = collectProviderSessions(serviceImpl, callback.result, callback.getArgs());
            if (providerSessions.isEmpty()) {
                // 别名入口返回 void / 结果里没有会话: 属于正常情况, 只记调试日志.
                Log.d("Credential provider session 未解析到 ProviderSession, 跳过");
                return;
            }

            for (Object providerSession : providerSessions) {
                try {
                    handleProviderSession(serviceImpl, providerSession);
                } catch (Throwable throwable) {
                    Log.w("Credential provider session 处理失败", throwable);
                }
            }
        } catch (Throwable throwable) {
            // 回调里的异常绝对不能抛回 system_server.
            Log.w("CredentialManagerServiceImplHook 处理失败", throwable);
        }
    }

    private static void handleProviderSession(Object serviceImpl, Object providerSession) {
        int userId = resolveUserId(providerSession, serviceImpl);
        String providerPackage = resolveProviderPackage(providerSession, serviceImpl);
        if (providerPackage == null) {
            Log.w("Credential provider 解析失败: 拿不到 componentName, userId=" + userId);
            return;
        }

        AppRecord appRecord = AppService.get(providerPackage, userId);
        if (appRecord == null) {
            Log.w("Credential provider 未找到 AppRecord pkg=" + providerPackage + " userId=" + userId);
            return;
        }

        boolean added = CredentialData.putSession(providerSession, appRecord);
        // 与 Autofill 不同, 这里关心的是「provider 一次都还没被豁免过」时解冻:
        // 请求可能同时复用同一个 provider 的多个会话, 区分「是否首个会话」意义不大,
        // 而 thaw 对没被冻结的应用几乎零开销 (没有已冻结进程时直接返回).
        FreezerService.thaw(appRecord);

        Log.i("Credential provider session " + (added ? "开始" : "更新")
                + " app=" + appRecord.getPackageNameWithUser()
                + " activeSessions=" + CredentialData.getSessionCount());
    }

    /**
     * 收集本次调用涉及的 ProviderSession.
     *
     * <p>优先级: 返回值本身是 ProviderSession (AOSP 主入口) 时直接用, 不再扫参数;
     * 否则从返回值 / 参数的 mProviders 字段 (RequestSession 里的
     * {@code Map<String, ProviderSession>}) 兜底收集.
     */
    private static List<Object> collectProviderSessions(Object serviceImpl, Object result, Object[] args) {
        List<Object> sessions = new ArrayList<>();

        if (isProviderSession(result)) {
            sessions.add(result);
            return sessions;
        }

        // 个别 ROM 的入口一次返回多个会话 (List<ProviderSession>), 也一并收下.
        if (result instanceof List<?> list) {
            for (Object item : list) {
                if (isProviderSession(item) && !containsIdentity(sessions, item))
                    sessions.add(item);
            }
            if (!sessions.isEmpty())
                return sessions;
        }

        collectFromProvidersField(result, sessions);
        if (args != null) {
            for (Object arg : args)
                collectFromProvidersField(arg, sessions);
        }
        if (sessions.isEmpty() && serviceImpl != null)
            collectFromProvidersField(serviceImpl, sessions);

        return sessions;
    }

    /** 只认字段名 mProviders, 不做全字段扫描: 避免误伤和性能问题. */
    private static void collectFromProvidersField(Object target, List<Object> sessions) {
        if (target == null)
            return;

        try {
            Field field = CakeReflection.findFieldIfExists(target.getClass(), "mProviders");
            if (field == null)
                return;

            Object value = field.get(target);
            if (!(value instanceof Map<?, ?> providers))
                return;

            for (Object providerSession : providers.values()) {
                if (isProviderSession(providerSession) && !containsIdentity(sessions, providerSession))
                    sessions.add(providerSession);
            }
        } catch (Throwable ignored) {
            // 字段存在但不是 Map / 不可读: 跳过.
        }
    }

    /**
     * ProviderSession 是 com.android.server.credentials 里的抽象基类,
     * 实际对象是它的子类 (ProviderGetSession / ProviderCreateSession / ...),
     * 所以按继承链上的简单类名判断, 不写死具体子类.
     */
    private static boolean isProviderSession(Object object) {
        if (object == null)
            return false;
        for (Class<?> clazz = object.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            if ("ProviderSession".equals(clazz.getSimpleName()))
                return true;
        }
        return false;
    }

    private static boolean containsIdentity(List<Object> sessions, Object target) {
        for (Object session : sessions) {
            if (session == target)
                return true;
        }
        return false;
    }

    /** 优先用 ProviderSession 自带的 mUserId, 其次用 per-user service 继承来的 mUserId. */
    private static int resolveUserId(Object providerSession, Object serviceImpl) {
        int userId = tryGetIntField(providerSession, "mUserId");
        if (userId != Integer.MIN_VALUE)
            return userId;

        userId = tryGetIntField(serviceImpl, "mUserId");
        return (userId != Integer.MIN_VALUE) ? userId : 0;
    }

    private static int tryGetIntField(Object object, String fieldName) {
        if (object == null)
            return Integer.MIN_VALUE;
        try {
            return CakeReflection.getIntField(object, fieldName);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * 解析 provider 包名.
     *
     * <p>ProviderSession 上有 protected final ComponentName mComponentName 和
     * protected getComponentName(); 拿不到时退回 per-user service 的
     * getServiceComponentName() / mInfo.getServiceInfo().
     */
    private static String resolveProviderPackage(Object providerSession, Object serviceImpl) {
        try {
            String packageName = componentNameToPackage(CakeReflection.callMethod(providerSession, "getComponentName"));
            if (packageName != null)
                return packageName;
        } catch (Throwable ignored) {
            // 个别 ROM 没有 getComponentName().
        }

        try {
            String packageName = componentNameToPackage(CakeReflection.getObjectField(providerSession, "mComponentName"));
            if (packageName != null)
                return packageName;
        } catch (Throwable ignored) {
            // 字段不存在.
        }

        if (serviceImpl == null)
            return null;

        try {
            String packageName = componentNameToPackage(CakeReflection.callMethod(serviceImpl, "getServiceComponentName"));
            if (packageName != null)
                return packageName;
        } catch (Throwable ignored) {
            // 字段 / 方法不存在.
        }

        try {
            Object info = CakeReflection.getObjectField(serviceImpl, "mInfo");
            if (info != null) {
                Object serviceInfoObject = CakeReflection.callMethod(info, "getServiceInfo");
                if (serviceInfoObject instanceof ServiceInfo serviceInfo)
                    return emptyToNull(serviceInfo.packageName);
            }
        } catch (Throwable ignored) {
            // 字段 / 方法不存在.
        }

        return null;
    }

    private static String componentNameToPackage(Object componentName) {
        if (!(componentName instanceof ComponentName value))
            return null;
        return emptyToNull(value.getPackageName());
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
