package org.telegram.messenger;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** A compact index of MediaStore rows. Only visible rows become PhotoEntry objects. */
public final class PagedGalleryIndex {
    private static final int PAGE_SIZE = 64;
    private static final int CACHE_SIZE = 768;
    private static final int MAX_REQUESTS = 4;
    private static final int IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
    private static final int VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
    private static PagedGalleryIndex sharedIndex;
    private static CancellationSignal sharedSignal;
    private static final ArrayList<Listener> waitingListeners = new ArrayList<>();
    private static boolean sharedDirty;
    private static Runnable pendingSharedRefresh;
    private static ContentObserver mediaStoreObserver;
    private static Runnable pendingMediaStoreChange;
    private static final ArrayList<Uri> pendingMediaUris = new ArrayList<>();
    private static final HashSet<String> queryingMediaUris = new HashSet<>();
    private static final int MAX_PENDING_MEDIA_URIS = 32;
    private static final int MAX_MEDIA_QUERY_RETRIES = 2;
    private static final int MAX_RECENT_MEDIA_ENTRIES = 2048;

    public static void warmUp(Context context) {
        registerMediaStoreObserver(context.getApplicationContext());
        if (hasGalleryPermission(context)) {
            requestShared(context, null);
        }
    }

    private static void registerMediaStoreObserver(Context context) {
        if (mediaStoreObserver != null) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        mediaStoreObserver = new ContentObserver(new Handler(context.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                onGalleryChanged(context, uri);
            }
        };
        try {
            resolver.registerContentObserver(Uri.parse("content://media"), true, mediaStoreObserver);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void requestShared(Context context, Listener listener) {
        registerMediaStoreObserver(context.getApplicationContext());
        if (!hasGalleryPermission(context)) {
            if (listener != null) {
                listener.onIndexReady(null);
            }
            return;
        }
        if (sharedIndex != null) {
            if (listener != null) {
                listener.onIndexReady(sharedIndex);
            }
            if (!sharedDirty && sharedSignal == null) {
                return;
            }
        }
        if (listener != null) {
            waitingListeners.add(listener);
        }
        if (sharedSignal != null || pendingSharedRefresh != null) {
            return;
        }
        startSharedLoad(context.getApplicationContext());
    }

    public static void onGalleryChanged(Context context) {
        onGalleryChanged(context, null);
    }

    public static void onGalleryChanged(Context context, Uri uri) {
        if (context == null) {
            return;
        }
        registerMediaStoreObserver(context.getApplicationContext());
        if (pendingMediaUris.size() < MAX_PENDING_MEDIA_URIS && !pendingMediaUris.contains(uri)) {
            pendingMediaUris.add(uri);
        }
        if (pendingMediaStoreChange != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingMediaStoreChange);
        }
        Context appContext = context.getApplicationContext();
        pendingMediaStoreChange = () -> {
            pendingMediaStoreChange = null;
            ArrayList<Uri> changes = new ArrayList<>(pendingMediaUris);
            pendingMediaUris.clear();
            for (Uri changedUri : changes) {
                queryChangedMedia(appContext, changedUri, 0);
            }
        };
        AndroidUtilities.runOnUIThread(pendingMediaStoreChange, 300);
    }

    private static void queryChangedMedia(Context context, Uri uri, int attempt) {
        if (!hasGalleryPermission(context)) {
            return;
        }
        String requestKey = uri == null ? "latest:external" : uri.toString();
        if (attempt == 0 && !queryingMediaUris.add(requestKey)) {
            return;
        }
        if (sharedIndex == null) {
            if (pendingMediaUris.size() < MAX_PENDING_MEDIA_URIS && !pendingMediaUris.contains(uri)) {
                pendingMediaUris.add(uri);
            }
            queryingMediaUris.remove(requestKey);
            return;
        }
        String volume = getVolumeFromUri(uri);
        int mediaId = getMediaIdFromUri(uri);
        Utilities.globalQueue.postRunnable(() -> {
            MediaController.PhotoEntry entry = null;
            try {
                entry = queryMediaEntry(context, volume, mediaId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            MediaController.PhotoEntry result = entry;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null && sharedIndex != null) {
                    boolean inserted = sharedIndex.applyMediaEntry(result);
                    MediaController.updateGalleryEntry(result);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pagedGalleryItemChanged, result, inserted);
                    queryingMediaUris.remove(requestKey);
                } else if (attempt < MAX_MEDIA_QUERY_RETRIES && sharedIndex != null) {
                    AndroidUtilities.runOnUIThread(() -> queryChangedMedia(context, uri, attempt + 1), 400L + attempt * 300L);
                } else {
                    queryingMediaUris.remove(requestKey);
                }
            });
        });
    }

    private static int getMediaIdFromUri(Uri uri) {
        if (uri == null) {
            return -1;
        }
        try {
            String last = uri.getLastPathSegment();
            return last == null ? -1 : Integer.parseInt(last);
        } catch (Exception ignore) {
            return -1;
        }
    }

    private static String getVolumeFromUri(Uri uri) {
        if (uri == null || uri.getPathSegments().isEmpty()) {
            return "external";
        }
        String volume = uri.getPathSegments().get(0);
        return TextUtils.isEmpty(volume) ? "external" : volume;
    }

    private static MediaController.PhotoEntry queryMediaEntry(Context context, String requestedVolume, int mediaId) {
        ContentResolver resolver = context.getContentResolver();
        String volume = Build.VERSION.SDK_INT >= 29 ? requestedVolume : "external";
        Uri filesUri = MediaStore.Files.getContentUri(volume);
        ArrayList<String> projectionList = new ArrayList<>(Arrays.asList(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Video.VideoColumns.DURATION,
                MediaStore.Images.ImageColumns.ORIENTATION
        ));
        if (Build.VERSION.SDK_INT >= 29) {
            projectionList.add(MediaStore.MediaColumns.VOLUME_NAME);
        }
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (?, ?) AND " +
                MediaStore.Files.FileColumns.DATA + " IS NOT NULL AND " + MediaStore.Files.FileColumns.DATA + " != ''";
        ArrayList<String> args = new ArrayList<>(Arrays.asList(String.valueOf(IMAGE), String.valueOf(VIDEO)));
        if (Build.VERSION.SDK_INT >= 29) {
            selection += " AND " + MediaStore.MediaColumns.IS_PENDING + " = 0";
        }
        if (mediaId >= 0) {
            selection += " AND " + MediaStore.Files.FileColumns._ID + " = ?";
            args.add(String.valueOf(mediaId));
        }
        String order = "CASE WHEN " + MediaStore.Images.ImageColumns.DATE_TAKEN + " > 0 THEN " +
                MediaStore.Images.ImageColumns.DATE_TAKEN + " / 1000 ELSE " +
                MediaStore.Files.FileColumns.DATE_ADDED + " END DESC, " +
                MediaStore.Files.FileColumns._ID + " DESC LIMIT 1";
        Cursor cursor = null;
        try {
            cursor = resolver.query(filesUri, projectionList.toArray(new String[0]), selection,
                    args.toArray(new String[0]), order);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int id = cursor.getInt(0);
            int type = cursor.getInt(1);
            int bucketId = cursor.isNull(2) ? 0 : cursor.getInt(2);
            String bucketName = cursor.getString(3);
            long dateAdded = cursor.getLong(4);
            long dateTaken = cursor.getLong(5);
            long dateModified = cursor.getLong(6);
            String path = cursor.getString(7);
            if (TextUtils.isEmpty(path)) {
                return null;
            }
            String mimeType = cursor.getString(8);
            if (TextUtils.isEmpty(mimeType) || type != IMAGE && type != VIDEO) {
                return null;
            }
            int width = cursor.getInt(9);
            int height = cursor.getInt(10);
            long size = cursor.getLong(11);
            int duration = (int) (cursor.getLong(12) / 1000);
            int orientation = cursor.getInt(13);
            String rowVolume = Build.VERSION.SDK_INT >= 29 ? cursor.getString(14) : "external";
            if (TextUtils.isEmpty(rowVolume)) {
                rowVolume = volume;
            }
            long date = dateTaken > 0 ? dateTaken / 1000 : dateAdded > 0 ? dateAdded : dateModified;
            boolean video = type == VIDEO;
            MediaController.PhotoEntry entry = new MediaController.PhotoEntry(bucketId, id, date, path,
                    video ? 0 : orientation, video ? duration : 0, video, width, height, size);
            entry.mediaStoreUri = buildMediaUri(rowVolume, id, video);
            entry.bucketName = bucketName;
            return entry;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static Uri buildMediaUri(String volume, int id, boolean video) {
        Uri collection;
        if (Build.VERSION.SDK_INT >= 29) {
            collection = video ? MediaStore.Video.Media.getContentUri(volume) : MediaStore.Images.Media.getContentUri(volume);
        } else {
            collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        return android.content.ContentUris.withAppendedId(collection, id);
    }

    private static String mediaKey(String volume, int id) {
        return (volume == null ? "external" : volume) + '\u0000' + id;
    }

    private boolean applyMediaEntry(MediaController.PhotoEntry entry) {
        if (closed || entry == null || entry.mediaStoreUri == null) {
            return false;
        }
        String volume = Build.VERSION.SDK_INT >= 29 ? entry.mediaStoreUri.getPathSegments().get(0) : "external";
        String key = mediaKey(volume, entry.imageId);
        SparseIntArray volumePositions = basePositionsByVolume.get(volume);
        int basePosition = volumePositions == null ? -2 : volumePositions.get(entry.imageId, -2);
        if (basePosition >= 0) {
            boolean wasCached = cache.containsKey(basePosition);
            cache.remove(basePosition);
            unavailable.remove(basePosition);
            if (wasCached) {
                cache.put(basePosition, entry);
            }
            updateCovers(entry);
            return false;
        }

        MediaController.PhotoEntry previous = recentByKey.get(key);
        if (previous == null && recentByKey.size() >= MAX_RECENT_MEDIA_ENTRIES) {
            sharedDirty = true;
            scheduleSharedRefresh(context);
            return false;
        }
        recentByKey.put(key, entry);
        if (volumePositions == null) {
            volumePositions = new SparseIntArray();
            basePositionsByVolume.put(volume, volumePositions);
        }
        volumePositions.put(entry.imageId, -1);
        insertRecent(allMedia, entry);
        if (entry.isVideo) {
            insertRecent(allVideos, entry);
        } else {
            insertRecent(allPhotos, entry);
        }

        Album bucket = entry.bucketId == 0 ? allMedia : findBucket(albums, entry.bucketId, false);
        boolean newMediaAlbum = bucket == null && entry.bucketId != 0;
        if (bucket == null) {
            bucket = new Album(entry.bucketId, TextUtils.isEmpty(entry.bucketName) ? "" : entry.bucketName, false, false, new IntList());
            int index = 0;
            while (index < albums.size() && albums.get(index).bucketId == 0) {
                index++;
            }
            albums.add(index, bucket);
        }
        insertRecent(bucket, entry);

        boolean newPhotoAlbum = false;
        if (!entry.isVideo) {
            Album photoBucket = entry.bucketId == 0 ? allPhotos : findBucket(photoAlbums, entry.bucketId, false);
            newPhotoAlbum = photoBucket == null && entry.bucketId != 0;
            if (photoBucket == null) {
                photoBucket = new Album(entry.bucketId, bucket.name, false, false, new IntList());
                int index = 0;
                while (index < photoAlbums.size() && photoAlbums.get(index).bucketId == 0) {
                    index++;
                }
                photoAlbums.add(index, photoBucket);
            }
            insertRecent(photoBucket, entry);
        }
        updateCovers(entry);
        return previous == null || newMediaAlbum || newPhotoAlbum;
    }

    private static Album findBucket(ArrayList<Album> source, int bucketId, boolean videoOnly) {
        if (bucketId == 0) {
            return null;
        }
        for (Album album : source) {
            if (album.bucketId == bucketId && album.videoOnly == videoOnly) {
                return album;
            }
        }
        return null;
    }

    private static void insertRecent(Album album, MediaController.PhotoEntry entry) {
        if (album == null) {
            return;
        }
        int existing = -1;
        for (int i = 0; i < album.recentEntries.size(); i++) {
            MediaController.PhotoEntry current = album.recentEntries.get(i);
            if (current.mediaStoreUri != null && current.mediaStoreUri.equals(entry.mediaStoreUri)) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            album.recentEntries.remove(existing);
        }
        int position = 0;
        while (position < album.recentEntries.size() && album.recentEntries.get(position).dateTaken >= entry.dateTaken) {
            position++;
        }
        album.recentEntries.add(position, entry);
        if (album.coverPhoto == null || entry.dateTaken >= album.coverPhoto.dateTaken) {
            album.coverPhoto = entry;
        }
    }

    private void updateCovers(MediaController.PhotoEntry entry) {
        updateAlbumCover(allMedia, entry);
        if (entry.isVideo) {
            updateAlbumCover(allVideos, entry);
        } else {
            updateAlbumCover(allPhotos, entry);
        }
        updateBucketCover(albums, entry);
        if (!entry.isVideo) {
            updateBucketCover(photoAlbums, entry);
        }
    }

    private static void updateBucketCover(ArrayList<Album> source, MediaController.PhotoEntry entry) {
        Album bucket = findBucket(source, entry.bucketId, false);
        updateAlbumCover(bucket, entry);
    }

    private static void updateAlbumCover(Album album, MediaController.PhotoEntry entry) {
        if (album != null && (album.coverPhoto == null || album.coverPhoto.mediaStoreUri != null && album.coverPhoto.mediaStoreUri.equals(entry.mediaStoreUri) || entry.dateTaken > album.coverPhoto.dateTaken)) {
            album.coverPhoto = entry;
        }
    }

    public int findPosition(Album album, MediaController.PhotoEntry entry) {
        if (album == null || entry == null) {
            return -1;
        }
        for (int i = 0; i < album.recentEntries.size(); i++) {
            MediaController.PhotoEntry recent = album.recentEntries.get(i);
            if (recent.mediaStoreUri != null && recent.mediaStoreUri.equals(entry.mediaStoreUri)) {
                return i;
            }
        }
        if (entry.mediaStoreUri == null) {
            return -1;
        }
        String volume = Build.VERSION.SDK_INT >= 29 ? entry.mediaStoreUri.getPathSegments().get(0) : "external";
        SparseIntArray positions = basePositionsByVolume.get(volume);
        int global = positions == null ? -1 : positions.get(entry.imageId, -1);
        if (global < 0) {
            return -1;
        }
        if (album.allMedia) {
            return album.recentEntries.size() + global;
        }
        int local = Arrays.binarySearch(album.positions.values, 0, album.positions.size, global);
        return local < 0 ? -1 : album.recentEntries.size() + local;
    }

    public static void removeSharedListener(Listener listener) {
        waitingListeners.remove(listener);
    }

    public static boolean isSharedRefreshPending() {
        return sharedDirty || sharedSignal != null || pendingSharedRefresh != null;
    }

    private static void scheduleSharedRefresh(Context context) {
        if (pendingSharedRefresh != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSharedRefresh);
        }
        pendingSharedRefresh = () -> {
            pendingSharedRefresh = null;
            if (sharedDirty && sharedSignal == null) {
                if (hasGalleryPermission(context)) {
                    startSharedLoad(context);
                } else {
                    sharedDirty = false;
                    if (sharedIndex != null) {
                        sharedIndex.close();
                        sharedIndex = null;
                    }
                    notifyWaitingListeners(null);
                }
            }
        };
        AndroidUtilities.runOnUIThread(pendingSharedRefresh, 15000);
    }

    private static boolean hasGalleryPermission(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                    Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static void startSharedLoad(Context context) {
        if (pendingSharedRefresh != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingSharedRefresh);
            pendingSharedRefresh = null;
        }
        sharedDirty = false;
        final CancellationSignal signal = sharedSignal = new CancellationSignal();
        load(context, signal, new Listener() {
            @Override
            public void onIndexReady(PagedGalleryIndex index) {
                if (sharedSignal != signal) {
                    if (index != null) {
                        index.close();
                    }
                    return;
                }
                sharedSignal = null;
                if (sharedDirty && pendingSharedRefresh == null) {
                    scheduleSharedRefresh(context);
                }
                if (index != null) {
                    PagedGalleryIndex old = sharedIndex;
                    sharedIndex = index;
                    notifyWaitingListeners(index);
                    if (old != null) {
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pagedGalleryIndexDidLoad);
                        old.close();
                    }
                    processDeferredMediaChanges(context);
                } else {
                    notifyWaitingListeners(sharedIndex);
                    if (sharedIndex != null) {
                        processDeferredMediaChanges(context);
                    }
                }
            }

            @Override
            public void onPageReady() {
            }
        });
    }

    private static void notifyWaitingListeners(PagedGalleryIndex index) {
        ArrayList<Listener> listeners = new ArrayList<>(waitingListeners);
        waitingListeners.clear();
        for (Listener listener : listeners) {
            listener.onIndexReady(index);
        }
    }

    private static void processDeferredMediaChanges(Context context) {
        if (pendingMediaUris.isEmpty()) {
            return;
        }
        ArrayList<Uri> changes = new ArrayList<>(pendingMediaUris);
        pendingMediaUris.clear();
        for (Uri changedUri : changes) {
            queryChangedMedia(context, changedUri, 0);
        }
    }

    public interface Listener {
        void onIndexReady(PagedGalleryIndex index);
        void onPageReady();
    }

    public static final class Album {
        public final int bucketId;
        public final String name;
        public final boolean videoOnly;
        public MediaController.PhotoEntry coverPhoto;
        private final boolean allMedia;
        private final IntList positions;
        private final ArrayList<MediaController.PhotoEntry> recentEntries = new ArrayList<>();

        private Album(int bucketId, String name, boolean videoOnly, boolean allMedia, IntList positions) {
            this.bucketId = bucketId;
            this.name = name;
            this.videoOnly = videoOnly;
            this.allMedia = allMedia;
            this.positions = positions;
        }

        public int size() {
            return recentEntries.size() + positions.size;
        }

        private int globalPosition(int position) {
            return allMedia ? position : positions.values[position];
        }
    }

    private static final class IntList {
        private int[] values = new int[16];
        private int size;

        private void add(int value) {
            if (size == values.length) {
                int[] bigger = new int[values.length * 2];
                System.arraycopy(values, 0, bigger, 0, size);
                values = bigger;
            }
            values[size++] = value;
        }
    }

    private static final class LongList {
        private long[] values = new long[16];
        private int size;

        private void add(long value) {
            if (size == values.length) {
                long[] bigger = new long[values.length * 2];
                System.arraycopy(values, 0, bigger, 0, size);
                values = bigger;
            }
            values[size++] = value;
        }
    }

    private final Context context;
    private final int[] ids;
    private final long[] dates;
    private final String[] volumes;
    private final HashMap<String, SparseIntArray> basePositionsByVolume;
    private final ArrayList<Album> albums;
    private final ArrayList<Album> photoAlbums;
    private final Album allMedia;
    private final Album allPhotos;
    private final Album allVideos;
    private final LinkedHashMap<Integer, MediaController.PhotoEntry> cache = new LinkedHashMap<Integer, MediaController.PhotoEntry>(CACHE_SIZE, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, MediaController.PhotoEntry> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final HashSet<String> inFlight = new HashSet<>();
    private final HashMap<String, ArrayList<Listener>> pageListeners = new HashMap<>();
    private final HashSet<Integer> unavailable = new HashSet<>();
    private final HashMap<String, MediaController.PhotoEntry> recentByKey = new HashMap<>();
    private boolean closed;

    private PagedGalleryIndex(Context context, int[] ids, long[] dates, String[] volumes, HashMap<String, SparseIntArray> basePositionsByVolume, ArrayList<Album> albums,
                              ArrayList<Album> photoAlbums, Album allMedia, Album allPhotos, Album allVideos) {
        this.context = context.getApplicationContext();
        this.ids = ids;
        this.dates = dates;
        this.volumes = volumes;
        this.basePositionsByVolume = basePositionsByVolume;
        this.albums = albums;
        this.photoAlbums = photoAlbums;
        this.allMedia = allMedia;
        this.allPhotos = allPhotos;
        this.allVideos = allVideos;
    }

    public static void load(Context context, CancellationSignal signal, Listener listener) {
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            PagedGalleryIndex index = null;
            try {
                index = build(appContext, signal);
            } catch (Throwable e) {
                if (!signal.isCanceled()) {
                    FileLog.e(e);
                }
            }
            final PagedGalleryIndex result = index;
            AndroidUtilities.runOnUIThread(() -> listener.onIndexReady(result));
        }, "gallery-index");
        thread.start();
    }

    private static PagedGalleryIndex build(Context context, CancellationSignal signal) {
        IntList idList = new IntList();
        LongList dateList = new LongList();
        ArrayList<String> volumeList = new ArrayList<>();
        HashMap<String, SparseIntArray> basePositionsByVolume = new HashMap<>();
        IntList photoPositions = new IntList();
        IntList videoPositions = new IntList();
        SparseArray<Album> bucketAlbums = new SparseArray<>();
        SparseArray<Album> photoBucketAlbums = new SparseArray<>();
        ArrayList<Album> albums = new ArrayList<>();
        ArrayList<Album> photoAlbums = new ArrayList<>();
        IntList allPositions = new IntList();
        Album allMedia = new Album(0, LocaleController.getString(R.string.AllMedia), false, true, allPositions);
        Album allPhotos = new Album(0, LocaleController.getString(R.string.AllPhotos), false, false, photoPositions);
        Album allVideos = new Album(0, LocaleController.getString(R.string.AllVideos), true, false, videoPositions);
        ContentResolver resolver = context.getContentResolver();
        ArrayList<String> projection = new ArrayList<>(Arrays.asList(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Video.VideoColumns.DURATION,
                MediaStore.Images.ImageColumns.ORIENTATION
        ));
        if (Build.VERSION.SDK_INT >= 29) {
            projection.add(MediaStore.MediaColumns.VOLUME_NAME);
        }
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (?, ?) AND " +
                MediaStore.Files.FileColumns.DATA + " IS NOT NULL AND " + MediaStore.Files.FileColumns.DATA + " != ''";
        if (Build.VERSION.SDK_INT >= 29) {
            selection += " AND " + MediaStore.MediaColumns.IS_PENDING + " = 0";
        }
        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Files.getContentUri("external"), projection.toArray(new String[0]), selection,
                    new String[]{String.valueOf(IMAGE), String.valueOf(VIDEO)},
                    "CASE WHEN " + MediaStore.Images.ImageColumns.DATE_TAKEN + " > 0 THEN " + MediaStore.Images.ImageColumns.DATE_TAKEN + " / 1000 ELSE " + MediaStore.Files.FileColumns.DATE_ADDED + " END DESC, " + MediaStore.Files.FileColumns._ID + " DESC",
                    signal);
            if (cursor == null) {
                return null;
            }
            while (!signal.isCanceled() && cursor.moveToNext()) {
                int id = cursor.getInt(0);
                int type = cursor.getInt(1);
                int bucketId = cursor.isNull(2) ? 0 : cursor.getInt(2);
                String bucketName = cursor.getString(3);
                long dateAdded = cursor.getLong(4);
                long dateTaken = cursor.getLong(5);
                long dateModified = cursor.getLong(6);
                String path = cursor.getString(7);
                String mimeType = cursor.getString(8);
                if (TextUtils.isEmpty(path) || TextUtils.isEmpty(mimeType)) {
                    continue;
                }
                long date = dateTaken > 0 ? dateTaken / 1000 : dateAdded > 0 ? dateAdded : dateModified;
                int width = cursor.getInt(9);
                int height = cursor.getInt(10);
                long size = cursor.getLong(11);
                int duration = (int) (cursor.getLong(12) / 1000);
                int orientation = cursor.getInt(13);
                String volume = Build.VERSION.SDK_INT >= 29 ? cursor.getString(14) : "external";
                if (TextUtils.isEmpty(volume)) {
                    volume = "external";
                }
                int position = idList.size;
                idList.add(id);
                dateList.add(date);
                volumeList.add(volume);
                basePositionsByVolume.computeIfAbsent(volume, ignored -> new SparseIntArray()).put(id, position);
                allPositions.add(position);
                if (type == IMAGE) {
                    photoPositions.add(position);
                } else {
                    videoPositions.add(position);
                }
                if (allMedia.coverPhoto == null || type == IMAGE && allPhotos.coverPhoto == null || type == VIDEO && allVideos.coverPhoto == null) {
                    MediaController.PhotoEntry cover = createPhotoEntry(bucketId, id, date, path, type == VIDEO, orientation, duration, width, height, size, bucketName, volume);
                    if (allMedia.coverPhoto == null) {
                        allMedia.coverPhoto = cover;
                    }
                    if (type == IMAGE && allPhotos.coverPhoto == null) {
                        allPhotos.coverPhoto = cover;
                    }
                    if (type == VIDEO && allVideos.coverPhoto == null) {
                        allVideos.coverPhoto = cover;
                    }
                }
                Album bucket = bucketAlbums.get(bucketId);
                if (bucket == null) {
                    bucket = new Album(bucketId, TextUtils.isEmpty(bucketName) ? "" : bucketName, false, false, new IntList());
                    bucket.coverPhoto = createPhotoEntry(bucketId, id, date, path, type == VIDEO, orientation, duration, width, height, size, bucketName, volume);
                    bucketAlbums.put(bucketId, bucket);
                    albums.add(bucket);
                }
                bucket.positions.add(position);
                if (type == IMAGE) {
                    Album photoBucket = photoBucketAlbums.get(bucketId);
                    if (photoBucket == null) {
                        photoBucket = new Album(bucketId, bucket.name, false, false, new IntList());
                        photoBucket.coverPhoto = createPhotoEntry(bucketId, id, date, path, false, orientation, 0, width, height, size, bucketName, volume);
                        photoBucketAlbums.put(bucketId, photoBucket);
                        photoAlbums.add(photoBucket);
                    }
                    photoBucket.positions.add(position);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (signal.isCanceled()) {
            return null;
        }
        int size = idList.size;
        int[] ids = new int[size];
        long[] dates = new long[size];
        String[] volumes = new String[size];
        for (int i = 0; i < size; i++) {
            ids[i] = idList.values[i];
            dates[i] = dateList.values[i];
            volumes[i] = volumeList.get(i);
        }
        albums.add(0, allMedia);
        if (photoPositions.size > 0) {
            albums.add(1, allPhotos);
        }
        if (videoPositions.size > 0) {
            albums.add(photoPositions.size > 0 ? 2 : 1, allVideos);
        }
        photoAlbums.add(0, allPhotos);
        return new PagedGalleryIndex(context, ids, dates, volumes, basePositionsByVolume, albums, photoAlbums, allMedia, allPhotos, allVideos);
    }

    private static MediaController.PhotoEntry createPhotoEntry(int bucketId, int id, long date, String path, boolean video, int orientation, int duration, int width, int height, long size, String bucketName, String volume) {
        MediaController.PhotoEntry entry = new MediaController.PhotoEntry(bucketId, id, date, path, orientation, duration, video, width, height, size);
        entry.mediaStoreUri = buildMediaUri(volume, id, video);
        entry.bucketName = bucketName;
        return entry;
    }

    public ArrayList<Album> getAlbums() {
        return albums;
    }

    public ArrayList<Album> getPhotoAlbums() {
        return photoAlbums;
    }

    public Album getAllMedia() {
        return allMedia;
    }

    public Album getAllPhotos() {
        return allPhotos;
    }

    public Album getAllVideos() {
        return allVideos;
    }

    public Album findAlbum(int bucketId, boolean videoOnly) {
        for (Album album : albums) {
            if (album.bucketId == bucketId && album.videoOnly == videoOnly) {
                return album;
            }
        }
        return allMedia;
    }

    public Album findPhotoAlbum(int bucketId) {
        if (bucketId != 0) {
            for (Album album : photoAlbums) {
                if (album.bucketId == bucketId) {
                    return album;
                }
            }
        }
        return allPhotos;
    }

    public long getDate(Album album, int position) {
        if (album == null || position < 0 || position >= album.size()) {
            return 0;
        }
        int recentCount = album.recentEntries.size();
        return position < recentCount ? album.recentEntries.get(position).dateTaken : dates[album.globalPosition(position - recentCount)];
    }

    public int getId(Album album, int position) {
        if (album == null || position < 0 || position >= album.size()) {
            return 0;
        }
        int recentCount = album.recentEntries.size();
        return position < recentCount ? album.recentEntries.get(position).imageId : ids[album.globalPosition(position - recentCount)];
    }

    public MediaController.PhotoEntry getCachedPhoto(Album album, int position) {
        if (album == null || position < 0 || position >= album.size()) {
            return null;
        }
        int recentCount = album.recentEntries.size();
        return position < recentCount ? album.recentEntries.get(position) : cache.get(album.globalPosition(position - recentCount));
    }

    public MediaController.PhotoEntry getPhoto(Album album, int position, Listener listener) {
        MediaController.PhotoEntry entry = getCachedPhoto(album, position);
        if (entry == null && album != null && position >= album.recentEntries.size() && position < album.size() && !unavailable.contains(album.globalPosition(position - album.recentEntries.size()))) {
            requestPage(album, position, listener);
        }
        return entry;
    }

    private void requestPage(Album album, int position, Listener listener) {
        if (closed || position < 0 || position >= album.size()) {
            return;
        }
        int basePosition = position - album.recentEntries.size();
        if (basePosition < 0) {
            return;
        }
        int start = basePosition / PAGE_SIZE * PAGE_SIZE;
        String key = System.identityHashCode(album) + ":" + start;
        if (inFlight.contains(key)) {
            addPageListener(key, listener);
            return;
        }
        if (inFlight.size() >= MAX_REQUESTS) {
            return;
        }
        inFlight.add(key);
        addPageListener(key, listener);
        int end = Math.min(album.size() - album.recentEntries.size(), start + PAGE_SIZE);
        int[] positions = new int[end - start];
        StringBuilder selection = new StringBuilder();
        ArrayList<String> argList = new ArrayList<>(positions.length * (Build.VERSION.SDK_INT >= 29 ? 2 : 1));
        for (int i = 0; i < positions.length; i++) {
            int global = album.globalPosition(start + i);
            positions[i] = global;
            if (i > 0) {
                selection.append(" OR ");
            }
            if (Build.VERSION.SDK_INT >= 29) {
                selection.append('(').append(MediaStore.Files.FileColumns._ID).append(" = ? AND ").append(MediaStore.MediaColumns.VOLUME_NAME).append(" = ?)");
                argList.add(String.valueOf(ids[global]));
                argList.add(volumes[global]);
            } else {
                selection.append(MediaStore.Files.FileColumns._ID).append(" = ?");
                argList.add(String.valueOf(ids[global]));
            }
        }
        String[] args = argList.toArray(new String[0]);
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            HashMap<String, MediaController.PhotoEntry> loaded = new HashMap<>();
            Cursor cursor = null;
            try {
                String[] columns = {
                        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Files.FileColumns.BUCKET_ID, MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Images.ImageColumns.DATE_TAKEN,
                        MediaStore.Files.FileColumns.ORIENTATION, MediaStore.Files.FileColumns.DURATION,
                        MediaStore.Files.FileColumns.WIDTH, MediaStore.Files.FileColumns.HEIGHT,
                        MediaStore.Files.FileColumns.SIZE,
                        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
                };
                String[] pageColumns = columns;
                if (Build.VERSION.SDK_INT >= 29) {
                    pageColumns = Arrays.copyOf(columns, columns.length + 1);
                    pageColumns[columns.length] = MediaStore.MediaColumns.VOLUME_NAME;
                }
                cursor = context.getContentResolver().query(MediaStore.Files.getContentUri("external"), pageColumns,
                        selection.toString(), args, null);
                while (cursor != null && cursor.moveToNext()) {
                    String path = cursor.getString(3);
                    if (TextUtils.isEmpty(path)) {
                        continue;
                    }
                    int id = cursor.getInt(0);
                    boolean isVideo = cursor.getInt(1) == VIDEO;
                    long dateAdded = cursor.getLong(4);
                    long dateTaken = cursor.getLong(5);
                    long date = dateTaken > 0 ? dateTaken / 1000 : dateAdded;
                    MediaController.PhotoEntry entry = new MediaController.PhotoEntry(cursor.getInt(2), id,
                            date, path, isVideo ? 0 : cursor.getInt(6),
                            isVideo ? (int) (cursor.getLong(7) / 1000) : 0,
                            isVideo, cursor.getInt(8), cursor.getInt(9), cursor.getLong(10));
                    String volume = Build.VERSION.SDK_INT >= 29 ? cursor.getString(12) : "external";
                    if (TextUtils.isEmpty(volume)) {
                        volume = "external";
                    }
                    entry.mediaStoreUri = buildMediaUri(volume, id, isVideo);
                    entry.bucketName = cursor.getString(11);
                    loaded.put(mediaKey(volume, id), entry);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                inFlight.remove(key);
                ArrayList<Listener> listeners = pageListeners.remove(key);
                if (closed) {
                    return;
                }
                for (int global : positions) {
                    MediaController.PhotoEntry entry = loaded.get(mediaKey(volumes[global], ids[global]));
                    if (entry != null) {
                        cache.put(global, entry);
                    } else {
                        unavailable.add(global);
                    }
                }
                if (listeners != null) {
                    for (Listener pageListener : listeners) {
                        pageListener.onPageReady();
                    }
                }
            });
        }, "gallery-page");
        thread.start();
    }

    private void addPageListener(String key, Listener listener) {
        if (listener == null) {
            return;
        }
        ArrayList<Listener> listeners = pageListeners.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removePageListener(Listener listener) {
        for (ArrayList<Listener> listeners : pageListeners.values()) {
            listeners.remove(listener);
        }
    }

    public void close() {
        closed = true;
        cache.clear();
        inFlight.clear();
        pageListeners.clear();
        unavailable.clear();
        recentByKey.clear();
        basePositionsByVolume.clear();
    }
}
