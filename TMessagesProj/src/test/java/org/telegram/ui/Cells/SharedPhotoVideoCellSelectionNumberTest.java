package org.telegram.ui.Cells;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CheckBoxBase;
import org.telegram.ui.Components.MediaPreviewLifecycleTest;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        MediaPreviewLifecycleTest.ShadowTheme.class,
        MediaPreviewLifecycleTest.ShadowLocaleController.class
})
public class SharedPhotoVideoCellSelectionNumberTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.density = 1f;
        AndroidUtilities.displaySize.set(360, 800);
    }

    @Test
    public void cell2DisplaysAndRenumbersSelectionOrder() throws Exception {
        SharedPhotoVideoCell2.SharedResources resources =
                new SharedPhotoVideoCell2.SharedResources(RuntimeEnvironment.getApplication(), null);
        SharedPhotoVideoCell2 cell = new SharedPhotoVideoCell2(RuntimeEnvironment.getApplication(), resources, 0);

        cell.setChecked(0, true, false);
        assertNotNull(cell.checkBoxBase);
        assertTrue(cell.checkBoxBase.isChecked());
        assertEquals("1", getCheckedText(cell.checkBoxBase));

        // Renumber while remaining checked (e.g. after reordering or deselecting an earlier item)
        cell.setChecked(2, true, false);
        assertTrue(cell.checkBoxBase.isChecked());
        assertEquals("3", getCheckedText(cell.checkBoxBase));

        cell.setChecked(1, true, false);
        assertTrue(cell.checkBoxBase.isChecked());
        assertEquals("2", getCheckedText(cell.checkBoxBase));

        // Unchecking non-animated (recycling) clears the stale selection number
        cell.setChecked(false, false);
        assertFalse(cell.checkBoxBase.isChecked());
        assertNull(getCheckedText(cell.checkBoxBase));

        // Reusing the same recycled cell in unnumbered mode (e.g. stories/cache) shows checkmark, not stale digit
        cell.setChecked(true, false);
        assertTrue(cell.checkBoxBase.isChecked());
        assertNull(getCheckedText(cell.checkBoxBase));
    }

    @Test
    public void legacySharedPhotoVideoCellDisplaysAndRenumbersSelectionOrder() throws Exception {
        SharedPhotoVideoCell cell = new SharedPhotoVideoCell(RuntimeEnvironment.getApplication());
        cell.setItemsCount(2);

        cell.setChecked(0, 1, true, false);
        CheckBoxBase checkBoxBase = getLegacyCheckBoxBase(cell, 0);
        assertTrue(checkBoxBase.isChecked());
        assertEquals("2", getCheckedText(checkBoxBase));

        cell.setChecked(0, 0, true, false);
        assertTrue(checkBoxBase.isChecked());
        assertEquals("1", getCheckedText(checkBoxBase));

        cell.setChecked(0, false, false);
        assertFalse(checkBoxBase.isChecked());
        assertNull(getCheckedText(checkBoxBase));

        cell.setChecked(0, true, false);
        assertTrue(checkBoxBase.isChecked());
        assertNull(getCheckedText(checkBoxBase));
    }

    private static String getCheckedText(CheckBoxBase checkBoxBase) throws Exception {
        Field field = CheckBoxBase.class.getDeclaredField("checkedText");
        field.setAccessible(true);
        return (String) field.get(checkBoxBase);
    }

    private static CheckBoxBase getLegacyCheckBoxBase(SharedPhotoVideoCell cell, int index) throws Exception {
        SharedPhotoVideoCell.PhotoVideoView itemView = cell.getView(index);
        assertNotNull(itemView);
        Field field = SharedPhotoVideoCell.PhotoVideoView.class.getDeclaredField("checkBox");
        field.setAccessible(true);
        CheckBox2 checkBox = (CheckBox2) field.get(itemView);
        assertNotNull(checkBox);
        return checkBox.getCheckBoxBase();
    }
}
