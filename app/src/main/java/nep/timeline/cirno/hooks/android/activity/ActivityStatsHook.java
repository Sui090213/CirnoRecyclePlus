package nep.timeline.cirno.hooks.android.activity;

import android.app.usage.UsageEvents;
import android.os.IBinder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import nep.timeline.cirno.reflect.CakeHooker;
import nep.timeline.cirno.virtuals.ActivityRecord;
import nep.timeline.cirno.entity.AppRecord;
import nep.timeline.cirno.framework.MethodHook;
import nep.timeline.cirno.log.Log;
import nep.timeline.cirno.services.FreezerService;
import nep.timeline.cirno.threads.FreezerHandler;

public class ActivityStatsHook extends MethodHook {
    private static final Map<IBinder, ActivityRecord> activityRecords = new ConcurrentHashMap<>();
    private static final List<Integer> events = List.of(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED, UsageEvents.Event.ACTIVITY_STOPPED + 1);

    public ActivityStatsHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return "com.android.server.wm.ActivityTaskManagerService";
    }

    @Override
    public String getTargetMethod() {
        return "updateActivityUsageStats";
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[] { "com.android.server.wm.ActivityRecord", int.class };
    }

    @Override
    public CakeHooker.Callback getTargetHook() {
        return new CakeHooker.Callback() {
            @Override
            public void call(CakeHooker.BeforeHookCallback callback) {
                int event = (int) callback.getArgs()[1];

                if (!events.contains(event))
                    return;

                Object activityObject = callback.getArgs()[0];
                if (activityObject == null)
                    return;

                ActivityRecord record = new ActivityRecord(activityObject);

                ActivityRecord activityRecord = activityRecords.computeIfAbsent(record.getToken(), k -> record);
                if (activityRecord == null)
                    return;

                AppRecord appRecord = activityRecord.toAppRecord();
                if (appRecord == null)
                    return;

                if (event == UsageEvents.Event.ACTIVITY_RESUMED) {
                    appRecord.getAppState().getActivities().add(activityRecord.getToken());
                    if (appRecord.getAppState().setVisible(true)) {
                        Log.d(appRecord.getPackageNameWithUser() + " 进入前台");
                        FreezerService.thaw(appRecord);
                    }
                    return;
                }

                if (event == UsageEvents.Event.ACTIVITY_PAUSED) {
                    // 暂停不等于进入后台: 文件选择器、权限弹窗、Chooser、分屏等场景下
                    // Activity 仍是可见的. 这里绝不能把 activity 从可见集合移除,
                    // 否则调用方 3 秒后会被冻结, 返回结果时无法恢复.
                    appRecord.getAppState().getActivities().add(activityRecord.getToken());
                    appRecord.getAppState().setVisible(true);
                    return;
                }

                // ACTIVITY_STOPPED / ACTIVITY_DESTROYED 才是真正不可见.
                appRecord.getAppState().getActivities().remove(activityRecord.getToken());
                if (event == UsageEvents.Event.ACTIVITY_STOPPED + 1)
                    activityRecords.remove(record.getToken());

                if (appRecord.getAppState().getActivities().isEmpty()) {
                    if (appRecord.getAppState().setVisible(false)) {
                        Log.d(appRecord.getPackageNameWithUser() + " 进入后台");
                        FreezerHandler.sendFreezeMessage(appRecord, 5000);
                    }
                }
            }
        };
    }

    /**
     * 返回结果 / 启动 Activity 前按 token 解冻调用方.
     *
     * <p>调用方在 startActivityForResult 后会先 PAUSED 再 STOPPED; 如果用户在选择器里
     * 停留超过冻结延迟, 调用方已经被冻住. 系统把结果送回前必须先解冻, 否则结果投递
     * 事务会排在冻结进程后面, 文件管理器看起来一直卡住.
     */
    public static void thawForToken(IBinder token, String reason) {
        if (token == null)
            return;
        ActivityRecord activityRecord = activityRecords.get(token);
        if (activityRecord == null)
            return;
        AppRecord appRecord = activityRecord.toAppRecord();
        if (appRecord != null)
            FreezerService.temporaryUnfreezeIfNeed(appRecord, reason, 10_000);
    }
}
