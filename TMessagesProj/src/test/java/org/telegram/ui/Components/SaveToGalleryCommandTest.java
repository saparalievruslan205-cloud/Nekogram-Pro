package org.telegram.ui.Components;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import tw.nekomimi.nekogram.SaveToDownloadReceiver;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        SaveToGalleryCommandTest.ShadowBulletinFactory.class,
        SaveToGalleryCommandTest.ShadowBulletin.class,
        SaveToGalleryCommandTest.ShadowLocale.class,
        SaveToGalleryCommandTest.ShadowSaveNotification.class
})
public class SaveToGalleryCommandTest {
    private Activity activity;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        ShadowBulletinFactory.shown = 0;
        ShadowLocale.resultId = 0;
    }

    @Test
    @Config(sdk = {23, 28})
    public void legacyStorageRequiresPermissionUntilGranted() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertTrue(SaveToGalleryCommand.requiresWritePermission(activity));
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertFalse(SaveToGalleryCommand.requiresWritePermission(activity));
    }

    @Test
    @Config(sdk = {29, 33})
    public void mediaStoreSavingDoesNotRequireLegacyOrReadMediaPermissions() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
                Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        assertFalse(SaveToGalleryCommand.requiresWritePermission(activity));
    }

    @Test
    public void completionReportsCountsOnceAndReleasesWeakTarget() {
        TrackingFragment fragment = fragment();
        SaveToGalleryCommand command = new SaveToGalleryCommand(fragment);
        command.onFinished(3, 2, false);
        assertEquals(R.string.GalleryBatchSaveSummary, ShadowLocale.resultId);
        assertArrayEquals(new Object[]{3, 2}, ShadowLocale.arguments);
        assertEquals(1, ShadowBulletinFactory.shown);
        command.onFinished(3, 2, false);
        command.onProgress(null, 10, 20, false);
        assertEquals(1, ShadowBulletinFactory.shown);
        assertEquals(0, fragment.progressEvents);
        assertNull(target(command).get());
    }

    @Test
    public void cancellationUsesCancelledSummary() {
        TrackingFragment fragment = fragment();
        new SaveToGalleryCommand(fragment).onFinished(1, 0, true);
        assertEquals(R.string.GalleryBatchSaveCancelled, ShadowLocale.resultId);
        assertArrayEquals(new Object[]{1, 0}, ShadowLocale.arguments);
        assertEquals(1, ShadowBulletinFactory.shown);
    }

    @Test
    public void progressReachesLiveScreenWithoutHoldingItInAClosure() throws Exception {
        TrackingFragment fragment = fragment();
        SaveToGalleryCommand command = new SaveToGalleryCommand(fragment);
        command.onProgress(null, 17, 40, true);
        assertEquals(1, fragment.progressEvents);
        assertEquals(17, fragment.loaded);
        assertEquals(40, fragment.total);
        assertTrue(fragment.active);
        // The only instance field is the weak target, never a bound callback or View.
        for (java.lang.reflect.Field field : SaveToGalleryCommand.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                assertEquals(WeakReference.class, field.getType());
            }
        }
        target(command).clear();
        command.onProgress(null, 40, 40, false);
        command.onFinished(1, 0, false);
        assertEquals(1, fragment.progressEvents);
        assertEquals(0, ShadowBulletinFactory.shown);
    }

    @Test
    public void finishingOrDestroyedFragmentReceivesNoUiUpdates() {
        TrackingFragment fragment = fragment();
        fragment.closing = true;
        assertNoUi(fragment);
        fragment.closing = false;
        fragment.isFinished = true;
        assertNoUi(fragment);
    }

    @Test
    public void detachedFragmentAndFinishedActivityReceiveNoUiUpdates() {
        TrackingFragment fragment = fragment();
        fragment.activity = null;
        assertNoUi(fragment);
        fragment.activity = activity;
        activity.finish();
        assertNoUi(fragment);
    }

    @Test
    public void destroyedActivityReceivesNoUiUpdates() {
        org.robolectric.android.controller.ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        TrackingFragment fragment = fragment();
        fragment.activity = controller.get();
        controller.pause().stop().destroy();
        assertNoUi(fragment);
    }

    @Test
    public void sharedMediaDenialOrCancelledPermissionClearsPendingSave() throws Exception {
        SharedMediaLayout layout = withoutUiConstruction(SharedMediaLayout.class);
        for (int[] result : new int[][]{{PackageManager.PERMISSION_DENIED}, {}, null}) {
            ReflectionHelpers.setField(layout, "pendingGallerySaveMessages", new ArrayList<>(Collections.singletonList(null)));
            assertTrue(layout.onGallerySavePermissionsResult(731, result));
            assertNull(ReflectionHelpers.getField(layout, "pendingGallerySaveMessages"));
            // A later grant must not resurrect the denied operation.
            assertTrue(layout.onGallerySavePermissionsResult(731, new int[]{PackageManager.PERMISSION_GRANTED}));
        }
        assertFalse(layout.onGallerySavePermissionsResult(99, null));
    }

    @Test
    public void chatDenialOrCancelledPermissionClearsPendingSave() throws Exception {
        ChatActivity chat = withoutUiConstruction(ChatActivity.class);
        for (int[] result : new int[][]{{PackageManager.PERMISSION_DENIED}, {}, null}) {
            ReflectionHelpers.setField(chat, "pendingGallerySaveMessages", new ArrayList<>(Collections.singletonList(null)));
            chat.onRequestPermissionsResultFragment(4, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, result);
            assertNull(ReflectionHelpers.getField(chat, "pendingGallerySaveMessages"));
            chat.onRequestPermissionsResultFragment(4, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, new int[]{PackageManager.PERMISSION_GRANTED});
        }
    }

    @Test
    public void workerReleasesCallbacksBeforeCompletionAndDropsLateProgress() throws Exception {
        AtomicReference<Object> worker = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger progress = new AtomicInteger();
        MessageObject marker = new MessageObject(0, new org.telegram.tgnet.TLRPC.TL_message(), "Test", "", "", true, false, false, false);
        ArrayList<MessageObject> messages = new ArrayList<>(Collections.singletonList(marker));
        MediaController.GallerySaveCallback completion = (saved, failed, cancelled) -> {
            assertNull(ReflectionHelpers.getField(worker.get(), "callback"));
            assertNull(ReflectionHelpers.getField(worker.get(), "progressCallback"));
            completions.incrementAndGet();
        };
        worker.set(worker(messages, completion, (message, loaded, total, active) -> progress.incrementAndGet()));
        Method notify = worker.get().getClass().getDeclaredMethod("notifySaveProgress", MessageObject.class, long.class, long.class, boolean.class);
        notify.setAccessible(true);
        Method finish = worker.get().getClass().getDeclaredMethod("finish");
        finish.setAccessible(true);
        finish.invoke(worker.get());
        finish.invoke(worker.get());
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread background = new Thread(() -> {
            try {
                notify.invoke(worker.get(), messages.get(0), 10L, 10L, false);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        background.start();
        background.join();
        assertNull(error.get());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, completions.get());
        assertEquals(0, progress.get());
        assertTrue(messages.isEmpty());
    }

    private Object worker(ArrayList<MessageObject> messages, MediaController.GallerySaveCallback completion,
                          MediaController.GallerySaveProgressCallback progress) throws Exception {
        Class<?> type = Class.forName(MediaController.class.getName() + "$GalleryMediaSaver");
        Constructor<?> constructor = type.getDeclaredConstructor(android.content.Context.class, AccountInstance.class,
                ArrayList.class, MediaController.GallerySaveCallback.class, MediaController.GallerySaveProgressCallback.class);
        constructor.setAccessible(true);
        return constructor.newInstance(activity.getApplicationContext(), new AccountInstance(0), messages, completion, progress);
    }

    private TrackingFragment fragment() {
        // Bypass Telegram's native account initialization; exercise real callback code.
        TrackingFragment fragment = Shadow.newInstanceOf(TrackingFragment.class);
        fragment.activity = activity;
        return fragment;
    }

    private static <T> T withoutUiConstruction(Class<T> type) throws Exception {
        // These permission handlers only need their pending field. Constructing the full
        // screens would start native account services and build unrelated view hierarchies.
        Class<?> allocatorType = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = allocatorType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(allocatorType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private void assertNoUi(TrackingFragment fragment) {
        SaveToGalleryCommand command = new SaveToGalleryCommand(fragment);
        command.onProgress(null, 1, 2, true);
        command.onFinished(1, 0, false);
        assertEquals(0, fragment.progressEvents);
        assertEquals(0, ShadowBulletinFactory.shown);
    }

    private WeakReference<BaseFragment> target(SaveToGalleryCommand command) {
        return ReflectionHelpers.getField(command, "fragmentRef");
    }

    public static class TrackingFragment extends BaseFragment implements SaveToGalleryCommand.ProgressTarget {
        Activity activity;
        boolean closing;
        int progressEvents;
        long loaded, total;
        boolean active;
        @Override public Activity getParentActivity() { return activity; }
        @Override public boolean isFinishing() { return closing; }
        @Override public void updateGallerySaveProgress(MessageObject message, long loaded, long total, boolean active) {
            progressEvents++;
            this.loaded = loaded;
            this.total = total;
            this.active = active;
        }
    }

    @Implements(value = BulletinFactory.class, isInAndroidSdk = false)
    public static class ShadowBulletinFactory {
        static int shown;
        @Implementation protected static BulletinFactory of(BaseFragment fragment) {
            return Shadow.newInstanceOf(BulletinFactory.class);
        }
        @Implementation protected Bulletin createSimpleBulletin(int icon, CharSequence title, CharSequence subtitle) {
            return Shadow.newInstanceOf(Bulletin.class);
        }
    }

    @Implements(value = Bulletin.class, isInAndroidSdk = false)
    public static class ShadowBulletin {
        @Implementation protected Bulletin show() {
            ShadowBulletinFactory.shown++;
            return null;
        }
    }

    @Implements(value = LocaleController.class, isInAndroidSdk = false)
    public static class ShadowLocale {
        static int resultId;
        static Object[] arguments;
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static String formatString(int id, Object... args) {
            resultId = id;
            arguments = args;
            return "Saved";
        }
    }

    @Implements(value = SaveToDownloadReceiver.class, isInAndroidSdk = false)
    public static class ShadowSaveNotification {
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static int createNotificationId() { return 1; }
        @Implementation protected static void cancelNotification(int id) { }
    }
}
