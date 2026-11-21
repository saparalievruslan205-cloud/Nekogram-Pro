package org.telegram.ui.Cells;

import android.app.Application;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.MediaPreviewLifecycleTest;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        MediaPreviewLifecycleTest.ShadowTheme.class,
        MediaPreviewLifecycleTest.ShadowLocaleController.class
})
public class TextSettingsCellLayoutTest {
    private boolean savedRtl;

    @Before
    public void setUp() {
        savedRtl = LocaleController.isRTL;
        LocaleController.isRTL = false;
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.density = 1f;
        AndroidUtilities.displaySize.set(360, 800);
    }

    @After
    public void tearDown() {
        LocaleController.isRTL = savedRtl;
    }

    @Test
    public void longValueSetBeforeMeasureDoesNotOverlapOrOverflowCell() {
        TextSettingsCell cell = new TextSettingsCell(RuntimeEnvironment.getApplication(), key -> Color.BLACK);
        cell.setBetterLayout(false);
        cell.setTextAndValue(
                "Внешний протокол потоковой передачи",
                "Внешний поставщик контента по протоколу потоковой передачи",
                false,
                false
        );

        int cellWidth = 360;
        measureAndLayout(cell, cellWidth);

        View title = cell.getTextView();
        View value = cell.getValueTextView();
        assertTrue("Title must remain visible with positive width", title.getMeasuredWidth() > 0);
        assertTrue("Value must stay within cell bounds", value.getLeft() >= 0 && value.getRight() <= cellWidth);
        assertTrue("Title and value must keep a clear gap without overlapping",
                title.getRight() + AndroidUtilities.dp(12) <= value.getLeft());
    }

    @Test
    public void streamingRowFitsWithClearGap() {
        TextSettingsCell cell = new TextSettingsCell(RuntimeEnvironment.getApplication(), key -> Color.BLACK);
        cell.setBetterLayout(false);
        cell.setTextAndValue("Протокол внешнего стриминга", "Нативный", false, false);

        int cellWidth = 360;
        measureAndLayout(cell, cellWidth);

        View title = cell.getTextView();
        View value = cell.getValueTextView();
        assertTrue(title.getMeasuredWidth() > 0);
        assertTrue(value.getMeasuredWidth() > 0);
        assertTrue(value.getRight() <= cellWidth);
        assertTrue(title.getRight() + AndroidUtilities.dp(12) <= value.getLeft());
    }

    @Test
    public void shrinkingCellWidthRelayoutsAnimatedValue() {
        TextSettingsCell cell = new TextSettingsCell(RuntimeEnvironment.getApplication(), key -> Color.BLACK);
        cell.setBetterLayout(false);
        cell.setTextAndValue(
                "Протокол внешнего стриминга",
                "Очень длинное значение настройки для проверки сжатия ширины",
                false,
                false
        );

        measureAndLayout(cell, 480);
        int wideValueWidth = cell.getValueTextView().getMeasuredWidth();

        measureAndLayout(cell, 300);
        View title = cell.getTextView();
        View value = cell.getValueTextView();
        assertTrue("Value should shrink when cell narrows", value.getMeasuredWidth() <= wideValueWidth);
        assertTrue("Value must remain inside narrow cell", value.getLeft() >= 0 && value.getRight() <= 300);
        assertTrue("Title and value must not overlap in narrow cell",
                title.getRight() + AndroidUtilities.dp(12) <= value.getLeft());
    }

    @Test
    public void rtlLayoutKeepsGapWithoutOverlap() {
        LocaleController.isRTL = true;
        TextSettingsCell cell = new TextSettingsCell(RuntimeEnvironment.getApplication(), key -> Color.BLACK);
        cell.setBetterLayout(false);
        cell.setTextAndValue(
                "Внешний протокол потоковой передачи",
                "Внешний поставщик контента по протоколу потоковой передачи",
                false,
                false
        );

        measureAndLayout(cell, 360);

        View title = cell.getTextView();
        View value = cell.getValueTextView();
        assertTrue(title.getMeasuredWidth() > 0);
        assertTrue(value.getLeft() >= 0 && value.getRight() <= 360);
        assertTrue(value.getRight() + AndroidUtilities.dp(12) <= title.getLeft());
    }

    private static void measureAndLayout(TextSettingsCell cell, int widthPx) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        cell.measure(widthSpec, heightSpec);
        cell.layout(0, 0, cell.getMeasuredWidth(), cell.getMeasuredHeight());
    }
}
