package tw.nekomimi.nekogram.helpers;

public final class MediaSizeBadgePolicy {
    private MediaSizeBadgePolicy() {
    }

    /** Keep a duration-only overlay and the custom size badge in separate rows. */
    public static float getBadgeTop(float preferredTop, float nativeBottom, float gap, float height, float bottomLimit) {
        float top = Math.max(preferredTop, nativeBottom + gap);
        return top + height <= bottomLimit ? top : Float.NaN;
    }

    public static boolean shouldDrawCustomVideoSizeBadge(boolean isVideo, Boolean telegramBadgeVisible) {
        return telegramBadgeVisible != null
                && isVideo && !telegramBadgeVisible;
    }

    public static boolean shouldDrawCustomMediaSizeBadge(boolean isPhoto, boolean isVideo, Boolean telegramBadgeVisible) {
        return isPhoto || shouldDrawCustomVideoSizeBadge(isVideo, telegramBadgeVisible);
    }

    /** Wrap progress only when both rows fit below the native duration overlay. */
    public static int getProgressLineCount(float textWidth, float availableWidth, float twoLineHeight, float availableHeight) {
        return textWidth > availableWidth && twoLineHeight <= availableHeight ? 2 : 1;
    }
}
