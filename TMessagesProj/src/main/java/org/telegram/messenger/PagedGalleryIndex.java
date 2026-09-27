package org.telegram.messenger;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.SparseArray;

import java.util.ArrayList;
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

    public static void warmUp(Context context) {
        if (hasGalleryPermission(context)) {
            requestShared(context, null);
        }
    }

    public static void requestShared(Context context, Listener listener) {
        if (!hasGalleryPermission(context)) {
            if (listener != null) {
                listener.onIndexReady(null);
            }
            return;
        }
        if (sharedIndex != null && !sharedDirty && sharedSignal == null) {
            if (listener != null) {
                listener.onIndexReady(sharedIndex);
            }
            return;
        }
        if (listener != null) {
            waitingListeners.add(listener);
        }
        if (sharedSignal != null) {
            return;
        }
        startSharedLoad(context.getApplicationContext());
    }

    public static void onGalleryChanged(Context context) {
        if (sharedIndex == null && sharedSignal == null) {
            return;
        }
        sharedDirty = true;
        scheduleSharedRefresh(context.getApplicationContext());
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
                if (index != null) {
                    PagedGalleryIndex old = sharedIndex;
                    sharedIndex = index;
                    if (old != null) {
                        old.close();
                    }
                }
                if (sharedDirty) {
                    if (pendingSharedRefresh == null) {
                        scheduleSharedRefresh(context);
                    }
                } else {
                    notifyWaitingListeners(index != null ? index : sharedIndex);
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

        private Album(int bucketId, String name, boolean videoOnly, boolean allMedia, IntList positions) {
            this.bucketId = bucketId;
            this.name = name;
            this.videoOnly = videoOnly;
            this.allMedia = allMedia;
            this.positions = positions;
        }

        public int size() {
            return positions.size;
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
    private final HashSet<Integer> unavailable = new HashSet<>();
    private boolean closed;

    private PagedGalleryIndex(Context context, int[] ids, long[] dates, ArrayList<Album> albums,
                              ArrayList<Album> photoAlbums, Album allMedia, Album allPhotos, Album allVideos) {
        this.context = context.getApplicationContext();
        this.ids = ids;
        this.dates = dates;
        this.albums = albums;
        this.photoAlbums = photoAlbums;
        this.allMedia = allMedia;
        this.allPhotos = allPhotos;
        this.allVideos = allVideos;
    }

    public static void load(Context context, CancellationSignal signal, Listener listener) {
        final Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> {
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
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static PagedGalleryIndex build(Context context, CancellationSignal signal) {
        IntList idList = new IntList();
        LongList dateList = new LongList();
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
        String[] columns = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.BUCKET_ID,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATA
        };
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (?, ?) AND " +
                MediaStore.Files.FileColumns.DATA + " IS NOT NULL AND " + MediaStore.Files.FileColumns.DATA + " != ''";
        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Files.getContentUri("external"), columns, selection,
                    new String[]{String.valueOf(IMAGE), String.valueOf(VIDEO)},
                    MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC, " + MediaStore.Files.FileColumns._ID + " DESC",
                    signal);
            if (cursor == null) {
                return null;
            }
            while (!signal.isCanceled() && cursor.moveToNext()) {
                int id = cursor.getInt(0);
                int type = cursor.getInt(1);
                int bucketId = cursor.getInt(2);
                String bucketName = cursor.getString(3);
                long date = cursor.getLong(4);
                int position = idList.size;
                idList.add(id);
                dateList.add(date);
                allPositions.add(position);
                if (type == IMAGE) {
                    photoPositions.add(position);
                } else {
                    videoPositions.add(position);
                }
                if (allMedia.coverPhoto == null || type == IMAGE && allPhotos.coverPhoto == null || type == VIDEO && allVideos.coverPhoto == null) {
                    MediaController.PhotoEntry cover = new MediaController.PhotoEntry(bucketId, id, date,
                            cursor.getString(5), 0, 0, type == VIDEO, 0, 0, 0);
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
                    bucket.coverPhoto = new MediaController.PhotoEntry(bucketId, id, date,
                            cursor.getString(5), 0, 0, type == VIDEO, 0, 0, 0);
                    bucketAlbums.put(bucketId, bucket);
                    albums.add(bucket);
                }
                bucket.positions.add(position);
                if (type == IMAGE) {
                    Album photoBucket = photoBucketAlbums.get(bucketId);
                    if (photoBucket == null) {
                        photoBucket = new Album(bucketId, bucket.name, false, false, new IntList());
                        photoBucket.coverPhoto = new MediaController.PhotoEntry(bucketId, id, date,
                                cursor.getString(5), 0, 0, false, 0, 0, 0);
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
        for (int i = 0; i < size; i++) {
            ids[i] = idList.values[i];
            dates[i] = dateList.values[i];
        }
        albums.add(0, allMedia);
        if (photoPositions.size > 0) {
            albums.add(1, allPhotos);
        }
        if (videoPositions.size > 0) {
            albums.add(photoPositions.size > 0 ? 2 : 1, allVideos);
        }
        photoAlbums.add(0, allPhotos);
        return new PagedGalleryIndex(context, ids, dates, albums, photoAlbums, allMedia, allPhotos, allVideos);
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
        return dates[album.globalPosition(position)];
    }

    public int getId(Album album, int position) {
        if (album == null || position < 0 || position >= album.size()) {
            return 0;
        }
        return ids[album.globalPosition(position)];
    }

    public MediaController.PhotoEntry getCachedPhoto(Album album, int position) {
        if (album == null || position < 0 || position >= album.size()) {
            return null;
        }
        return cache.get(album.globalPosition(position));
    }

    public MediaController.PhotoEntry getPhoto(Album album, int position, Listener listener) {
        MediaController.PhotoEntry entry = getCachedPhoto(album, position);
        if (entry == null && album != null && position >= 0 && position < album.size() && !unavailable.contains(album.globalPosition(position))) {
            requestPage(album, position, listener);
        }
        return entry;
    }

    private void requestPage(Album album, int position, Listener listener) {
        if (closed || position < 0 || position >= album.size() || inFlight.size() >= MAX_REQUESTS) {
            return;
        }
        int start = position / PAGE_SIZE * PAGE_SIZE;
        String key = System.identityHashCode(album) + ":" + start;
        if (!inFlight.add(key)) {
            return;
        }
        int end = Math.min(album.size(), start + PAGE_SIZE);
        int[] positions = new int[end - start];
        StringBuilder selection = new StringBuilder(MediaStore.Files.FileColumns._ID).append(" IN (");
        String[] args = new String[positions.length];
        for (int i = 0; i < positions.length; i++) {
            int global = album.globalPosition(start + i);
            positions[i] = global;
            args[i] = String.valueOf(ids[global]);
            if (i > 0) {
                selection.append(',');
            }
            selection.append('?');
        }
        selection.append(')');
        Thread thread = new Thread(() -> {
            SparseArray<MediaController.PhotoEntry> loaded = new SparseArray<>();
            Cursor cursor = null;
            try {
                String[] columns = {
                        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Files.FileColumns.BUCKET_ID, MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.ORIENTATION,
                        MediaStore.Files.FileColumns.DURATION, MediaStore.Files.FileColumns.WIDTH,
                        MediaStore.Files.FileColumns.HEIGHT, MediaStore.Files.FileColumns.SIZE
                };
                cursor = context.getContentResolver().query(MediaStore.Files.getContentUri("external"), columns,
                        selection.toString(), args, null);
                while (cursor != null && cursor.moveToNext()) {
                    String path = cursor.getString(3);
                    if (TextUtils.isEmpty(path)) {
                        continue;
                    }
                    int id = cursor.getInt(0);
                    boolean isVideo = cursor.getInt(1) == VIDEO;
                    MediaController.PhotoEntry entry = new MediaController.PhotoEntry(cursor.getInt(2), id,
                            cursor.getLong(4), path, isVideo ? 0 : cursor.getInt(5),
                            isVideo ? (int) (cursor.getLong(6) / 1000) : 0,
                            isVideo, cursor.getInt(7), cursor.getInt(8), cursor.getLong(9));
                    loaded.put(id, entry);
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
                if (closed) {
                    return;
                }
                for (int global : positions) {
                    MediaController.PhotoEntry entry = loaded.get(ids[global]);
                    if (entry != null) {
                        cache.put(global, entry);
                    } else {
                        unavailable.add(global);
                    }
                }
                listener.onPageReady();
            });
        }, "gallery-page");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public void close() {
        closed = true;
        cache.clear();
        inFlight.clear();
        unavailable.clear();
    }
}
