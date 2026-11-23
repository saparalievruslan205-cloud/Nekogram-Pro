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

    /** Wrap progress or duration+size only when both rows fit in the available height. */
    public static int getProgressLineCount(float textWidth, float availableWidth, float twoLineHeight, float availableHeight) {
        return textWidth > availableWidth && twoLineHeight <= availableHeight ? 2 : 1;
    }

    /** Include video duration in the custom badge when Telegram's native duration overlay is not drawn. */
    public static boolean shouldIncludeVideoDuration(boolean isVideo, boolean isGif, boolean nativeDurationDrawn) {
        return isVideo && !isGif && !nativeDurationDrawn;
    }

    /** Combine optional video duration and file size into a single badge label. */
    public static String formatBadgeText(String durationText, String sizeText) {
        boolean hasDuration = durationText != null && !durationText.isEmpty();
        boolean hasSize = sizeText != null && !sizeText.isEmpty();
        if (hasDuration && hasSize) {
            return durationText + " · " + sizeText;
        }
        if (hasDuration) {
            return durationText;
        }
        return hasSize ? sizeText : null;
    }

    /**
     * Split a badge label onto two lines for narrow album tiles.
     * Keeps "loaded /" + "total · pct%" for download progress and splits "duration · size" into two rows.
     */
    public static String[] splitTwoLineBadgeText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int slashSeparator = text.indexOf(" / ");
        if (slashSeparator >= 0) {
            return new String[] {
                    text.substring(0, slashSeparator + 2),
                    text.substring(slashSeparator + 3)
            };
        }
        int dotSeparator = text.indexOf(" · ");
        if (dotSeparator >= 0) {
            return new String[] {
                    text.substring(0, dotSeparator),
                    text.substring(dotSeparator + 3)
            };
        }
        return null;
    }
}
