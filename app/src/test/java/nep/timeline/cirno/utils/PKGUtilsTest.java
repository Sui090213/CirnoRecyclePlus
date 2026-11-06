package nep.timeline.cirno.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 系统分区路径判断 (纯字符串逻辑). */
public class PKGUtilsTest {
    @Test
    public void recognizesSystemPartitionPaths() {
        assertTrue(PKGUtils.isSystemPartitionPath("/system/app/Foo/Foo.apk"));
        assertTrue(PKGUtils.isSystemPartitionPath("/system_ext/priv-app/Foo/Foo.apk"));
        assertTrue(PKGUtils.isSystemPartitionPath("/product/app/Foo/Foo.apk"));
        assertTrue(PKGUtils.isSystemPartitionPath("/vendor/app/Foo/Foo.apk"));
        assertTrue(PKGUtils.isSystemPartitionPath("/odm/app/Foo/Foo.apk"));
        assertTrue(PKGUtils.isSystemPartitionPath("/apex/com.android.foo/Foo.apk"));
    }

    @Test
    public void ignoresDataAndNullPaths() {
        assertFalse(PKGUtils.isSystemPartitionPath("/data/app/~~abc/Foo/base.apk"));
        assertFalse(PKGUtils.isSystemPartitionPath("/mnt/expand/abc/app/Foo/base.apk"));
        assertFalse(PKGUtils.isSystemPartitionPath(null));
        assertFalse(PKGUtils.isSystemPartitionPath(""));
    }
}
