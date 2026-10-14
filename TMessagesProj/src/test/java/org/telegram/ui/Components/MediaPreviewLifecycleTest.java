package org.telegram.ui.Components;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.AlbumButton;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        MediaPreviewLifecycleTest.ShadowImageLoader.class,
        MediaPreviewLifecycleTest.ShadowTheme.class,
        MediaPreviewLifecycleTest.ShadowLocaleController.class
})
public class MediaPreviewLifecycleTest {
    private Activity activity;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void scrollBurstChecksCoverVisibilityOnlyOnNextFrame() {
        CountingAlbumButton button = attachButton();
        button.runFrame();
        int previousChecks = button.visibilityChecks;
        ViewTreeObserver observer = button.getViewTreeObserver();
        for (int i = 0; i < 30; i++) dispatchScroll(observer);
        assertEquals(previousChecks, button.visibilityChecks);
        assertEquals(1, button.frames.size());
        button.runFrame();
        assertEquals(previousChecks + 1, button.visibilityChecks);
        dispatchScroll(observer);
        assertEquals(1, button.frames.size());
    }

    @Test
    public void detachCancelsFrameAndUnsubscribesBeforeReattachment() {
        CountingAlbumButton button = attachButton();
        FrameLayout parent = (FrameLayout) button.getParent();
        ViewTreeObserver oldObserver = button.getViewTreeObserver();
        assertEquals(1, button.frames.size());
        parent.removeView(button);
        assertTrue(button.frames.isEmpty());
        assertFalse(ReflectionHelpers.getField(button, "scrollCheckScheduled"));
        assertNull(ReflectionHelpers.getField(button, "coverObserver"));
        dispatchScroll(oldObserver);
        assertTrue(button.frames.isEmpty());
        parent.addView(button);
        assertEquals(1, button.frames.size());
        button.runFrame();
        dispatchScroll(oldObserver);
        assertEquals(1, button.frames.size());
    }

