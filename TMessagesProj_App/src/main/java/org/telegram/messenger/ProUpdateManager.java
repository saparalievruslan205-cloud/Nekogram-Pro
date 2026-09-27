package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Update feed for the separately installed Nekogram Pro companion. */
final class ProUpdateManager {
    private static final String RELEASE_API =
            "https://api.github.com/repos/saparalievruslan205-cloud/Nekogram-Pro/releases/latest";
    private static final String DOWNLOAD_PREFIX =
            "https://github.com/saparalievruslan205-cloud/Nekogram-Pro/releases/download/";
    private static final Pattern TAG = Pattern.compile("pro-([0-9]+)-([0-9]+(?:\\.[0-9]+){1,3})");
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final ArrayList<Runnable> callbacks = new ArrayList<>();
    private boolean initialized;
    private boolean checking;
    private long lastCheck;
    private volatile ProUpdate update;

    @Nullable
    synchronized BetaUpdate getUpdate() {
        initialize();
        return update;
    }

    void check(boolean force, @Nullable Runnable whenDone) {
        synchronized (this) {
            initialize();
            long now = System.currentTimeMillis();
            if (!force && now >= lastCheck && now - lastCheck < CHECK_INTERVAL_MS) {
                if (whenDone != null) {
                    AndroidUtilities.runOnUIThread(whenDone);
                }
                return;
            }
            if (whenDone != null) {
                callbacks.add(whenDone);
            }
            if (checking) {
                return;
            }
            checking = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            ProUpdate found = null;
            boolean successful = false;
            try {
                found = fetchLatest();
                successful = true;
            } catch (Exception e) {
                FileLog.e(e);
            }
            final ProUpdate result = found;
            final boolean checked = successful;
            AndroidUtilities.runOnUIThread(() -> {
                ArrayList<Runnable> done;
                synchronized (ProUpdateManager.this) {
                    if (checked) {
                        update = result;
                        lastCheck = System.currentTimeMillis();
                        SharedPreferences.Editor editor = preferences().edit().putLong("last_check", lastCheck);
                        if (result == null) {
                            editor.remove("version").remove("version_code")
                                    .remove("changelog").remove("download_url");
                        } else {
                            editor.putString("version", result.version)
                                    .putInt("version_code", result.versionCode)
                                    .putString("changelog", result.changelog)
                                    .putString("download_url", result.downloadUrl);
                        }
                        editor.apply();
                    }
                    checking = false;
                    done = new ArrayList<>(callbacks);
                    callbacks.clear();
                }
                for (Runnable callback : done) {
                    callback.run();
                }
            });
        });
    }

    private void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        SharedPreferences saved = preferences();
        lastCheck = saved.getLong("last_check", 0);
        String url = saved.getString("download_url", null);
        int versionCode = saved.getInt("version_code", 0);
        if (url != null && url.startsWith(DOWNLOAD_PREFIX) && url.endsWith(".apk")
                && versionCode > org.telegram.messenger.regular.BuildConfig.VERSION_CODE) {
            update = new ProUpdate(saved.getString("version", ""), versionCode,
                    saved.getString("changelog", ""), url);
        }
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("pro_updates", Context.MODE_PRIVATE);
    }

    @Nullable
    private static ProUpdate fetchLatest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Nekogram-Pro");
        try {
            int status = connection.getResponseCode();
            if (status == 404) {
                return null;
            }
            if (status != 200) {
                throw new IllegalStateException("GitHub releases HTTP " + status);
            }
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (response.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("GitHub release response too large");
                    }
                    response.write(buffer, 0, count);
                }
            }
            JSONObject release = new JSONObject(response.toString(StandardCharsets.UTF_8.name()));
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                return null;
            }
            String tag = release.optString("tag_name");
            Matcher matcher = TAG.matcher(tag);
            if (!matcher.matches()) {
                return null;
            }
            int versionCode = Integer.parseInt(matcher.group(1));
            if (versionCode <= org.telegram.messenger.regular.BuildConfig.VERSION_CODE) {
                return null;
            }
            String version = matcher.group(2);
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) {
                return null;
            }
            String downloadUrl = selectApk(assets, tag);
            if (downloadUrl == null) {
                return null;
            }
            String changelog = release.optString("body", "");
            if (changelog.length() > 1000) {
                changelog = changelog.substring(0, 1000);
            }
            return new ProUpdate(version, versionCode, changelog, downloadUrl);
        } finally {
            connection.disconnect();
        }
    }

    @Nullable
    private static String selectApk(JSONArray assets, String tag) {
        String[] abis = Build.SUPPORTED_ABIS;
        for (int index = 0; index <= abis.length; index++) {
            String suffix = "-" + (index < abis.length ? abis[index] : "universal") + ".apk";
            for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                JSONObject asset = assets.optJSONObject(assetIndex);
                if (asset == null || asset.optLong("size") <= 0) {
                    continue;
                }
                String name = asset.optString("name");
                String url = asset.optString("browser_download_url");
                if (name.startsWith("Nekogram-Pro-") && name.endsWith(suffix)
                        && url.equals(DOWNLOAD_PREFIX + tag + "/" + name)) {
                    return url;
                }
            }
        }
        return null;
    }

    static final class ProUpdate extends BetaUpdate {
        final String downloadUrl;

        ProUpdate(String version, int versionCode, String changelog, String downloadUrl) {
            super(version, versionCode, changelog);
            this.downloadUrl = downloadUrl;
        }
    }
}
