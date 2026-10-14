package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A dialog above the current chat or shared-media screen; opening it never navigates. */
public final class SelectionOrderSheet extends BottomSheet {
    private static final int PREVIEW_SIZE = 96;
    private static final int ROW_HEIGHT = 120;
    private static final Object ORDER_PAYLOAD = new Object();
    private final ArrayList<MessageObject> orderedMessages;
    private final RecyclerView recyclerView;
    private final ItemTouchHelper touchHelper;
    private Consumer<ArrayList<MessageObject>> onOrderChanged;
    private Consumer<MessageObject> onPreview;
    private MessageObject pendingPreview;
    private boolean dragging;
    private boolean released;

    public static void show(Activity activity, ArrayList<MessageObject> messages,
                            Theme.ResourcesProvider resourcesProvider,
                            Consumer<ArrayList<MessageObject>> onOrderChanged,
                            Consumer<MessageObject> onPreview) {
        if (activity == null || messages.isEmpty()) {
            return;
        }
        new SelectionOrderSheet(activity, messages, resourcesProvider, onOrderChanged, onPreview).show();
    }

    private SelectionOrderSheet(Activity activity, ArrayList<MessageObject> messages,
                                Theme.ResourcesProvider resources,
                                Consumer<ArrayList<MessageObject>> orderChanged,
                                Consumer<MessageObject> preview) {
        super(activity, false, resources);
        fixNavigationBar();
        onOrderChanged = orderChanged;
        onPreview = preview;
        orderedMessages = new ArrayList<>(messages);
        recyclerView = new RecyclerView(activity);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        recyclerView.setPadding(0, 0, 0, dp(8));
        int maximumHeight = AndroidUtilities.displaySize.y > 0 ? AndroidUtilities.displaySize.y * 2 / 3 : dp(520);
        int listHeight = Math.min(maximumHeight, orderedMessages.size() * dp(ROW_HEIGHT) + dp(8));
        recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(new SelectionOrderRow(parent.getContext(), SelectionOrderSheet.this.resourcesProvider)) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                MessageObject message = orderedMessages.get(position);
                SelectionOrderRow row = (SelectionOrderRow) holder.itemView;
                row.setMessage(message, position + 1);
                row.setPreviewPaused(dragging);
                boolean canPreview = message.isPhoto() || message.isVideo() || message.isGif();
                row.setClickable(canPreview);
                row.setOnClickListener(canPreview ? v -> {
                    if (!released && !isDismissed() && onPreview != null) {
                        pendingPreview = message;
                        dismiss();
                    }
                } : null);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, List<Object> payloads) {
                if (!payloads.isEmpty() && payloads.contains(ORDER_PAYLOAD)) {
                    ((SelectionOrderRow) holder.itemView).setOrder(position + 1);
                } else {
                    onBindViewHolder(holder, position);
                }
            }

