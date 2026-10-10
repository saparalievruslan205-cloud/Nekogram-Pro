package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.util.ArrayList;
import java.util.function.Consumer;

/** A dialog above the current chat or shared-media screen; opening it never navigates. */
public final class SelectionOrderSheet {
    private static final int PREVIEW_SIZE = 96;
    private static final int ROW_HEIGHT = 120;

    private SelectionOrderSheet() {
    }

    public static void show(Activity activity, ArrayList<MessageObject> messages,
                            Theme.ResourcesProvider resourcesProvider,
                            Consumer<ArrayList<MessageObject>> onOrderChanged,
                            Consumer<MessageObject> onPreview) {
        if (activity == null || messages.isEmpty()) {
            return;
        }
        ArrayList<MessageObject> orderedMessages = new ArrayList<>(messages);
        RecyclerView recyclerView = new RecyclerView(activity);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        recyclerView.setPadding(0, 0, 0, dp(8));
        int maximumHeight = AndroidUtilities.displaySize.y > 0 ? AndroidUtilities.displaySize.y * 2 / 3 : dp(520);
        int listHeight = Math.min(maximumHeight, orderedMessages.size() * dp(ROW_HEIGHT) + dp(8));
        recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
        BottomSheet[] sheetRef = new BottomSheet[1];
        RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(new SelectionOrderRow(activity, resourcesProvider)) {};
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                MessageObject message = orderedMessages.get(position);
                SelectionOrderRow row = (SelectionOrderRow) holder.itemView;
                row.setMessage(message, position + 1);
                boolean canPreview = message.isPhoto() || message.isVideo() || message.isGif();
                row.setClickable(canPreview);
                row.setOnClickListener(canPreview ? v -> {
                    BottomSheet sheet = sheetRef[0];
                    if (sheet != null) {
                        sheet.setOnDismissListener(() -> onPreview.accept(message));
                        sheet.dismiss();
                    }
                } : null);
            }

            @Override
            public int getItemCount() {
                return orderedMessages.size();
            }
        };
        recyclerView.setAdapter(adapter);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recycler, @NonNull RecyclerView.ViewHolder source,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = source.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                    return false;
                }
                // Move through the list rather than exchanging distant endpoints.
                MessageObject moved = orderedMessages.remove(from);
                orderedMessages.add(to, moved);
                adapter.notifyItemMoved(from, to);
                adapter.notifyItemRangeChanged(Math.min(from, to), Math.abs(from - to) + 1);
                onOrderChanged.accept(new ArrayList<>(orderedMessages));
                return true;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            }
        }).attachToRecyclerView(recyclerView);
        sheetRef[0] = new BottomSheet.Builder(activity, false, resourcesProvider)
                .setTitle(LocaleController.formatString(R.string.SelectionOrderTitle, orderedMessages.size()))
                .setCustomView(recyclerView)
                .create();
        sheetRef[0].show();
    }

    private static final class SelectionOrderRow extends LinearLayout {
        private final TextView numberView;
        private final BackupImageView imageView;
        private final TextView descriptionView;

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
            numberView.setText(String.valueOf(order));
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
    }
}
