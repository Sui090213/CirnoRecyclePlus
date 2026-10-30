package nep.timeline.cirno.configs.settings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ApplicationSettings {
    /**
     * 白名单应用 (packageName#userId).
     *
     * <p>注意: 必须用可变集合. 旧代码用 Set.of(...) 创建的是不可变集合, 首次运行
     * (配置文件还没生成) 时在界面上加白名单会直接抛 UnsupportedOperationException.
     */
    public Set<String> whiteApps = new LinkedHashSet<>(Set.of("com.tencent.mm#0", "com.tencent.mm#999"));

    /**
     * 每个应用单独的冻结选项, key 是 {@code packageName#userId}.
     *
     * <p>注意: 必须用可变 Map 且默认非 null. Gson 反序列化时如果 JSON 里是 null,
     * 会把这里的默认值覆盖成 null, 所以读取方 (AppConfigs / AppOptionsStore) 都要做兜底.
     */
    public Map<String, AppOptions> appOptions = new LinkedHashMap<>();
}
