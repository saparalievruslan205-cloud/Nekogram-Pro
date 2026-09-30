package org.telegram.messenger;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

import org.telegram.messenger.regular.R;
import org.telegram.ui.ActionBar.AlertDialog;

/** Downloads a Pro release in the app and hands the APK to Android's installer. */
final class ProUpdateDownloader {
    private static final String PREFS = "pro_update_download";
    private static final String KEY_ID = "download_id";
    private static final String KEY_VERSION = "version_code";
    private static final long POLL_DELAY_MS = 500;

    private ProUpdateDownloader() {
    }

    static void show(Activity activity, ProUpdateManager.ProUpdate update) {
        if (activity.isFinishing()) {
            return;
        }
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            showError(activity);
            return;
        }
        long id = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ID, -1);
        int version = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, -1);
        if (id >= 0 && version != update.versionCode) {
            manager.remove(id);
            id = -1;
        }
        if (id < 0) {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.downloadUrl));
                request.setTitle(activity.getString(R.string.Nekogram));
                request.setMimeType("application/vnd.android.package-archive");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                        "Nekogram-Pro-" + update.versionCode + ".apk");
                id = manager.enqueue(request);
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_ID, id).putInt(KEY_VERSION, update.versionCode).apply();
            } catch (Exception e) {
                FileLog.e(e);
                showError(activity);
                return;
            }
        }

        final long downloadId = id;
        Handler handler = new Handler(Looper.getMainLooper());
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.Nekogram))
                .setMessage(activity.getString(R.string.ProUpdateDownloading))
                .setNegativeButton(activity.getString(R.string.ProUpdateCancel), (dialog, which) -> {
                    manager.remove(downloadId);
                    clear(activity, downloadId);
                })
                .create();
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || !progress.isShowing()) {
                    return;
                }
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        manager.remove(downloadId);
                        clear(activity, downloadId);
                        progress.dismiss();
                        showError(activity);
                        return;
                    }
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        progress.dismiss();
                        try {
                            File apk = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                                    "Nekogram-Pro-" + update.versionCode + ".apk");
                            PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                            long archiveVersion = info == null ? -1 : Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                    ? info.getLongVersionCode() : info.versionCode;
                            if (!apk.isFile() || apk.length() == 0 || info == null
                                    || !activity.getPackageName().equals(info.packageName)
                                    || archiveVersion != update.versionCode) {
                                manager.remove(downloadId);
                                clear(activity, downloadId);
                                showError(activity);
                                return;
                            }
                            Uri uri = manager.getUriForDownloadedFile(downloadId);
                            if (uri == null) {
                                showError(activity);
                                return;
                            }
                            Intent install = new Intent(Intent.ACTION_VIEW);
                            install.setDataAndType(uri, "application/vnd.android.package-archive");
                            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivity(install);
                        } catch (Exception e) {
                            FileLog.e(e);
                            showError(activity);
                        }
                        return;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        manager.remove(downloadId);
                        clear(activity, downloadId);
                        progress.dismiss();
                        showError(activity);
                        return;
                    }
                    long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    String amount = AndroidUtilities.formatFileSize(bytes);
                    progress.setMessage(total > 0
                            ? activity.getString(R.string.ProUpdateProgress, amount, AndroidUtilities.formatFileSize(total),
                                    (int) Math.min(100, bytes * 100 / total))
                            : activity.getString(R.string.ProUpdateProgressUnknown, amount));
                    handler.postDelayed(this, POLL_DELAY_MS);
                } catch (Exception e) {
                    FileLog.e(e);
                    progress.dismiss();
                    showError(activity);
                }
            }
        };
        progress.setOnDismissListener(dialog -> handler.removeCallbacks(poll));
        progress.show();
        handler.post(poll);
    }

    private static void clear(Context context, long id) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ID, -1) == id) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    private static void showError(Activity activity) {
        if (!activity.isFinishing()) {
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.Nekogram))
                    .setMessage(activity.getString(R.string.ProUpdateDownloadFailed))
                    .setPositiveButton(activity.getString(R.string.ProUpdateOK), null)
                    .show();
        }
    }
}
