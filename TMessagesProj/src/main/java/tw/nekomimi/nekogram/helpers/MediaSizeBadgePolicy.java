package tw.nekomimi.nekogram.helpers;

public final class MediaSizeBadgePolicy {
    private MediaSizeBadgePolicy() {
    }

    public static boolean shouldDrawCustomVideoSizeBadge(boolean isVideo, boolean isAlbum, boolean telegramBadgeVisible) {
        return isVideo && isAlbum && !telegramBadgeVisible;
    }

    public static boolean shouldDrawCustomVideoSizeBadge(boolean isVideo, boolean isAlbum, Boolean telegramBadgeVisible) {
        return telegramBadgeVisible != null
                && shouldDrawCustomVideoSizeBadge(isVideo, isAlbum, telegramBadgeVisible.booleanValue());
    }

    public static boolean shouldDrawCustomMediaSizeBadge(boolean isPhoto, boolean isVideo, boolean isAlbum, Boolean telegramBadgeVisible) {
        return isPhoto || shouldDrawCustomVideoSizeBadge(isVideo, isAlbum, telegramBadgeVisible);
    }
}
