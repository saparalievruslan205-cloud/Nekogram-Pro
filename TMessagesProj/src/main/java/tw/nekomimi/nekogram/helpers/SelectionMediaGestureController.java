package tw.nekomimi.nekogram.helpers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;

/**
 * Coordinates long-press gestures on media items while multi-selection mode is active:
 * <ul>
 *   <li>Moving past touch slop immediately transitions into drag-to-select, toggling the anchor item
 *       first so selection order is preserved.</li>
 *   <li>Holding still until {@link #HOLD_TO_OPEN_EXTRA_DELAY_MS} after long-press (or lifting the
 *       finger cleanly without dragging) cancels drag-to-select and opens the media preview
 *       without modifying selection.</li>
 *   <li>Cancellation ({@code ACTION_CANCEL}, lifecycle pause/detach, or action mode exit) aborts
 *       without side effects.</li>
 * </ul>
 */
public final class SelectionMediaGestureController {

    /**
     * Extra hold duration after standard Android long-press (~400-500ms) before automatically
     * opening media while the finger stays still (~1.25-1.35s total hold time).
     */
    public static final long HOLD_TO_OPEN_EXTRA_DELAY_MS = 850L;

    public interface Scheduler {
        void postDelayed(@NonNull Runnable runnable, long delayMs);
        void cancel(@NonNull Runnable runnable);
    }

    private static final Scheduler DEFAULT_SCHEDULER = new Scheduler() {
        @Override
        public void postDelayed(@NonNull Runnable runnable, long delayMs) {
            AndroidUtilities.runOnUIThread(runnable, delayMs);
        }

        @Override
        public void cancel(@NonNull Runnable runnable) {
            AndroidUtilities.cancelRunOnUIThread(runnable);
        }
    };

    private final Scheduler scheduler;
    private final long holdToOpenExtraDelayMs;

    private boolean active;
    private boolean dragStarted;
    private boolean opened;
    @Nullable
    private Runnable onOpenMedia;
    @Nullable
    private Runnable onDragStart;
    @Nullable
    private Runnable pendingHoldRunnable;

    public SelectionMediaGestureController() {
        this(DEFAULT_SCHEDULER, HOLD_TO_OPEN_EXTRA_DELAY_MS);
    }

    public SelectionMediaGestureController(@NonNull Scheduler scheduler, long holdToOpenExtraDelayMs) {
        this.scheduler = scheduler;
        this.holdToOpenExtraDelayMs = Math.max(0L, holdToOpenExtraDelayMs);
    }

    public void arm(@NonNull Runnable onOpenMedia, @Nullable Runnable onDragStart) {
        cancel();
        this.active = true;
        this.dragStarted = false;
        this.opened = false;
        this.onOpenMedia = onOpenMedia;
        this.onDragStart = onDragStart;

        Runnable holdRunnable = new Runnable() {
            @Override
            public void run() {
                if (pendingHoldRunnable != this || !active || dragStarted || opened) {
                    return;
                }
                Runnable openAction = SelectionMediaGestureController.this.onOpenMedia;
                clearState();
                opened = true;
                if (openAction != null) {
                    openAction.run();
                }
            }
        };
        pendingHoldRunnable = holdRunnable;
        scheduler.postDelayed(holdRunnable, holdToOpenExtraDelayMs);
    }

    public boolean onDragStarted() {
        if (!active || dragStarted || opened) {
            return false;
        }
        Runnable dragAction = onDragStart;
        cancelPendingHold();
        active = false;
        dragStarted = true;
        onOpenMedia = null;
        onDragStart = null;
        if (dragAction != null) {
            dragAction.run();
        }
        return true;
    }

    public boolean onGestureEnded(boolean dragStartedFromList, boolean canceled) {
        cancelPendingHold();
        if (!active || opened || dragStarted || dragStartedFromList || canceled) {
            clearState();
            return false;
        }
        Runnable openAction = onOpenMedia;
        clearState();
        opened = true;
        if (openAction != null) {
            openAction.run();
        }
        return true;
    }

    public void cancel() {
        cancelPendingHold();
        clearState();
        opened = false;
        dragStarted = false;
    }

    public boolean isActive() {
        return active;
    }

    private void cancelPendingHold() {
        if (pendingHoldRunnable != null) {
            scheduler.cancel(pendingHoldRunnable);
            pendingHoldRunnable = null;
        }
    }

    private void clearState() {
        active = false;
        onOpenMedia = null;
        onDragStart = null;
        pendingHoldRunnable = null;
    }

    public static boolean isPreviewableChatMedia(@Nullable MessageObject message) {
        if (message == null) {
            return false;
        }
        if (message.isSendError() || message.isSending() || message.isSponsored()
                || message.isEphemeral() || message.needDrawBluredPreview()
                || message.isRoundVideo()) {
            return false;
        }
        return message.type == MessageObject.TYPE_PHOTO || message.isVideo() || message.isGif();
    }

    public static boolean hasUnrevealedMediaSpoiler(@Nullable MessageObject message) {
        return message != null
                && message.hasMediaSpoilers()
                && !message.needDrawBluredPreview()
                && !message.isMediaSpoilersRevealed;
    }
}
