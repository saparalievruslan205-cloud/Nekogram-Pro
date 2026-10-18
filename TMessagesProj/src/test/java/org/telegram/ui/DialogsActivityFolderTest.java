package org.telegram.ui;

import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.FilterTabsViewStateTest;
import org.telegram.ui.Components.MediaPreviewLifecycleTest;

import java.lang.reflect.Proxy;
import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        MediaPreviewLifecycleTest.ShadowTheme.class,
        MediaPreviewLifecycleTest.ShadowLocaleController.class,
        DialogsActivityFolderTest.ShadowBaseFragment.class,
        DialogsActivityFolderTest.ShadowMessagesController.class
})
public class DialogsActivityFolderTest {
    private TestDialogsActivity main;
    private DialogsActivity.ViewPage page;
    private FilterTabsView tabs;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        main = new TestDialogsActivity();
        page = main.new ViewPage(RuntimeEnvironment.getApplication());
        ReflectionHelpers.setField(main, "viewPages", new DialogsActivity.ViewPage[]{page});
        ReflectionHelpers.setField(page, "selectedType", 2);
        ReflectionHelpers.setField(page, "dialogsType", DialogsActivity.DIALOGS_TYPE_FOLDER1);
        state().selectFilter(77);
        state().saveScroll(3, -17, -300);
        tabs = new FilterTabsView(RuntimeEnvironment.getApplication(), key -> 0xff000000);
        tabs.setDelegate(new FilterTabsViewStateTest.PassiveTabsDelegate());
        tabs.addTab(0, 0, "All", false, true, false);
        tabs.addTab(1, 101, "Work", false, false, false);
        tabs.addTab(2, 202, "Groups", false, false, false);
        tabs.selectTabWithStableId(202);
        ReflectionHelpers.setField(main, "filterTabsView", tabs);
        ReflectionHelpers.setField(main, "isPaused", true);
        ShadowMessagesController.filters = new ArrayList<>();
    }

    @Test
    public void pausedMainDefersAllSavedMessagesReloadNotifications() {
        // The fixture deliberately has no adapter: hidden notifications must not touch it.
        for (int i = 0; i < 3; i++) {
            main.didReceivedNotification(NotificationCenter.dialogsNeedReload, 0, true);
            main.didReceivedNotification(NotificationCenter.updateInterfaces, 0, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
            main.didReceivedNotification(NotificationCenter.dialogFiltersUpdated, 0);
        }
        assertReturnState();
        assertEquals(0, main.controllerReads);
    }

    @Test
    public void coveredMainDefersUpdatesEvenBeforeOnPause() {
        ReflectionHelpers.setField(main, "isPaused", false);
        BaseFragment chat = new BaseFragment() { };
        INavigationLayout navigation = (INavigationLayout) Proxy.newProxyInstance(
                INavigationLayout.class.getClassLoader(), new Class<?>[]{INavigationLayout.class},
                (proxy, method, args) -> method.getName().equals("getLastFragment") ? chat : null);
        ReflectionHelpers.setField(main, "parentLayout", navigation);
        main.didReceivedNotification(NotificationCenter.dialogsNeedReload, 0, true);
        main.didReceivedNotification(NotificationCenter.dialogFiltersUpdated, 0);
        assertReturnState();
        assertEquals(0, main.controllerReads);
    }

    @Test
    public void filterReadsRemainLocalWhenPickerReplacesOrClearsControllerSlot() {
        MessagesController.DialogFilter expected = filter(77);
        MessagesController.DialogFilter other = filter(42);
        ShadowMessagesController.filters.add(other);
        ShadowMessagesController.filters.add(expected);
        main.controller = new MessagesController(0);
        main.controller.selectedDialogFilter = new MessagesController.DialogFilter[]{other, null};
        TestDialogsActivity picker = new TestDialogsActivity();
        picker.controller = main.controller;
        ReflectionHelpers.setField(picker, "onlySelect", true);
        DialogsActivity.ViewPage pickerPage = picker.new ViewPage(RuntimeEnvironment.getApplication());
        ReflectionHelpers.setField(picker, "viewPages", new DialogsActivity.ViewPage[]{pickerPage});
        ReflectionHelpers.setField(pickerPage, "dialogsType", DialogsActivity.DIALOGS_TYPE_FOLDER1);
        DialogsFolderState pickerState = ReflectionHelpers.getField(pickerPage, "folderState");
        pickerState.selectFilter(42);
        assertSame(other, picker.getDialogFilterForDialogsType(DialogsActivity.DIALOGS_TYPE_FOLDER1));
        assertSame(expected, main.getDialogFilterForDialogsType(DialogsActivity.DIALOGS_TYPE_FOLDER1));
        main.controller.selectedDialogFilter[0] = null;
        assertSame(expected, main.getDialogFilterForDialogsType(DialogsActivity.DIALOGS_TYPE_FOLDER1));
        assertReturnState();
    }

    private void assertReturnState() {
        assertEquals(77, state().getFilterId());
        assertEquals(2, (int) ReflectionHelpers.getField(page, "selectedType"));
        assertEquals(2, tabs.getCurrentTabId());
        assertEquals(202, tabs.getCurrentTabStableId());
        assertEquals(3, state().resolveScrollPosition(4, i -> i == 3 ? -300 : 0));
        assertEquals(-17, state().getScrollOffset());
    }

    private DialogsFolderState state() {
        return ReflectionHelpers.getField(page, "folderState");
    }

    private static MessagesController.DialogFilter filter(int id) {
        MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
        filter.id = id;
        return filter;
    }

    private static class TestDialogsActivity extends DialogsActivity {
        MessagesController controller;
        int controllerReads;

        TestDialogsActivity() { super(null); }

        @Override
        public MessagesController getMessagesController() {
            controllerReads++;
            if (controller == null) {
                throw new AssertionError("Background dialogs accessed shared controller state");
            }
            return controller;
        }
    }

    @Implements(value = BaseFragment.class, isInAndroidSdk = false)
    public static class ShadowBaseFragment {
        @Implementation protected void __constructor__(Bundle args) { }
    }

    @Implements(value = MessagesController.class, isInAndroidSdk = false)
    public static class ShadowMessagesController {
        static ArrayList<MessagesController.DialogFilter> filters;
        @Implementation protected void __constructor__(int account) { }
        @Implementation protected ArrayList<MessagesController.DialogFilter> getDialogFilters() { return filters; }
    }
}
