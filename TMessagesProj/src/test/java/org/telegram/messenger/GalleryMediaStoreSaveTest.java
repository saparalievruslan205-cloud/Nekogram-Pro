package org.telegram.messenger;

import android.Manifest;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;
import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 33}, application = Application.class, shadows = {
        GalleryMediaStoreSaveTest.ShadowFileSafety.class, GalleryMediaStoreSaveTest.ShadowLog.class
})
public class GalleryMediaStoreSaveTest {
    private File source;
    private MediaProvider provider;

    @Before
    public void setUp() throws Exception {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        ShadowLog.lastError = null;
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
                Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        source = File.createTempFile("batch-source", ".jpg", RuntimeEnvironment.getApplication().getCacheDir());
        Files.write(source.toPath(), new byte[]{1, 2, 3, 4});
        provider = Robolectric.buildContentProvider(MediaProvider.class).create(MediaStore.AUTHORITY).get();
        provider.destination = new File(source.getParentFile(), "gallery-destination.jpg");
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider);
    }

    @Test
    public void photoIsCopiedAndPublishedWithoutStoragePermissions() throws Exception {
        Uri saved = save();
        assertNotNull(String.valueOf(ShadowLog.lastError), saved);
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(provider.destination.toPath()));
        assertEquals(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), provider.collection);
        assertEquals("batch.jpg", provider.inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals(Integer.valueOf(1), provider.inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals(Integer.valueOf(0), provider.published.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals(0, provider.deletes);
    }

    @Test
    public void failedPublicationRemovesUnfinishedMediaStoreItem() throws Exception {
        provider.failPublish = true;
        assertNull(save());
        assertNotNull(provider.published);
        assertEquals("Could not publish MediaStore item", ShadowLog.lastError.getMessage());
        assertEquals(1, provider.deletes);
    }

    private Uri save() throws Exception {
        for (Method method : MediaController.class.getDeclaredMethods()) {
            if (method.getName().equals("saveGalleryFile")) {
                method.setAccessible(true);
                return (Uri) method.invoke(null, 0, source, "batch.jpg", null, null);
            }
        }
        throw new AssertionError("Gallery writer not found");
    }

    /** A real resolver/provider boundary with a temporary file instead of device storage. */
    public static class MediaProvider extends ContentProvider {
        File destination;
        Uri collection;
        ContentValues inserted, published;
        boolean failPublish;
        int deletes;
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues values) {
            collection = uri;
            inserted = new ContentValues(values);
            return Uri.withAppendedPath(uri, "1");
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            published = new ContentValues(values);
            return failPublish ? 0 : 1;
        }
        @Override public int delete(Uri uri, String selection, String[] args) { deletes++; return 1; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return "image/jpeg"; }
    }

    @Implements(value = AndroidUtilities.class, isInAndroidSdk = false)
    public static class ShadowFileSafety {
        // Production checks use a native readlink implementation, unrelated to MediaStore.
        @Implementation protected static boolean isInternalUri(Uri uri) { return false; }
    }

    @Implements(value = FileLog.class, isInAndroidSdk = false)
    public static class ShadowLog {
        static Throwable lastError;
        @Implementation protected static void e(Throwable error) { lastError = error; }
    }
}
