package nep.timeline.cirno.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 文件选择器保护名单的纯字符串测试. */
public class FilePickerGuardTest {
    @Test
    public void recognizesCommonFilePickers() {
        assertTrue(FilePickerGuard.isFilePicker("com.android.documentsui"));
        assertTrue(FilePickerGuard.isFilePicker("com.miui.filemanager"));
        assertTrue(FilePickerGuard.isFilePicker("com.coloros.filemanager"));
        assertTrue(FilePickerGuard.isFilePicker("com.oplus.filemanager"));
        assertTrue(FilePickerGuard.isFilePicker("bin.mt.plus"));
    }

    @Test
    public void doesNotProtectOrdinaryApps() {
        assertFalse(FilePickerGuard.isFilePicker("com.tencent.mm"));
        assertFalse(FilePickerGuard.isFilePicker("com.example.app"));
        assertFalse(FilePickerGuard.isFilePicker(null));
    }
}
