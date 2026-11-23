package org.telegram.messenger;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class)
public class GalleryThumbnailReliabilityTest {

    @Test
    public void resolveMediaThumbDimensionUses256DefaultAndClamps() {
        assertEquals(256, ImageLoader.resolveMediaThumbDimension(0f));
        assertEquals(256, ImageLoader.resolveMediaThumbDimension(-10f));
        assertEquals(64, ImageLoader.resolveMediaThumbDimension(24f));
        assertEquals(180, ImageLoader.resolveMediaThumbDimension(180f));
        assertEquals(512, ImageLoader.resolveMediaThumbDimension(1024f));
    }

    @Test
    public void prepareFileThumbDecodeOptionsAlwaysClearsInJustDecodeBounds() {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        ImageLoader.prepareFileThumbDecodeOptions(new File("non-existent-gallery-image.jpg"), opts);
        assertFalse(opts.inJustDecodeBounds);
        assertEquals(1, opts.inSampleSize);
    }

    @Test
    public void normalizeDecodedBitmapRejectsNullAndRecycled() {
        assertNull(ImageLoader.normalizeDecodedBitmap(null));
        Bitmap valid = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        assertSame(valid, ImageLoader.normalizeDecodedBitmap(valid));
        valid.recycle();
        assertNull(ImageLoader.normalizeDecodedBitmap(valid));
    }

    @Test
    public void imageReceiverMarksCanceledLoadingOnlyForMatchingActiveFailure() throws Exception {
        ImageReceiver receiver = new ImageReceiver();
        int guid = receiver.getNewGuid();
        setField(receiver, "currentImageKey", "thumb_key_1");

        // Mismatched guid or key must not mark failed.
        receiver.onImageLoadFailed("thumb_key_1", ImageReceiver.TYPE_IMAGE, guid - 1);
        assertFalse(receiver.isCanceledLoading());
        receiver.onImageLoadFailed("other_key", ImageReceiver.TYPE_IMAGE, guid);
        assertFalse(receiver.isCanceledLoading());

        // Matching failure with no image drawable enables retry.
        receiver.onImageLoadFailed("thumb_key_1", ImageReceiver.TYPE_IMAGE, guid);
        assertTrue(receiver.isCanceledLoading());

        // If a drawable is already present, failure notification must not mark canceled.
        setField(receiver, "canceledLoading", false);
        setField(receiver, "currentImageDrawable", new ColorDrawable(0xff000000));
        receiver.onImageLoadFailed("thumb_key_1", ImageReceiver.TYPE_IMAGE, guid);
        assertFalse(receiver.isCanceledLoading());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
