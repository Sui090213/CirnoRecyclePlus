package nep.timeline.cirno;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public interface CommonConstants {
    String NATIVE_PACKAGE_NAME = BuildConfig.APPLICATION_ID;
    String SHELL = "com.android.shell";
    String ANDROID = "android";

    /*
     * 注意: 不要再用 Set.of(...) 初始化这里.
     *
     * Set.of 在参数里有重复元素时会直接在类初始化阶段抛
     * IllegalArgumentException("duplicate element: ..."), 整个进程会变成
     * ExceptionInInitializerError 闪退. v1.2.1 就因为 app.revanced.android.gms
     * 写了两遍导致 App / system_server 一加载这个类就崩.
     *
     * 这里用 List.of(...) -> LinkedHashSet 构造: 重复项会被静默去重, 不会崩;
     * 最后再用 Collections.unmodifiableSet 包一层, 保持和以前一样的只读语义.
     */
    Set<String> whiteApps = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            NATIVE_PACKAGE_NAME,
            ANDROID,
            SHELL,
            "moe.shizuku.privileged.api", /* Shizuku */
            "com.google.android.gms", /* Google Play 服务: FCM 长连接 / 推送投递, 冻结它等于切断 FCM */
            "com.google.android.gsf", /* 谷歌服务框架 */
            "com.android.vending", /* Play 商店: GMS 推送依赖它更新/登录状态 */
            "com.google.android.configupdater", /* Google 配置更新 */
            "com.google.android.ext.services", /* Google 核心服务组件 */
            "com.google.android.ext.shared", /* Google 核心服务组件 */
            "com.google.android.contactkeys", /* Android System Key Verifier */
            "com.google.android.safetycore", /* Android System SafetyCore */
            "com.google.android.apps.weather", /* Pixel 天气 */
            "com.google.android.googlequicksearchbox", /* Google */
            "app.revanced.android.gms", /* MicroG */
            "app.revanced.android.gsf", /* MicroG GSF */
            "com.microg.vending", /* MicroG 商店 */
            "com.xiaomi.xmsf", /* 小米服务框架 */
            "com.huawei.hwid", /* HMS核心 */
            "io.heckel.ntfy", /* NTFY */
            "com.xiaomi.smarthome", /* 米家 */
            "com.mfashiongallery.emag", /* 小米画报 */
            "com.miui.miwallpaper.moon", /* 月球超级壁纸 */
            "com.miui.miwallpaper.snowmountain", /* 雪山超级壁纸 */
            "com.miui.miwallpaper.saturn", /* 土星超级壁纸 */
            "com.miui.miwallpaper.geometry", /* 几何超级壁纸 */
            "com.miui.miwallpaper.mars", /* 火星超级壁纸 */
            "com.miui.miwallpaper.earth", /* 地球超级壁纸 */
            "com.xiaomi.mibrain.speech", /* 系统语音引擎 */
            "com.miui.securitymanager", /* 手机管家 */
            "com.miui.home", /* 小米桌面 */
            "com.mi.android.globallauncher", /* POCO桌面 */
            "com.miui.gallery", /* 小米相册 */
            "com.miui.mediaeditor", /* 小米相册 - 编辑 */
            "com.android.deskclock", /* 时钟 */
            "com.android.soundrecorder", /* 录音机 */
            "com.android.calendar", /* 日历 */
            "com.miui.screenrecorder", /* 小米屏幕录制 */
            "com.miui.weather2", /* 小米天气 */
            "com.miui.notes", /* 小米笔记 */
            "com.mi.earphone", /* 小米耳机 */
            "com.mi.health", /* 小米健康 */
            "com.edith.os", /* OPPO Edith OS */
            "com.coloros.alarmclock", /* OPPO时钟 */
            "com.coloros.soundrecorder", /* OPPO录音机 */
            "com.coloros.calendar", /* OPPO日历 */
            "com.unionpay.tsmservice", /* 银联可信服务安全组件 */
            "com.finshell.wallet", /* OPPO钱包 */
            "com.coloros.translate", /* OPPO语音翻译 */
            "com.heytap.health", /* OPPO健康 */
            "com.heytap.uwbconnect", /* OPPO云 */
            "com.coloros.familyguard", /* OPPO家人守护 */
            "com.coloros.oppopods", /* OPPO耳机 */
            "com.coloros.shortcuts", /* 小布指令 */
            "com.coloros.compass2", /* OPPO指南针 */
            "com.coloros.note", /* OPPO便签 */
            "com.coloros.filemanager", /* OPPO文件管理 */
            "com.coloros.backuprestore", /* OPPO手机搬家 */
            "com.coloros.weather2", /* OPPO天气 */
            "com.color.otaassistant", /* OPPO OTA助手 */
            "com.oplus.caseflash", /* 一加CaseFlash */
            "com.oplus.riderMode", /* 一加骑手模式 */
            "andes.oplus.documentsreader", /* 一加文档 */
            "com.oplus.onet", /* 一加O+互联 */
            "com.oplus.screenrecorder", /* 一加录屏 */
            "com.oplus.games", /* 一加游戏助手 */
            "com.oplus.consumerIRApp", /* 一加红外遥控 */
            "com.oplus.melody", /* OPPO无线耳机 */
            "com.oneplus.brickmode", /* 一加禅定空间 */
            "com.oneplus.ctsprepare", /* 一加CTS准备 */
            "com.realme.link", /* 真我互联 */
            "com.realme.linkcn", /* 真我互联大陆 */
            "com.android.email", /* 邮件 */
            "io.github.vvb2060.magisk", /* Magisk Alpha */
            "io.github.vvb2060.magisk.lite", /* Magisk Lite */
            "io.github.huskydg.magisk", /* Magisk Delta / Kitsune Mask */
            "com.topjohnwu.magisk", /* Magisk */
            "com.thehitman7.cygisk", /* Cygisk */
            "com.samsung.android.rampart", /* 自动拦截程序 */
            "com.samsung.android.messaging",
            "com.samsung.android.app.telephonyui",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.smartcallprovider",
            "com.samsung.android.intellivoiceservice",
            "com.qti.qcc",
            "com.sec.epdg",
            "com.sec.imsservice",
            "com.android.systemui", /* 系统界面 */
            "miui.systemui.plugin"  /* 系统界面组件 */
    )));

    /**
     * Google 服务全家桶.
     *
     * <p>这些应用默认在 {@link #whiteApps} 里受保护, 因为冻结 GMS 会切断 FCM 推送.
     * 但并不是所有人都用 FCM: 用户在应用列表里打开对应应用的「允许冻结 Google 服务」后,
     * {@code AppRecord.isProtected()} 会对该应用放行, 之后才允许自动 / 手动冻结.
     *
     * <p>用 LinkedHashSet 构造, 避免将来手滑写重复项导致 Set.of 在类初始化时崩溃.
     */
    Set<String> googleFamilyApps = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.configupdater",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.google.android.contactkeys",
            "com.google.android.safetycore",
            "com.google.android.apps.weather",
            "com.google.android.googlequicksearchbox",
            "app.revanced.android.gms",
            "app.revanced.android.gsf",
            "com.microg.vending"
    )));

    static boolean isWhitelistApps(String packageName) {
        return packageName != null && whiteApps.contains(packageName);
    }

    static boolean isGoogleFamily(String packageName) {
        return packageName != null && googleFamilyApps.contains(packageName);
    }
}
