package org.telegram.messenger;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class)
public class PagedGalleryIndexTest {
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext = context;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        field(null, "pendingMediaUris", ArrayList.class).clear();
        field(null, "queryingMediaUris", HashSet.class).clear();
        field(null, "repeatMediaQueries", HashSet.class).clear();
        setStatic("sharedIndex", null);
        setStatic("sharedSignal", null);
        setStatic("pendingMediaStoreChange", null);
        MediaController.allMediaAlbums.clear();
        MediaController.allPhotoAlbums.clear();
        MediaController.allMediaAlbumEntry = null;
        MediaController.allPhotosAlbumEntry = null;
        MediaController.allVideosAlbumEntry = null;
    }

    @Test
    public void reservesSnapshotAndKeepsNotificationArrivingDuringQuery() throws Exception {
        Uri uri = uri(10);
        enqueue(uri);
        assertEquals(Arrays.asList(uri), takePending());
        assertTrue(field(null, "pendingMediaUris", ArrayList.class).isEmpty());

        enqueue(uri);
        assertTrue(takePending().isEmpty());
        assertEquals(Arrays.asList(uri), field(null, "pendingMediaUris", ArrayList.class));
        assertEquals(1, field(null, "repeatMediaQueries", HashSet.class).size());

        invoke(null, "finishMediaQuery", context, uri);
        assertEquals(Arrays.asList(uri), takePending());
        assertTrue(field(null, "repeatMediaQueries", HashSet.class).isEmpty());
    }

    @Test
    public void retainsSpecificUrisWhenAllQuerySlotsAreBusy() throws Exception {
        for (int i = 1; i <= 6; i++) enqueue(uri(i));
        assertEquals(4, takePending().size());
        assertEquals(Arrays.asList(uri(5), uri(6)), field(null, "pendingMediaUris", ArrayList.class));
        invoke(null, "finishMediaQuery", context, uri(1));
        assertEquals(Arrays.asList(uri(5)), takePending());
        assertEquals(Arrays.asList(uri(6)), field(null, "pendingMediaUris", ArrayList.class));
    }

    @Test
    public void doesNotLoseInFlightUriWhenPendingQueueIsFull() throws Exception {
        Uri active = uri(10);
        enqueue(active);
        takePending();
        for (int i = 100; i < 132; i++) enqueue(uri(i));
        enqueue(active);
        assertTrue(field(null, "pendingMediaUris", ArrayList.class).contains(active));
    }

    @Test
    public void concurrentNotificationsSurviveSnapshotAndCompletion() throws Exception {
        Uri uri = uri(10);
        enqueue(uri);
        takePending();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread observer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 1000; i++) {
                    PagedGalleryIndex.onGalleryChanged(context, uri);
                }
            } catch (Throwable e) {
                error.set(e);
            }
        });
        observer.start();
        start.countDown();
        for (int i = 0; i < 100; i++) takePending();
        observer.join(5000);
        assertFalse(observer.isAlive());
        assertNull(error.get());
        invoke(null, "finishMediaQuery", context, uri);
        assertEquals(Arrays.asList(uri), takePending());
    }

    @Test
    public void removesBaseRowWithoutShiftingOtherRowsOrStableIds() throws Exception {
        MediaController.PhotoEntry first = entry(30, false);
        MediaController.PhotoEntry removed = entry(20, false);
        MediaController.PhotoEntry last = entry(10, true);
        PagedGalleryIndex index = index(first, removed, last);
        long stable = index.getStableId(index.getAllMedia(), 2);
        assertTrue((Boolean) invoke(index, "removeMediaEntry", "external_primary", 20));
        assertEquals(2, index.getAllMedia().size());
        assertEquals(30, index.getId(index.getAllMedia(), 0));
        assertEquals(10, index.getId(index.getAllMedia(), 1));
        assertEquals(stable, index.getStableId(index.getAllMedia(), 1));
        assertEquals(1, index.findPosition(index.getAllMedia(), last));
        assertEquals(-1, index.findPosition(index.getAllMedia(), removed));
        assertFalse(field(index, "cache", LinkedHashMap.class).containsKey(1));
        assertEquals(1, index.getAllPhotos().size());
        assertEquals(1, index.getAllVideos().size());
        assertSame(last, index.getCachedPhoto(index.getAllMedia(), 1));
    }

    @Test
    public void removesIndexedRowEvenWhenItsPhotoEntryIsNotCached() throws Exception {
        PagedGalleryIndex index = index(entry(10, false));
        field(index, "cache", LinkedHashMap.class).clear();
        assertTrue((Boolean) invoke(index, "removeMediaEntry", "external_primary", 10));
        assertEquals(0, index.getAllMedia().size());
        assertEquals(1, index.getAlbums().size());
        assertEquals(1, index.getPhotoAlbums().size());
        assertNull(index.getAllMedia().coverPhoto);
    }

    @Test
    public void removesRecentInsertionFromAllAlbumsAndCanReinsertIt() throws Exception {
        PagedGalleryIndex index = index();
        MediaController.PhotoEntry photo = entry(10, false);
        assertTrue((Boolean) invoke(index, "applyMediaEntry", photo));
        assertTrue((Boolean) invoke(index, "removeMediaEntry", "external_primary", 10));
        assertEquals(0, index.getAllMedia().size());
        assertEquals(0, index.getAllPhotos().size());
        assertTrue(field(index, "recentByKey", HashMap.class).isEmpty());
        assertEquals(1, index.getAlbums().size());
        assertTrue((Boolean) invoke(index, "applyMediaEntry", photo));
        assertEquals(1, index.getAllMedia().size());
        assertEquals(0, index.findPosition(index.getAllMedia(), photo));
        assertTrue(index.getAlbums().contains(index.getAllPhotos()));
    }

    @Test
    public void updatesCoversAndPrunesOnlyEmptyAlbums() throws Exception {
        MediaController.PhotoEntry first = entry(20, false);
        MediaController.PhotoEntry next = entry(10, false);
        PagedGalleryIndex index = index(first, next);
        invoke(index, "removeMediaEntry", "external", 20);
        assertSame(next, index.getAllMedia().coverPhoto);
        assertSame(next, index.getAllPhotos().coverPhoto);
        assertSame(next, index.findAlbum(7, false).coverPhoto);
        assertSame(next, index.findPhotoAlbum(7).coverPhoto);
        invoke(index, "removeMediaEntry", "external", 10);
        assertEquals(Arrays.asList(index.getAllMedia()), index.getAlbums());
        assertEquals(Arrays.asList(index.getAllPhotos()), index.getPhotoAlbums());
    }

    @Test
    public void deletionHonorsVolumeAndAggregateExternalAlias() throws Exception {
        MediaController.PhotoEntry primary = entry(10, false);
        MediaController.PhotoEntry sd = entry(10, false);
        sd.mediaStoreUri = Uri.parse("content://media/abcd-1234/images/media/10");
        PagedGalleryIndex index = index(primary, sd);
        assertTrue((Boolean) invoke(index, "removeMediaEntry", "external_primary", 10));
        assertEquals(1, index.getAllMedia().size());
        assertSame(sd, index.getCachedPhoto(index.getAllMedia(), 0));
        assertFalse(PagedGalleryIndex.matchesMediaEntry("external_primary", 10, sd));
        assertTrue((Boolean) invoke(index, "removeMediaEntry", "external", 10));
        assertEquals(0, index.getAllMedia().size());
    }

    @Test
    public void successfulEmptyQueryDeletesKnownRowAndNotifiesSynchronously() throws Exception {
        PagedGalleryIndex index = index(entry(10, false));
        setStatic("sharedIndex", index);
        NotificationCenter center = NotificationCenter.getGlobalInstance();
        AtomicReference<Object[]> deleted = new AtomicReference<>();
        center.addObserver((id, account, args) -> deleted.set(args), NotificationCenter.pagedGalleryItemDeleted);
        // Both mechanisms normally delay notifications. Deletion must bypass both.
        field(center, "postponeCallbackList", ArrayList.class).add((NotificationCenter.PostponeNotificationCallback) (id, account, args) -> true);
        setField(center, "animationInProgressCount", 1);
        assertTrue((Boolean) invoke(null, "reconcileMissingMedia", "external", 10, queryResult(true)));
        assertEquals(0, index.getAllMedia().size());
        assertArrayEquals(new Object[]{"external", 10}, deleted.get());
    }

    @Test
    public void emptyQueryForUnknownRowIsStillEligibleForRetry() throws Exception {
        PagedGalleryIndex index = index(entry(10, false));
        setStatic("sharedIndex", index);
        assertFalse((Boolean) invoke(null, "reconcileMissingMedia", "external", 99, queryResult(true)));
        assertEquals(1, index.getAllMedia().size());
    }

    @Test
    public void knownDeletedRowFinishesAfterOneProviderQueryWithoutRetry() throws Exception {
        PagedGalleryIndex index = index(entry(10, false));
        setStatic("sharedIndex", index);
        TestProvider provider = new TestProvider();
        provider.returnEmpty = true;
        ShadowContentResolver.registerProviderInternal("media", provider);
        enqueue(uri(10));
        invoke(null, "processDeferredMediaChanges", context);
        drainProviderQueue();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        drainProviderQueue();
        assertEquals(1, provider.queries.get());
        assertEquals(0, index.getAllMedia().size());
        assertTrue(field(null, "queryingMediaUris", HashSet.class).isEmpty());
    }

    @Test
    public void notYetPublishedRowGetsAllBoundedRetries() throws Exception {
        PagedGalleryIndex index = index(entry(10, false));
        setStatic("sharedIndex", index);
        TestProvider provider = new TestProvider();
        provider.returnEmpty = true;
        ShadowContentResolver.registerProviderInternal("media", provider);
        enqueue(uri(99));
        invoke(null, "processDeferredMediaChanges", context);
        finishRetries();
        assertEquals(4, provider.queries.get());
        assertEquals(1, index.getAllMedia().size());
        assertTrue(field(null, "queryingMediaUris", HashSet.class).isEmpty());
    }

    @Test
    public void notificationDuringFinalRetryStartsAnotherQueryAndFindsPublishedRow() throws Exception {
        PagedGalleryIndex index = index();
        setStatic("sharedIndex", index);
        TestProvider provider = new TestProvider();
        provider.publishAfterQueries = 4;
        provider.onFinalEmptyQuery = () -> PagedGalleryIndex.onGalleryChanged(context, uri(99));
        ShadowContentResolver.registerProviderInternal("media", provider);
        enqueue(uri(99));
        invoke(null, "processDeferredMediaChanges", context);
        finishRetries();
        drainProviderQueue();
        assertEquals(5, provider.queries.get());
        assertEquals(1, index.getAllMedia().size());
        assertEquals(99, index.getId(index.getAllMedia(), 0));
        assertTrue(field(null, "queryingMediaUris", HashSet.class).isEmpty());
        assertTrue(field(null, "pendingMediaUris", ArrayList.class).isEmpty());
    }

    @Test
    public void providerFailureNeverDeletesKnownRowEvenAfterRetries() throws Exception {
        PagedGalleryIndex index = index(entry(10, false));
        setStatic("sharedIndex", index);
        TestProvider provider = new TestProvider();
        provider.failQuery = true;
        ShadowContentResolver.registerProviderInternal("media", provider);
        enqueue(uri(10));
        invoke(null, "processDeferredMediaChanges", context);
        finishRetries();
        assertEquals(4, provider.queries.get());
        assertEquals(1, index.getAllMedia().size());
        assertTrue(field(null, "queryingMediaUris", HashSet.class).isEmpty());
    }

    private void finishRetries() throws Exception {
        for (int attempt = 0; attempt <= 3; attempt++) {
            drainProviderQueue();
            if (attempt < 3) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        }
    }

    private void drainProviderQueue() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        Utilities.globalQueue.postRunnable(finished::countDown);
        assertTrue("Provider queue did not finish", finished.await(5, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void nullCursorFailureAndCollectionQueryDoNotDeleteKnownRows() throws Exception {
        PagedGalleryIndex index = index(entry(10, false));
        setStatic("sharedIndex", index);
        assertFalse((Boolean) invoke(null, "reconcileMissingMedia", "external", 10, null));
        assertFalse((Boolean) invoke(null, "reconcileMissingMedia", "external", 10, queryResult(false)));
        assertFalse((Boolean) invoke(null, "reconcileMissingMedia", "external", -1, queryResult(true)));
        assertEquals(1, index.getAllMedia().size());
    }

    @Test
    public void distinguishesZeroRowsFromNullCursorAndFilteredRows() throws Exception {
        TestProvider provider = new TestProvider();
        ShadowContentResolver.registerProviderInternal("media", provider);
        provider.cursor = new MatrixCursor(new String[15]);
        assertTrue(field(invoke(null, "queryMediaEntries", context, "external", 10), "emptyCursor", Boolean.class));
        provider.cursor = null;
        assertFalse(field(invoke(null, "queryMediaEntries", context, "external", 10), "emptyCursor", Boolean.class));
        provider.cursor = new MatrixCursor(new String[15]);
        provider.cursor.addRow(new Object[]{10, 1, 7, "Camera", 1, 0, 1, "", "image/jpeg", 1, 1, 1, 0, 0, "external_primary"});
        Object result = invoke(null, "queryMediaEntries", context, "external", 10);
        assertFalse(field(result, "emptyCursor", Boolean.class));
        assertTrue(field(result, "entries", ArrayList.class).isEmpty());
    }

    @Test
    public void removesLegacyAlbumRowAndCoverTogether() throws Exception {
        MediaController.PhotoEntry removed = entry(20, false);
        removed.mediaStoreUri = null; // Entries produced by the legacy full scanner.
        MediaController.PhotoEntry next = entry(10, false);
        MediaController.AlbumEntry all = new MediaController.AlbumEntry(0, "All", removed);
        MediaController.AlbumEntry bucket = new MediaController.AlbumEntry(7, "Camera", removed);
        for (MediaController.AlbumEntry album : Arrays.asList(all, bucket)) {
            album.addPhoto(removed);
            album.addPhoto(next);
        }
        MediaController.allMediaAlbumEntry = all;
        MediaController.allMediaAlbums.addAll(Arrays.asList(all, bucket));
        MediaController.removeGalleryEntry("external", 20);
        assertEquals(Arrays.asList(next), all.photos);
        assertNull(all.photosByIds.get(20));
        assertSame(next, all.coverPhoto);
        MediaController.removeGalleryEntry("external", 10);
        assertEquals(Arrays.asList(all), MediaController.allMediaAlbums);
        assertNull(all.coverPhoto);
    }

    @Test
    public void inFlightPageCannotResurrectDeletedAndReinsertedBaseRow() throws Exception {
        MediaController.PhotoEntry first = entry(20, false);
        MediaController.PhotoEntry removed = entry(10, false);
        PagedGalleryIndex index = index(first, removed);
        field(index, "cache", LinkedHashMap.class).remove(1);
        TestProvider provider = new TestProvider();
        provider.entered = new CountDownLatch(1);
        provider.release = new CountDownLatch(1);
        provider.cursor = new MatrixCursor(new String[]{"_id", "media_type", "bucket_id", "_data", "date_added", "datetaken", "orientation", "duration", "width", "height", "_size", "bucket_display_name", "volume_name"});
        provider.cursor.addRow(new Object[]{20, 1, 7, "/media/20", 20, 0, 0, 0, 1, 1, 10, "Camera", "external_primary"});
        provider.cursor.addRow(new Object[]{10, 1, 7, "/media/10", 10, 0, 0, 0, 1, 1, 10, "Camera", "external_primary"});
        ShadowContentResolver.registerProviderInternal("media", provider);
        CountDownLatch completed = new CountDownLatch(1);
        index.getPhoto(index.getAllMedia(), 1, new PagedGalleryIndex.Listener() {
            @Override public void onIndexReady(PagedGalleryIndex ready) { }
            @Override public void onPageReady() { completed.countDown(); }
        });
        try {
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            assertTrue((Boolean) invoke(index, "removeMediaEntry", "external", 10));
            assertTrue((Boolean) invoke(index, "applyMediaEntry", removed));
        } finally {
            provider.release.countDown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (completed.getCount() > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertEquals(0, completed.getCount());
        assertFalse(field(index, "cache", LinkedHashMap.class).containsKey(1));
        assertEquals(2, index.getAllMedia().size());
        assertSame(removed, index.getCachedPhoto(index.getAllMedia(), 0));
    }

    @Test
    public void legacyObserversSkipFullReloadWhenPagedIndexIsActive() throws Exception {
        setStatic("sharedIndex", index());
        // Register the real paged observer without starting an index scan.
        PagedGalleryIndex.onGalleryChanged(context, uri(10));
        shadowOf(Looper.getMainLooper()).idle();
        for (String name : Arrays.asList("GalleryObserverInternal", "GalleryObserverExternal")) {
            Constructor<?> constructor = Class.forName(MediaController.class.getName() + "$" + name).getDeclaredConstructor();
            constructor.setAccessible(true);
            ((android.database.ContentObserver) constructor.newInstance()).onChange(false);
        }
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(field(MediaController.class, "refreshGalleryRunnable", Object.class));
        assertFalse(field(MediaController.class, "galleryLoadInProgress", Boolean.class));
    }

    private static Uri uri(int id) {
        return Uri.parse("content://media/external/images/media/" + id);
    }

    private static MediaController.PhotoEntry entry(int id, boolean video) {
        MediaController.PhotoEntry entry = new MediaController.PhotoEntry(7, id, id, "/media/" + id, 0, video, 1, 1, 10);
        entry.mediaStoreUri = Uri.parse("content://media/external_primary/" + (video ? "video" : "images") + "/media/" + id);
        entry.bucketName = "Camera";
        return entry;
    }

    private void enqueue(Uri uri) throws Exception {
        synchronized (field(null, "uriLock", Object.class)) {
            invoke(null, "enqueueMediaUri", uri);
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Uri> takePending() throws Exception {
        return (ArrayList<Uri>) invoke(null, "takePendingMediaQueries");
    }

    private PagedGalleryIndex index(MediaController.PhotoEntry... entries) throws Exception {
        PagedGalleryIndex.Album media = album(0, "All Media", true);
        PagedGalleryIndex.Album photos = album(0, "All Photos", false);
        PagedGalleryIndex.Album videos = album(0, "All Videos", false);
        PagedGalleryIndex.Album bucket = album(7, "Camera", false);
        PagedGalleryIndex.Album photoBucket = album(7, "Camera", false);
        HashMap<String, SparseIntArray> positions = new HashMap<>();
        int[] ids = new int[entries.length];
        long[] dates = new long[entries.length];
        String[] volumes = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            MediaController.PhotoEntry entry = entries[i];
            ids[i] = entry.imageId;
            dates[i] = entry.dateTaken;
            volumes[i] = entry.mediaStoreUri.getPathSegments().get(0);
            positions.computeIfAbsent(volumes[i], ignored -> new SparseIntArray()).put(ids[i], i);
            for (PagedGalleryIndex.Album album : Arrays.asList(media, entry.isVideo ? videos : photos, bucket)) {
                invoke(field(album, "positions", Object.class), "add", i);
                if (album.coverPhoto == null) album.coverPhoto = entry;
            }
            if (!entry.isVideo) {
                invoke(field(photoBucket, "positions", Object.class), "add", i);
                if (photoBucket.coverPhoto == null) photoBucket.coverPhoto = entry;
            }
        }
        ArrayList<PagedGalleryIndex.Album> albums = new ArrayList<>();
        albums.add(media);
        if (photos.size() > 0) albums.add(photos);
        if (videos.size() > 0) albums.add(videos);
        if (bucket.size() > 0) albums.add(bucket);
        ArrayList<PagedGalleryIndex.Album> photoAlbums = new ArrayList<>();
        photoAlbums.add(photos);
        if (photoBucket.size() > 0) photoAlbums.add(photoBucket);
        Constructor<?> constructor = PagedGalleryIndex.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        PagedGalleryIndex index = (PagedGalleryIndex) constructor.newInstance(context, ids, dates, volumes, positions, albums, photoAlbums, media, photos, videos);
        LinkedHashMap<Integer, MediaController.PhotoEntry> cache = field(index, "cache", LinkedHashMap.class);
        for (int i = 0; i < entries.length; i++) cache.put(i, entries[i]);
        return index;
    }

    private static PagedGalleryIndex.Album album(int bucket, String name, boolean all) throws Exception {
        Constructor<?> listConstructor = Class.forName(PagedGalleryIndex.class.getName() + "$IntList").getDeclaredConstructor();
        listConstructor.setAccessible(true);
        Constructor<?> constructor = PagedGalleryIndex.Album.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (PagedGalleryIndex.Album) constructor.newInstance(bucket, name, false, all, listConstructor.newInstance());
    }

    private static Object queryResult(boolean empty) throws Exception {
        Constructor<?> constructor = Class.forName(PagedGalleryIndex.class.getName() + "$MediaQueryResult").getDeclaredConstructor();
        constructor.setAccessible(true);
        Object result = constructor.newInstance();
        setField(result, "emptyCursor", empty);
        return result;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?> type = target == null ? PagedGalleryIndex.class : target.getClass();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
                    throw e;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Class<?> owner = target == null ? PagedGalleryIndex.class : target instanceof Class ? (Class<?>) target : target.getClass();
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target instanceof Class ? null : target));
    }

    private static void setStatic(String name, Object value) throws Exception {
        setField(PagedGalleryIndex.class, name, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = (target instanceof Class ? (Class<?>) target : target.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        field.set(target instanceof Class ? null : target, value);
    }

    public static class TestProvider extends ContentProvider {
        MatrixCursor cursor;
        CountDownLatch entered;
        CountDownLatch release;
        boolean returnEmpty;
        boolean failQuery;
        AtomicInteger queries = new AtomicInteger();
        int publishAfterQueries;
        Runnable onFinalEmptyQuery;

        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            int count = queries.incrementAndGet();
            if (publishAfterQueries > 0) {
                MatrixCursor result = new MatrixCursor(projection);
                if (count == publishAfterQueries) onFinalEmptyQuery.run();
                if (count > publishAfterQueries) {
                    result.addRow(new Object[]{99, 1, 7, "Camera", 99, 0, 99, "/media/99", "image/jpeg", 1, 1, 10, 0, 0, "external_primary"});
                }
                return result;
            }
            if (failQuery) throw new IllegalStateException("Provider temporarily unavailable");
            if (returnEmpty) return new MatrixCursor(projection);
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Page query was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return cursor;
        }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
