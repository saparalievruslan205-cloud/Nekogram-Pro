package org.telegram.ui.Components;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.lang.ref.WeakReference;

/** UI callbacks owned by a gallery worker must not keep its originating screen alive. */
public final class SaveToGalleryCommand implements MediaController.GallerySaveCallback, MediaController.GallerySaveProgressCallback {
    public interface ProgressTarget {
        void updateGallerySaveProgress(MessageObject message, long loaded, long total, boolean active);
    }

    private final WeakReference<BaseFragment> fragmentRef;

    public SaveToGalleryCommand(BaseFragment fragment) {
        fragmentRef = new WeakReference<>(fragment);
    }

    public static boolean requiresWritePermission(Activity activity) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canShowUi(BaseFragment fragment) {
        if (fragment == null || fragment.isFinished || fragment.isFinishing()) {
            return false;
        }
        Activity activity = fragment.getParentActivity();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    @Override
    public void onFinished(int saved, int failed, boolean cancelled) {
        BaseFragment fragment = fragmentRef.get();
        fragmentRef.clear();
        if (canShowUi(fragment)) {
            int resultId = cancelled ? R.string.GalleryBatchSaveCancelled : R.string.GalleryBatchSaveSummary;
            BulletinFactory.of(fragment).createSimpleBulletin(R.drawable.msg_gallery,
                    LocaleController.formatString(resultId, saved, failed), "").show();
        }
    }

    @Override
    public void onProgress(MessageObject message, long loaded, long total, boolean active) {
        BaseFragment fragment = fragmentRef.get();
        if (canShowUi(fragment) && fragment instanceof ProgressTarget) {
            ((ProgressTarget) fragment).updateGallerySaveProgress(message, loaded, total, active);
        }
    }
}
