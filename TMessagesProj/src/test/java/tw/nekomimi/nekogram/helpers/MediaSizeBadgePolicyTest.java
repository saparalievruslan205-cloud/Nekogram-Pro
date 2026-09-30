package tw.nekomimi.nekogram.helpers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaSizeBadgePolicyTest {
    @Test
    public void doesNotDrawForSingleVideo() {
        assertFalse(MediaSizeBadgePolicy.shouldDrawCustomVideoSizeBadge(true, false, false));
    }

    @Test
    public void doesNotDrawWhenTelegramBadgeIsVisible() {
        assertFalse(MediaSizeBadgePolicy.shouldDrawCustomVideoSizeBadge(true, true, true));
    }

    @Test
    public void drawsForAlbumVideoWhenTelegramBadgeIsHidden() {
        assertTrue(MediaSizeBadgePolicy.shouldDrawCustomVideoSizeBadge(true, true, false));
    }

    @Test
    public void keepsPhotoBadgeBehaviorUnchanged() {
        assertTrue(MediaSizeBadgePolicy.shouldDrawCustomMediaSizeBadge(true, false, false, null));
    }

    @Test
    public void doesNotDrawWhenTelegramBadgeVisibilityIsUnknown() {
        assertFalse(MediaSizeBadgePolicy.shouldDrawCustomVideoSizeBadge(true, true, (Boolean) null));
        assertFalse(MediaSizeBadgePolicy.shouldDrawCustomMediaSizeBadge(false, false, false, null));
    }
}
