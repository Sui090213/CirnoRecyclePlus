package nep.timeline.cirno.memory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.reflect.CakeReflection;
import nep.timeline.cirno.services.ActivityManagerService;
import nep.timeline.cirno.services.CachedAppOptimizer;
import nep.timeline.cirno.virtuals.ProcessRecord;

/**
 * L2: 调用 AMS 的 {@code CachedAppOptimizer.compactApp} 对进程做内存压缩
 * (process_madvise 的 MADV_COLD / MADV_PAGEOUT), 把冷页标记 / 回收.
 *
 * <p>Cirno 已经在 CacheEnableFreezerHook / CacheUseFreezerHook 里拿到了系统
 * CachedAppOptimizer 实例, 这里直接复用, 不需要新增 hook.
 *
 * <p>不同 Android 版本 compactApp 的签名不完全一致, 因此按
 * "方法名 compactApp + 第一个参数为 com.android.server.am.ProcessRecord" 动态解析,
 * 后续参数按类型填充: {@code boolean} 传 true (强制), {@code int} 传压缩来源常量.
 * 解析失败或调用失败只打日志, 不影响 L0 的 memory.reclaim.
 *
 * <p>注意: 该调用会读 ProcessRecord 的 pid 等字段, 这里按框架习惯持有 mProcLock 再调用.
 */
public class CompactHelper {
    private static final String PROCESS_RECORD_CLASS = "com.android.server.am.ProcessRecord";

    private static boolean unsupportedLogged = false;
    private static boolean disabledLogged = false;
    private static boolean methodResolved = false;
    private static Method compactMethod;

    private CompactHelper() {
    }

    /**
     * 对一个进程请求压缩, 返回是否成功发起 (系统内部可能因为进程状态跳过, 这里拿不到结果).
     */
    public static boolean compact(ProcessRecord processRecord) {
        if (processRecord == null || processRecord.isDeathProcess())
            return false;

        Object optimizer = CachedAppOptimizer.getInstance();
        if (optimizer == null) {
            logUnsupported("CachedAppOptimizer 实例尚未就绪");
            return false;
        }

        if (!isCompactionEnabled(optimizer)) {
            if (!disabledLogged) {
                disabledLogged = true;
                Log.w("系统未启用内存压缩 (mUseCompaction=false), 跳过 L2");
            }
            return false;
        }

        Method method = getCompactMethod(optimizer.getClass());
        if (method == null) {
            logUnsupported("找不到 compactApp 方法");
            return false;
        }

        int source = getCompactSource(optimizer.getClass());
        Object record = processRecord.getInstance();

        try {
            Object procLock = getProcLock();
            if (procLock == null) {
                invokeCompact(method, optimizer, record, source);
            } else {
                synchronized (procLock) {
                    invokeCompact(method, optimizer, record, source);
                }
            }
            return true;
        } catch (Throwable throwable) {
            Log.d("内存压缩 " + processRecord.getProcessName() + " 失败: " + throwable);
            return false;
        }
    }

    private static boolean isCompactionEnabled(Object optimizer) {
        try {
            return CakeReflection.getBooleanField(optimizer, "mUseCompaction");
        } catch (Throwable ignored) {
            // 字段不存在时按系统默认值处理, 交给 compactApp 自己判断.
            return true;
        }
    }

    private static Method getCompactMethod(Class<?> clazz) {
        if (methodResolved)
            return compactMethod;

        // 注意: 这里只在第一次调用时解析, 解析结果缓存在静态字段里.
        methodResolved = true;

        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals("compactApp"))
                continue;

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length < 2)
                continue;
            if (!PROCESS_RECORD_CLASS.equals(parameterTypes[0].getName()))
                continue;

            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                // 不可访问时继续用其它重载, 都失败则跳过 L2.
                continue;
            }

            compactMethod = method;
            Log.i("已解析内存压缩接口: " + method);
            break;
        }
        return compactMethod;
    }

    private static void invokeCompact(Method method, Object optimizer, Object record, int source) throws Throwable {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        args[0] = record;

        for (int i = 1; i < parameterTypes.length; i++) {
            Class<?> type = parameterTypes[i];
            if (type == boolean.class) {
                args[i] = true;              // shouldForceCompact
            } else if (type == int.class) {
                args[i] = source;            // CompactSource
            } else if (type == long.class) {
                args[i] = 0L;
            } else if (type == float.class) {
                args[i] = 0.0f;
            } else if (type == double.class) {
                args[i] = 0.0d;
            } else {
                args[i] = null;
            }
        }

        try {
            method.invoke(optimizer, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    /**
     * 取 CompactSource 常量. 各版本常量名可能不同, 逐个尝试, 最后回退到 AOSP 的
     * COMPACT_SOURCE_APP_BACKGROUND (2).
     */
    private static int getCompactSource(Class<?> clazz) {
        String[] names = {
                "COMPACT_SOURCE_MEMORY_PRESSURE",
                "COMPACT_SOURCE_APP_BACKGROUND",
                "COMPACT_SOURCE_SYSTEM",
                "COMPACT_SOURCE_UNKNOWN"
        };
        for (String name : names) {
            try {
                return CakeReflection.getStaticIntField(clazz, name);
            } catch (Throwable ignored) {
                // 该版本没有这个常量, 继续尝试下一个.
            }
        }
        return 2;
    }

    private static Object getProcLock() {
        try {
            Object activityManagerService = ActivityManagerService.instance;
            if (activityManagerService == null)
                return null;
            return CakeReflection.getObjectField(activityManagerService, "mProcLock");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logUnsupported(String reason) {
        if (unsupportedLogged)
            return;
        unsupportedLogged = true;
        Log.w("内存压缩不可用, 已跳过 L2: " + reason);
    }
}
