package tw.nekomimi.nekogram.helpers;

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
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.MediaPreviewLifecycleTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        MediaPreviewLifecycleTest.ShadowTheme.class,
        MediaPreviewLifecycleTest.ShadowLocaleController.class
})
public class SelectionMediaGestureControllerTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.density = 1f;
        AndroidUtilities.displaySize.set(360, 800);
    }

    private static final class FakeScheduler implements SelectionMediaGestureController.Scheduler {
        private static final class ScheduledTask {
            final Runnable runnable;
            final long delayMs;

            ScheduledTask(Runnable runnable, long delayMs) {
                this.runnable = runnable;
                this.delayMs = delayMs;
            }
        }

        final List<ScheduledTask> pending = new ArrayList<>();

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            pending.add(new ScheduledTask(runnable, delayMs));
        }

        @Override
        public void cancel(Runnable runnable) {
            pending.removeIf(task -> task.runnable == runnable);
        }

        void fireAll() {
            List<ScheduledTask> copy = new ArrayList<>(pending);
            pending.clear();
            for (ScheduledTask task : copy) {
                task.runnable.run();
            }
        }
    }

    @Test
    public void holdingPastDelayOpensMediaOnceAndIgnoresSubsequentUpOrDrag() {
        FakeScheduler scheduler = new FakeScheduler();
        SelectionMediaGestureController controller = new SelectionMediaGestureController(scheduler, 850L);
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger dragCount = new AtomicInteger();

        controller.arm(openCount::incrementAndGet, dragCount::incrementAndGet);
        assertTrue(controller.isActive());
        assertEquals(1, scheduler.pending.size());
        assertEquals(850L, scheduler.pending.get(0).delayMs);

        scheduler.fireAll();

        assertEquals(1, openCount.get());
        assertEquals(0, dragCount.get());
        assertFalse(controller.isActive());

        // Subsequent drag or finger release must not reopen or start drag
        assertFalse(controller.onDragStarted());
        assertFalse(controller.onGestureEnded(false, false));
        assertEquals(1, openCount.get());
        assertEquals(0, dragCount.get());
    }

    @Test
    public void draggingBeforeHoldDelayCancelsOpenAndTriggersAnchorSelection() {
        FakeScheduler scheduler = new FakeScheduler();
        SelectionMediaGestureController controller = new SelectionMediaGestureController(scheduler, 850L);
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger dragCount = new AtomicInteger();

        controller.arm(openCount::incrementAndGet, dragCount::incrementAndGet);
        assertTrue(controller.onDragStarted());

        assertEquals(0, openCount.get());
        assertEquals(1, dragCount.get());
        assertTrue(scheduler.pending.isEmpty());
        assertFalse(controller.isActive());

        // Releasing after drag must not open media
        assertFalse(controller.onGestureEnded(true, false));
        assertEquals(0, openCount.get());
    }

    @Test
    public void releasingAfterLongPressWithoutDraggingOpensMediaAndCancelsTimer() {
        FakeScheduler scheduler = new FakeScheduler();
        SelectionMediaGestureController controller = new SelectionMediaGestureController(scheduler, 850L);
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger dragCount = new AtomicInteger();

        controller.arm(openCount::incrementAndGet, dragCount::incrementAndGet);
        assertTrue(controller.onGestureEnded(false, false));

        assertEquals(1, openCount.get());
        assertEquals(0, dragCount.get());
        assertTrue(scheduler.pending.isEmpty());

        // Firing any stale runnable does nothing
        scheduler.fireAll();
        assertEquals(1, openCount.get());
    }

    @Test
    public void actionCancelOrLifecycleCancelAbortsWithoutOpeningOrSelecting() {
        FakeScheduler scheduler = new FakeScheduler();
        SelectionMediaGestureController controller = new SelectionMediaGestureController(scheduler, 850L);
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger dragCount = new AtomicInteger();

        controller.arm(openCount::incrementAndGet, dragCount::incrementAndGet);
        assertFalse(controller.onGestureEnded(false, true));
        assertEquals(0, openCount.get());
        assertEquals(0, dragCount.get());
        assertTrue(scheduler.pending.isEmpty());

        controller.arm(openCount::incrementAndGet, dragCount::incrementAndGet);
        controller.cancel();
        scheduler.fireAll();
        assertFalse(controller.onGestureEnded(false, false));
        assertEquals(0, openCount.get());
        assertEquals(0, dragCount.get());
    }

    @Test
    public void previewableMediaPredicateAcceptsPhotoVideoGifAndRejectsOthers() {
        MessageObject photo = makeMessage(MessageObject.TYPE_PHOTO, new TLRPC.TL_messageMediaPhoto());
        assertTrue(SelectionMediaGestureController.isPreviewableChatMedia(photo));

        TLRPC.TL_messageMediaDocument videoMedia = new TLRPC.TL_messageMediaDocument();
        videoMedia.document = new TLRPC.TL_document();
        videoMedia.document.mime_type = "video/mp4";
        TLRPC.TL_documentAttributeVideo videoAttr = new TLRPC.TL_documentAttributeVideo();
        videoMedia.document.attributes.add(videoAttr);
        MessageObject video = makeMessage(MessageObject.TYPE_VIDEO, videoMedia);
        assertTrue(SelectionMediaGestureController.isPreviewableChatMedia(video));

        TLRPC.TL_messageMediaDocument gifMedia = new TLRPC.TL_messageMediaDocument();
        gifMedia.document = new TLRPC.TL_document();
        gifMedia.document.mime_type = "video/mp4";
        gifMedia.document.attributes.add(new TLRPC.TL_documentAttributeAnimated());
        MessageObject gif = makeMessage(MessageObject.TYPE_GIF, gifMedia);
        assertTrue(SelectionMediaGestureController.isPreviewableChatMedia(gif));

        // Round video is excluded
        TLRPC.TL_messageMediaDocument roundMedia = new TLRPC.TL_messageMediaDocument();
        roundMedia.document = new TLRPC.TL_document();
        TLRPC.TL_documentAttributeVideo roundAttr = new TLRPC.TL_documentAttributeVideo();
        roundAttr.round_message = true;
        roundMedia.document.attributes.add(roundAttr);
        MessageObject roundVideo = makeMessage(MessageObject.TYPE_ROUND_VIDEO, roundMedia);
        assertFalse(SelectionMediaGestureController.isPreviewableChatMedia(roundVideo));

        // Plain text message is excluded
        MessageObject text = makeMessage(MessageObject.TYPE_TEXT, null);
        assertFalse(SelectionMediaGestureController.isPreviewableChatMedia(text));

        // Sending / failed / sponsored messages are excluded
        MessageObject sendingPhoto = makeMessage(MessageObject.TYPE_PHOTO, new TLRPC.TL_messageMediaPhoto());
        sendingPhoto.messageOwner.id = -1;
        sendingPhoto.messageOwner.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
        assertFalse(SelectionMediaGestureController.isPreviewableChatMedia(sendingPhoto));

        MessageObject failedPhoto = makeMessage(MessageObject.TYPE_PHOTO, new TLRPC.TL_messageMediaPhoto());
        failedPhoto.messageOwner.id = -2;
        failedPhoto.messageOwner.send_state = MessageObject.MESSAGE_SEND_STATE_SEND_ERROR;
        assertFalse(SelectionMediaGestureController.isPreviewableChatMedia(failedPhoto));
    }

    @Test
    public void spoilerPredicateBlocksSelectionUntilRevealed() {
        TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
        media.spoiler = true;
        MessageObject spoilerPhoto = makeMessage(MessageObject.TYPE_PHOTO, media);

        assertTrue(SelectionMediaGestureController.hasUnrevealedMediaSpoiler(spoilerPhoto));

        spoilerPhoto.isMediaSpoilersRevealed = true;
        assertFalse(SelectionMediaGestureController.hasUnrevealedMediaSpoiler(spoilerPhoto));

        MessageObject normalPhoto = makeMessage(MessageObject.TYPE_PHOTO, new TLRPC.TL_messageMediaPhoto());
        assertFalse(SelectionMediaGestureController.hasUnrevealedMediaSpoiler(normalPhoto));
    }

    private static MessageObject makeMessage(int type, TLRPC.MessageMedia media) {
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = 42;
        msg.media = media;
        MessageObject obj = new MessageObject(0, msg, "", "", "", false, false, false, false);
        obj.type = type;
        return obj;
    }
}
