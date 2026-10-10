package tw.nekomimi.nekogram.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MediaSizeBadgePolicyTest {
    @Test
    public void reservesDurationRowEvenWithoutNativeSize() {
        assertEquals(24f, MediaSizeBadgePolicy.getBadgeTop(6f, 20f, 4f, 20f, 100f), 0f);
    }

    @Test
    public void omitsBadgeWhenCompactTileHasNoFreeRow() {
        assertTrue(Float.isNaN(MediaSizeBadgePolicy.getBadgeTop(6f, 20f, 4f, 20f, 40f)));
    }

    @Test
    public void keepsPhotoPositionWithoutNativeOverlay() {
        assertEquals(6f, MediaSizeBadgePolicy.getBadgeTop(6f, 0f, 4f, 20f, 100f), 0f);
    }
    @Test
    public void drawsForSingleVideoWithDurationOnly() {
        assertTrue(MediaSizeBadgePolicy.shouldDrawCustomVideoSizeBadge(true, false));
    }

    @Test
    public void doesNotDrawWhenTelegramBadgeIsVisible() {
        assertFalse(MediaSizeBadgePolicy.shouldDrawCustomVideoSizeBadge(true, true));
    }

    @Test
    public void drawsForAlbumVideoWhenTelegramBadgeIsHidden() {
        assertTrue(MediaSizeBadgePolicy.shouldDrawCustomVideoSizeBadge(true, false));
    }

    @Test
    public void keepsPhotoBadgeBehaviorUnchanged() {
        assertTrue(MediaSizeBadgePolicy.shouldDrawCustomMediaSizeBadge(true, false, null));
    }

    @Test
    public void doesNotDrawWhenTelegramBadgeVisibilityIsUnknown() {
        assertFalse(MediaSizeBadgePolicy.shouldDrawCustomVideoSizeBadge(true, null));
        assertFalse(MediaSizeBadgePolicy.shouldDrawCustomMediaSizeBadge(false, false, null));
    }

    @Test
    public void wrapsProgressOnNarrowAlbumTile() {
        assertEquals(2, MediaSizeBadgePolicy.getProgressLineCount(140f, 80f, 32f, 100f));
        assertEquals(40f, MediaSizeBadgePolicy.getBadgeTop(6f, 36f, 4f, 32f, 100f), 0f);
    }

    @Test
    public void keepsProgressOnOneLineWhenItFits() {
        assertEquals(1, MediaSizeBadgePolicy.getProgressLineCount(140f, 180f, 32f, 100f));
        assertEquals(1, MediaSizeBadgePolicy.getProgressLineCount(140f, 140f, 32f, 100f));
    }

    @Test
    public void keepsSingleRowWhenDurationLeavesTooLittleHeight() {
        assertEquals(1, MediaSizeBadgePolicy.getProgressLineCount(140f, 80f, 32f, 24f));
        assertEquals(2, MediaSizeBadgePolicy.getProgressLineCount(140f, 80f, 32f, 32f));
    }

    @Test
    public void excludesNonVideoDocuments() {
        assertFalse(MediaSizeBadgePolicy.shouldDrawCustomMediaSizeBadge(false, false, false));
    }
}
