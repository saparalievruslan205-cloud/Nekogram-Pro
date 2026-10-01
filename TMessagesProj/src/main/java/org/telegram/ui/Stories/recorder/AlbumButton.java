package org.telegram.ui.Stories.recorder;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CombinedDrawable;

public class AlbumButton extends View {
    private final ImageReceiver imageReceiver = new ImageReceiver(this);
    private final CharSequence title;
    private CharSequence subtitle;
    private MediaController.PhotoEntry cover;
    private final Drawable noGalleryDrawable;
    private final String coverFilter;
    private final Rect visibleRect = new Rect();
    private boolean coverRequested;
    private int coverRetryCount;

    private final TextPaint namePaintLayout = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout nameLayout;
    private float nameLayoutWidth, nameLayoutLeft;

    private final TextPaint countPaintLayout = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout countLayout;
    private float countLayoutWidth, countLayoutLeft;

    public AlbumButton(Context context, MediaController.PhotoEntry cover, CharSequence name, int count, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        setPadding(dp(16), 0, dp(16), 0);
        setBackground(Theme.getSelectorDrawable(false));
        setMinimumWidth(dp(196));
        setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48));

        namePaintLayout.setColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider));
        namePaintLayout.setTextSize(dp(16));
        countPaintLayout.setColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider));
        countPaintLayout.setAlpha((int) (.4f * 0xFF));
        countPaintLayout.setTextSize(dp(13));

        title = "" + name;
        subtitle = "" + count;

        imageReceiver.setRoundRadius(dp(4));
        final Drawable noPhotosIcon = context.getResources().getDrawable(R.drawable.msg_media_gallery).mutate();
        noPhotosIcon.setColorFilter(new PorterDuffColorFilter(0x4dFFFFFF, PorterDuff.Mode.MULTIPLY));
        final CombinedDrawable placeholder = new CombinedDrawable(Theme.createRoundRectDrawable(dp(6), 0xFF2E2E2F), noPhotosIcon);
        placeholder.setFullsize(false);
        placeholder.setIconSize(dp(18), dp(18));
        this.noGalleryDrawable = placeholder;
        this.cover = cover;
        coverFilter = imageSize + "_" + imageSize;
        imageReceiver.setImageBitmap(noGalleryDrawable);
        imageReceiver.setDelegate((receiver, set, thumb, memCache) -> {
            if (!set && coverRequested && cover != null && coverRetryCount < 2 && isAttachedToWindow()) {
                coverRetryCount++;
                postDelayed(() -> {
                    if (cover != null && isAttachedToWindow()) {
                        coverRequested = false;
                        requestCoverIfVisible();
                    }
                }, 450L * coverRetryCount);
            }
        });

        setContentDescription(title + (count > 0 ? " " + LocaleController.formatPluralStringComma("Media", count) : ""));
    }

    private void requestCoverIfVisible() {
        if (coverRequested || !getGlobalVisibleRect(visibleRect)) {
            return;
        }
        coverRequested = true;
        if (cover != null && cover.mediaStoreUri != null && cover.path != null) {
            imageReceiver.setImage(ImageLocation.getForMediaStore(cover.mediaStoreUri, cover.imageId, cover.path, cover.isVideo), coverFilter, null, null, noGalleryDrawable, null, 0);
        } else if (cover != null && cover.thumbPath != null) {
            imageReceiver.setImage(ImageLocation.getForPath(cover.thumbPath), coverFilter, null, null, noGalleryDrawable, null, 0);
        } else if (cover != null && cover.path != null) {
            if (cover.isVideo) {
                imageReceiver.setImage(ImageLocation.getForPath("vthumb://" + cover.imageId + ":" + cover.path), coverFilter, null, null, noGalleryDrawable, null, 0);
            } else {
                imageReceiver.setImage(ImageLocation.getForPath("thumb://" + cover.imageId + ":" + cover.path), coverFilter, null, null, noGalleryDrawable, null, 0);
            }
        }
    }

    public void setCover(MediaController.PhotoEntry cover) {
        if (this.cover == cover) {
            return;
        }
        this.cover = cover;
        coverRequested = false;
        coverRetryCount = 0;
        imageReceiver.setImageBitmap(noGalleryDrawable);
        if (isAttachedToWindow()) {
            requestCoverIfVisible();
        }
        invalidate();
    }

    public void setAlbum(MediaController.PhotoEntry cover, int count) {
        boolean countChanged = !TextUtils.equals(subtitle, String.valueOf(count));
        subtitle = String.valueOf(count);
        if (countChanged) {
            countLayout = null;
            requestLayout();
        }
        setCover(cover);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        imageReceiver.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        imageReceiver.onDetachedFromWindow();
    }
    final float imageSize = 30;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        updateLayouts(MeasureSpec.getSize(widthMeasureSpec) - dp(imageSize) - dp(12) - getPaddingLeft() - getPaddingRight());
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            float cwidth = getPaddingLeft() + dp(imageSize) + dp(12) + nameLayoutWidth + dp(8) + countLayoutWidth + getPaddingRight();
            setMeasuredDimension((int) Math.min(cwidth, MeasureSpec.getSize(widthMeasureSpec)), dp(48));
        } else if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(48));
        }
    }

    private void updateLayouts(int widthAvailable) {
        if (nameLayout == null || countLayout == null || nameLayout.getWidth() != widthAvailable) {
            CharSequence title = TextUtils.ellipsize(this.title, namePaintLayout, widthAvailable, TextUtils.TruncateAt.END);
            nameLayout = new StaticLayout(title, namePaintLayout, Math.max(0, widthAvailable), Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
            nameLayoutLeft = nameLayout.getLineCount() > 0 ? nameLayout.getLineLeft(0) : 0;
            nameLayoutWidth = nameLayout.getLineCount() > 0 ? nameLayout.getLineWidth(0) : 0;

            widthAvailable -= (int) (nameLayoutWidth + dp(8));
            CharSequence subtitle = TextUtils.ellipsize(this.subtitle, countPaintLayout, widthAvailable, TextUtils.TruncateAt.END);
            countLayout = new StaticLayout(subtitle, countPaintLayout, Math.max(0, widthAvailable), Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
            countLayoutLeft = countLayout.getLineCount() > 0 ? countLayout.getLineLeft(0) : 0;
            countLayoutWidth = countLayout.getLineCount() > 0 ? countLayout.getLineWidth(0) : 0;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        requestCoverIfVisible();
        float x = getPaddingLeft();
        imageReceiver.setImageCoords(x, (getMeasuredHeight() - dp(imageSize)) / 2f, dp(imageSize), dp(imageSize));
        imageReceiver.draw(canvas);
        x += dp(imageSize);
        x += dp(12);
        if (nameLayout != null) {
            canvas.save();
            canvas.translate(x - nameLayoutLeft, (getMeasuredHeight() - nameLayout.getHeight()) / 2f);
            nameLayout.draw(canvas);
            x += nameLayoutWidth;
            x += dp(6);
            canvas.restore();
        }
        if (countLayout != null) {
            canvas.save();
            canvas.translate(x - countLayoutLeft, (getMeasuredHeight() - countLayout.getHeight()) / 2f + dpf2(1.6f));
            countLayout.draw(canvas);
            canvas.restore();
        }
    }
}