            @Override
            public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
                super.onViewAttachedToWindow(holder);
                ((SelectionOrderRow) holder.itemView).setPreviewPaused(dragging || released);
            }

            @Override
            public void onViewDetachedFromWindow(RecyclerView.ViewHolder holder) {
                ((SelectionOrderRow) holder.itemView).setPreviewPaused(true);
                super.onViewDetachedFromWindow(holder);
            }

            @Override
            public void onViewRecycled(RecyclerView.ViewHolder holder) {
                ((SelectionOrderRow) holder.itemView).clearPreview();
                super.onViewRecycled(holder);
            }

            @Override
            public int getItemCount() {
                return orderedMessages.size();
            }
        };
        recyclerView.setAdapter(adapter);
        touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder source,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = source.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (released || from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                    return false;
                }
                // Move through the list rather than exchanging distant endpoints.
                MessageObject moved = orderedMessages.remove(from);
                orderedMessages.add(to, moved);
                adapter.notifyItemMoved(from, to);
                adapter.notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1, ORDER_PAYLOAD);
                if (onOrderChanged != null) {
                    onOrderChanged.accept(new ArrayList<>(orderedMessages));
                }
                return true;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
                super.onSelectedChanged(holder, actionState);
                dragging = actionState == ItemTouchHelper.ACTION_STATE_DRAG;
                for (int i = 0; i < recyclerView.getChildCount(); i++) {
                    ((SelectionOrderRow) recyclerView.getChildAt(i)).setPreviewPaused(dragging || released);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder holder) {
                super.clearView(recycler, holder);
                ((SelectionOrderRow) holder.itemView).setPreviewPaused(dragging || released);
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            }
        });
        touchHelper.attachToRecyclerView(recyclerView);
        setTitle(LocaleController.formatString(R.string.SelectionOrderTitle, orderedMessages.size()));
        setCustomView(recyclerView);
    }

    @Override
    public void dismissInternal() {
        if (released) {
            return;
        }
        released = true;
        MessageObject message = pendingPreview;
        Consumer<MessageObject> preview = message == null ? null : onPreview;
        pendingPreview = null;
        onOrderChanged = null;
        onPreview = null;
        setDelegate(null);
        setOnHideListener(null);
        // The Runnable overload ignores null, so clear the Dialog listener explicitly.
        setOnDismissListener((DialogInterface.OnDismissListener) null);
        setOnCancelListener(null);
        setOnShowListener(null);

        recyclerView.stopScroll();
        if (recyclerView.getItemAnimator() != null) {
            recyclerView.getItemAnimator().endAnimations();
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            ((SelectionOrderRow) recyclerView.getChildAt(i)).clearPreview();
        }
        // Recycle children before detaching the helper so an active drag is ended too.
        recyclerView.setAdapter(null);
        touchHelper.attachToRecyclerView(null);
        recyclerView.getRecycledViewPool().clear();
        orderedMessages.clear();
        setCustomView(null);
        try {
            super.dismissInternal();
        } finally {
            resourcesProvider = null;
        }
        if (preview != null) {
            preview.accept(message);
        }
    }

    private static final class SelectionOrderRow extends LinearLayout {
        private final TextView numberView;
        private final BackupImageView imageView;
        private final TextView descriptionView;
        private boolean previewPaused;
        private boolean allowAnimationBeforePause;
        private boolean resumeAnimation;

        SelectionOrderRow(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(12), dp(16), dp(12));
            setLayoutParams(new RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT)));
            numberView = new TextView(context);
            numberView.setGravity(Gravity.CENTER);
            numberView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
            numberView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            numberView.setTypeface(AndroidUtilities.bold());
            addView(numberView, new LinearLayout.LayoutParams(dp(32), LayoutParams.MATCH_PARENT));

            imageView = new BackupImageView(context);
            imageView.setRoundRadius(dp(8));
            imageView.getImageReceiver().setAllowStartAnimation(false);
            addView(imageView, new LinearLayout.LayoutParams(dp(PREVIEW_SIZE), dp(PREVIEW_SIZE)));

            descriptionView = new TextView(context);
            descriptionView.setGravity(Gravity.CENTER_VERTICAL);
            descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            descriptionView.setMaxLines(3);
            descriptionView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            textParams.leftMargin = dp(12);
            addView(descriptionView, textParams);

            ImageView dragHandle = new ImageView(context);
            dragHandle.setImageResource(R.drawable.ic_drag_handle);
            dragHandle.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            dragHandle.setContentDescription(LocaleController.getString(R.string.SelectionOrderDragHandle));
            LinearLayout.LayoutParams dragParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            dragParams.leftMargin = dp(8);
            addView(dragHandle, dragParams);
        }

        void setMessage(MessageObject message, int order) {
            setOrder(order);
            CharSequence description = message.messageText;
            TLRPC.MessageMedia media = message.messageOwner == null ? null : MessageObject.getMedia(message.messageOwner);
            if (TextUtils.isEmpty(description)) {
                if (message.isPhoto()) {
                    description = LocaleController.getString(R.string.SelectionOrderPhoto);
                } else if (message.isGif()) {
                    description = LocaleController.getString(R.string.AttachGif);
                } else if (message.isVideo()) {
                    description = LocaleController.getString(R.string.SelectionOrderVideo);
                } else if (message.getDocument() != null) {
                    description = FileLoader.getDocumentFileName(message.getDocument());
                } else {
                    description = LocaleController.getString(R.string.Message);
                }
            }
            descriptionView.setText(description);
            imageView.setImageDrawable(Theme.chat_attachEmptyDrawable);
            boolean visualMedia = message.isPhoto() || message.isVideo() || message.isGif();
            imageView.setVisibility(visualMedia ? View.VISIBLE : View.GONE);
            if (media != null && media.photo != null && message.isPhoto()) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, dp(PREVIEW_SIZE), false, null, true);
                if (size != null) {
                    imageView.setImage(ImageLocation.getForPhoto(size, media.photo), "96_96", Theme.chat_attachEmptyDrawable, message);
                }
            } else if (message.getDocument() != null && visualMedia) {
                TLRPC.Document document = message.getDocument();
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, dp(PREVIEW_SIZE), false, null, true);
                if (thumb != null) {
                    imageView.setImage(ImageLocation.getForDocument(thumb, document), "96_96", Theme.chat_attachEmptyDrawable, message);
                } else {
                    File localFile = !TextUtils.isEmpty(message.messageOwner.attachPath)
                            ? new File(message.messageOwner.attachPath) : null;
                    if (localFile == null || !localFile.isFile()) {
                        localFile = FileLoader.getInstance(message.currentAccount).getPathToAttach(document);
                    }
                    if (localFile.isFile()) {
                        imageView.setImage(ImageLocation.getForPath(localFile.getAbsolutePath()), "96_96_firstframe", Theme.chat_attachEmptyDrawable, message);
                    }
                }
            }
        }

        void setOrder(int order) {
            numberView.setText(String.valueOf(order));
        }

        void setPreviewPaused(boolean paused) {
            if (previewPaused == paused) {
                return;
            }
            previewPaused = paused;
            ImageReceiver receiver = imageView.getImageReceiver();
            if (paused) {
                allowAnimationBeforePause = receiver.getAllowStartAnimation();
                resumeAnimation = receiver.isAnimationRunning();
                receiver.setAllowStartAnimation(false);
                receiver.stopAnimation();
            } else {
                receiver.setAllowStartAnimation(allowAnimationBeforePause);
                if (allowAnimationBeforePause || resumeAnimation) {
                    receiver.startAnimation();
                }
                resumeAnimation = false;
            }
        }

        void clearPreview() {
            setOnClickListener(null);
            setClickable(false);
            descriptionView.setText(null);
            numberView.setText(null);
            previewPaused = false;
            resumeAnimation = false;
            allowAnimationBeforePause = false;
            ImageReceiver receiver = imageView.getImageReceiver();
            receiver.setAllowStartAnimation(false);
            receiver.stopAnimation();
            receiver.clearImage();
            // A detached receiver also keeps a reload backup and its MessageObject.
            receiver.setAllowLoadingOnAttachedOnly(false);
            receiver.setImage(null, null, null, null, (Drawable) null, null, 0);
            receiver.setAllowLoadingOnAttachedOnly(true);
        }
    }
}
