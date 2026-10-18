package org.telegram.ui.Components;

import android.app.Application;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        MediaPreviewLifecycleTest.ShadowTheme.class,
        MediaPreviewLifecycleTest.ShadowLocaleController.class
})
public class FilterTabsViewStateTest {
    private FilterTabsView tabs;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        tabs = new FilterTabsView(RuntimeEnvironment.getApplication(), key -> Color.BLACK);
        tabs.setDelegate(new PassiveTabsDelegate());
    }

    @Test
    public void stableIdLookupUsesTabIdWhenAllChatsIsHidden() {
        tabs.addTab(1, 101, "Work", false, false, false);
        tabs.addTab(2, 202, "Groups", false, false, false);
        assertEquals(101, tabs.getStableId(1));
        assertEquals(202, tabs.getStableId(2));
        assertEquals(-1, tabs.getStableId(0));
        assertTrue(tabs.selectTabWithStableId(202));
        assertEquals(2, tabs.getCurrentTabId());
    }

    @Test
    public void rebuildWithFewerTabsDoesNotRetainDeletedStableIds() {
        tabs.addTab(0, 0, "All", false, true, false);
        tabs.addTab(1, 101, "Work", false, false, false);
        tabs.addTab(2, 202, "Groups", false, false, false);
        tabs.selectTabWithStableId(202);
        tabs.removeTabs();
        tabs.addTab(0, 0, "All", false, true, false);
        tabs.addTab(1, 101, "Work", false, false, false);
        assertEquals(-1, tabs.getStableId(2));
        assertEquals(-1, tabs.getCurrentTabStableId());
        assertFalse(tabs.selectTabWithStableId(202));
    }

    @Test
    public void stableSelectionSurvivesReorderWithoutPageSelectionCallback() {
        tabs.addTab(0, 0, "All", false, true, false);
        tabs.addTab(1, 101, "Work", false, false, false);
        tabs.addTab(2, 202, "Groups", false, false, false);
        tabs.selectTabWithStableId(202);
        int stableId = tabs.getCurrentTabStableId();
        tabs.removeTabs();
        tabs.addTab(0, 101, "Work", false, false, false);
        tabs.addTab(1, 202, "Groups", false, false, false);
        assertTrue(tabs.selectTabWithStableId(stableId));
        assertEquals(1, tabs.getCurrentTabId());
        assertEquals(202, tabs.getCurrentTabStableId());
        assertFalse(tabs.isAnimatingIndicator());
    }

    public static class PassiveTabsDelegate implements FilterTabsView.FilterTabsViewDelegate {
        @Override public int getTabCounter(int tabId) { return 0; }
        @Override public void onPageSelected(FilterTabsView.Tab tab, boolean forward) { fail("Restoration must not switch pages"); }
        @Override public void onPageScrolled(float progress) { fail("Restoration must not animate pages"); }
        @Override public void onSamePageSelected() { fail("Restoration must not scroll to the top"); }
        @Override public boolean didSelectTab(FilterTabsView.TabView tabView, boolean selected) { return false; }
        @Override public boolean isTabMenuVisible() { return false; }
        @Override public void onDeletePressed(int id) { }
        @Override public void onPageReorder(int fromId, int toId) { }
        @Override public boolean canPerformActions() { return true; }
    }
}