    @Test
    public void recycledRowReleasesBitmapBackupParentAndClickListener() throws Exception {
        SelectionOrderSheet sheet = sheet(null, null);
        RecyclerView recycler = ReflectionHelpers.getField(sheet, "recyclerView");
        RecyclerView.Adapter adapter = recycler.getAdapter();
        RecyclerView.ViewHolder holder = adapter.createViewHolder(recycler, 0);
        BackupImageView image = ReflectionHelpers.getField(holder.itemView, "imageView");
        ImageReceiver receiver = image.getImageReceiver();
        receiver.setImageBitmap(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888));
        Object parent = new Object();
        ReflectionHelpers.setField(receiver, "currentParentObject", parent);
        Object backup = ReflectionHelpers.callConstructor(Class.forName(ImageReceiver.class.getName() + "$SetImageBackup"));
        ReflectionHelpers.setField(backup, "parentObject", parent);
        ReflectionHelpers.setField(backup, "thumb", new ColorDrawable(Color.RED));
        ReflectionHelpers.setField(receiver, "setImageBackup", backup);
        holder.itemView.setOnClickListener(v -> fail("Recycled row retained its click callback"));
        adapter.onViewRecycled(holder);
        assertNull(receiver.getBitmap());
        assertNull(receiver.getParentObject());
        assertNull(ReflectionHelpers.getField(backup, "parentObject"));
        assertNull(ReflectionHelpers.getField(backup, "thumb"));
        assertFalse(holder.itemView.hasOnClickListeners());
        assertTrue(ReflectionHelpers.getField(receiver, "allowLoadingOnAttachedOnly"));
        sheet.dismissInternal();
    }

    @Test
    public void draggingStopsPlaybackAndRestoresAutoplayAfterDrop() throws Exception {
        SelectionOrderSheet sheet = sheet(null, null);
        RecyclerView.ViewHolder holder = row(sheet);
        TrackingReceiver receiver = replaceReceiver(holder);
        receiver.setAllowStartAnimation(true);
        receiver.running = true;
        pause(holder, true);
        pause(holder, true);
        assertFalse(receiver.getAllowStartAnimation());
        assertFalse(receiver.running);
        assertEquals(1, receiver.stops);
        pause(holder, false);
        assertTrue(receiver.getAllowStartAnimation());
        assertTrue(receiver.running);
        assertEquals(1, receiver.starts);
        sheet.dismissInternal();
    }

    @Test
    public void dropStartsAnimationLoadedWhilePausedButKeepsStaticPreviewsStatic() throws Exception {
        SelectionOrderSheet sheet = sheet(null, null);
        RecyclerView.ViewHolder holder = row(sheet);
        TrackingReceiver receiver = replaceReceiver(holder);
        receiver.setAllowStartAnimation(true);
        pause(holder, true); // A drawable can finish loading before the drop.
        pause(holder, false);
        assertEquals(1, receiver.starts);
        receiver.setAllowStartAnimation(false);
        receiver.running = false;
        pause(holder, true);
        pause(holder, false);
        assertEquals(1, receiver.starts);
        assertFalse(receiver.getAllowStartAnimation());
        sheet.dismissInternal();
    }

    @Test
    public void orderPayloadChangesNumberWithoutReloadingPreview() throws Exception {
        SelectionOrderSheet sheet = sheet(null, null);
        RecyclerView recycler = ReflectionHelpers.getField(sheet, "recyclerView");
        RecyclerView.Adapter adapter = recycler.getAdapter();
        RecyclerView.ViewHolder holder = row(sheet);
        TrackingReceiver receiver = replaceReceiver(holder);
        Object payload = ReflectionHelpers.getStaticField(SelectionOrderSheet.class, "ORDER_PAYLOAD");
        adapter.onBindViewHolder(holder, 4, Collections.singletonList(payload));
        TextView number = ReflectionHelpers.getField(holder.itemView, "numberView");
        assertEquals("5", number.getText().toString());
        assertEquals(0, receiver.stops);
        assertEquals(0, receiver.starts);
        sheet.dismissInternal();
    }

    @Test
    public void dismissalClearsExternalCallbacksDelegatesAdapterAndPool() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SelectionOrderSheet sheet = sheet(order -> calls.incrementAndGet(), message -> calls.incrementAndGet());
        sheet.setDelegate(new BottomSheet.BottomSheetDelegate());
        sheet.setOnHideListener(dialog -> calls.incrementAndGet());
        RecyclerView recycler = ReflectionHelpers.getField(sheet, "recyclerView");
        recycler.getRecycledViewPool().putRecycledView(row(sheet));
        sheet.dismissInternal();
        assertNull(ReflectionHelpers.getField(sheet, "onOrderChanged"));
        assertNull(ReflectionHelpers.getField(sheet, "onPreview"));
        assertNull(ReflectionHelpers.getField(sheet, "delegate"));
        assertNull(ReflectionHelpers.getField(sheet, "onHideListener"));
        assertNull(sheet.getResourcesProvider());
        assertNull(recycler.getAdapter());
        assertEquals(0, recycler.getRecycledViewPool().getRecycledViewCount(0));
        Object helper = ReflectionHelpers.getField(sheet, "touchHelper");
        assertNull(ReflectionHelpers.getField(helper, "mRecyclerView"));
        sheet.dismissInternal();
        assertEquals(0, calls.get());
    }

    @Test
    public void deferredPreviewRunsOnceAfterCallbacksAndListHaveBeenReleased() throws Exception {
        AtomicReference<SelectionOrderSheet> reference = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        MessageObject marker = new MessageObject(0, new TLRPC.TL_message(), "Preview", "", "", true, false, false, false);
        SelectionOrderSheet sheet = sheet(null, message -> {
            assertSame(marker, message);
            assertNull(ReflectionHelpers.getField(reference.get(), "onPreview"));
            RecyclerView recycler = ReflectionHelpers.getField(reference.get(), "recyclerView");
            assertNull(recycler.getAdapter());
            calls.incrementAndGet();
        });
        reference.set(sheet);
        ReflectionHelpers.setField(sheet, "pendingPreview", marker);
        sheet.dismissInternal();
        sheet.dismissInternal();
        assertEquals(1, calls.get());
        assertNull(ReflectionHelpers.getField(sheet, "pendingPreview"));
    }

    @Test
    public void dragCallbackPausesVisibleAndNewlyAttachedRowsAndResumesAfterDrop() throws Exception {
        SelectionOrderSheet sheet = sheet(null, null);
        RecyclerView recycler = attachRows(sheet);
        RecyclerView.ViewHolder holder = recycler.getChildViewHolder(recycler.getChildAt(0));
        TrackingReceiver receiver = replaceReceiver(holder);
        receiver.setAllowStartAnimation(true);
        receiver.running = true;
        Object helper = ReflectionHelpers.getField(sheet, "touchHelper");
        ItemTouchHelper.Callback callback = ReflectionHelpers.getField(helper, "mCallback");
        callback.onSelectedChanged(holder, ItemTouchHelper.ACTION_STATE_DRAG);
        assertFalse(receiver.running);
        RecyclerView.ViewHolder incoming = row(sheet);
        TrackingReceiver incomingReceiver = replaceReceiver(incoming);
        incomingReceiver.setAllowStartAnimation(true);
        incomingReceiver.running = true;
        RecyclerView.Adapter adapter = recycler.getAdapter();
        adapter.onViewAttachedToWindow(incoming);
        assertFalse(incomingReceiver.running);
        callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
        assertTrue(receiver.running);
        adapter.onViewAttachedToWindow(incoming);
        assertTrue(incomingReceiver.running);
        adapter.onViewRecycled(incoming);
        sheet.dismissInternal();
    }

    @Test
    public void dismissalDuringActiveDragEndsSelectionWithoutRestartingPreview() throws Exception {
        SelectionOrderSheet sheet = sheet(null, null);
        RecyclerView recycler = attachRows(sheet);
        RecyclerView.ViewHolder holder = recycler.getChildViewHolder(recycler.getChildAt(0));
        TrackingReceiver receiver = replaceReceiver(holder);
        receiver.setAllowStartAnimation(true);
        receiver.running = true;
        ItemTouchHelper helper = ReflectionHelpers.getField(sheet, "touchHelper");
        helper.startDrag(holder);
        assertSame(holder, ReflectionHelpers.getField(helper, "mSelected"));
        assertFalse(receiver.running);
        sheet.dismissInternal();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertNull(ReflectionHelpers.getField(helper, "mSelected"));
        assertNull(ReflectionHelpers.getField(helper, "mRecyclerView"));
        assertEquals(0, receiver.starts);
        assertFalse(receiver.running);
    }

    private CountingAlbumButton attachButton() {
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        CountingAlbumButton button = new CountingAlbumButton(activity);
        root.addView(button);
        button.layout(0, 0, 240, 48);
        return button;
    }

    private SelectionOrderSheet sheet(Consumer<ArrayList<MessageObject>> order, Consumer<MessageObject> preview) throws Exception {
        Constructor<SelectionOrderSheet> constructor = SelectionOrderSheet.class.getDeclaredConstructor(
                Activity.class, ArrayList.class, Theme.ResourcesProvider.class, Consumer.class, Consumer.class);
        constructor.setAccessible(true);
        Theme.ResourcesProvider resources = key -> Color.BLACK;
        return constructor.newInstance(activity, new ArrayList<MessageObject>(), resources, order, preview);
    }

    private RecyclerView.ViewHolder row(SelectionOrderSheet sheet) {
        RecyclerView recycler = ReflectionHelpers.getField(sheet, "recyclerView");
        return recycler.getAdapter().createViewHolder(recycler, 0);
    }

    private RecyclerView attachRows(SelectionOrderSheet sheet) {
        ArrayList<MessageObject> messages = ReflectionHelpers.getField(sheet, "orderedMessages");
        messages.add(new MessageObject(0, new TLRPC.TL_message(), "First", "", "", true, false, false, false));
        messages.add(new MessageObject(0, new TLRPC.TL_message(), "Second", "", "", true, false, false, false));
        RecyclerView recycler = ReflectionHelpers.getField(sheet, "recyclerView");
        recycler.getAdapter().notifyDataSetChanged();
        activity.setContentView(recycler);
        recycler.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY));
        recycler.layout(0, 0, 400, 600);
        assertTrue(recycler.getChildCount() > 0);
        return recycler;
    }

    private TrackingReceiver replaceReceiver(RecyclerView.ViewHolder holder) {
        BackupImageView image = ReflectionHelpers.getField(holder.itemView, "imageView");
        TrackingReceiver receiver = new TrackingReceiver();
        ReflectionHelpers.setField(image, "imageReceiver", receiver);
        return receiver;
    }

    private void pause(RecyclerView.ViewHolder holder, boolean paused) throws Exception {
        Method method = holder.itemView.getClass().getDeclaredMethod("setPreviewPaused", boolean.class);
        method.setAccessible(true);
        method.invoke(holder.itemView, paused);
    }

    private void dispatchScroll(ViewTreeObserver observer) {
        ReflectionHelpers.callInstanceMethod(observer, "dispatchOnScrollChanged");
    }

    private static class CountingAlbumButton extends AlbumButton {
        final ArrayList<Runnable> frames = new ArrayList<>();
        int visibilityChecks;

        CountingAlbumButton(Context context) { super(context, null, "Camera", 0, null); }
        @Override public void postOnAnimation(Runnable runnable) { frames.add(runnable); }
        @Override public boolean removeCallbacks(Runnable runnable) {
            if (frames != null) frames.remove(runnable);
            return super.removeCallbacks(runnable);
        }
        @Override public boolean getGlobalVisibleRect(Rect rect, Point offset) {
            visibilityChecks++;
            rect.set(0, 0, 240, 48);
            return true;
        }
        void runFrame() {
            ArrayList<Runnable> pending = new ArrayList<>(frames);
            frames.clear();
            for (Runnable runnable : pending) runnable.run();
        }
    }

    private static class TrackingReceiver extends ImageReceiver {
        int starts, stops;
        boolean running;
        @Override public boolean isAnimationRunning() { return running; }
        @Override public void startAnimation() { starts++; running = true; }
        @Override public void stopAnimation() { stops++; running = false; }
    }

    // Keep native loading, localization and theme setup outside these view lifecycle tests.
    @Implements(value = ImageLoader.class, isInAndroidSdk = false)
    public static class ShadowImageLoader {
        @Implementation protected void __constructor__() { }
        @Implementation protected void cancelLoadingForImageReceiver(ImageReceiver receiver, boolean all) { }
    }

    @Implements(value = Theme.class, isInAndroidSdk = false)
    public static class ShadowTheme {
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static int getColor(int key) { return Color.BLACK; }
        @Implementation protected static int getColor(int key, Theme.ResourcesProvider provider) { return Color.BLACK; }
        @Implementation protected static Drawable getSelectorDrawable(boolean white) { return new ColorDrawable(Color.TRANSPARENT); }
    }

    @Implements(value = LocaleController.class, isInAndroidSdk = false)
    public static class ShadowLocaleController {
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected void __constructor__() { }
        @Implementation protected static String getString(int id) { return "Test"; }
        @Implementation protected static String formatString(int id, Object... args) { return "Selection order"; }
        @Implementation protected Locale getCurrentLocale() { return Locale.US; }
    }
}
