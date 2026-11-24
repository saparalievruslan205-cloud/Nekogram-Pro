package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.messenger.regular.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;

import tw.nekomimi.nekogram.Extra;

public class ApplicationLoaderImpl extends ApplicationLoader {
    private final ProUpdateManager proUpdates = new ProUpdateManager();

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected boolean isStandalone() {
        return Extra.isDirectApp();
    }

    @Override
    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        try {
            (new UpdateAppAlertDialog(context, update, account)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    @Override
    public boolean isCustomUpdate() {
        return "tw.nekomimi.nekogram.beta".equals(BuildConfig.APPLICATION_ID);
    }

    @Override
    public void checkUpdate(boolean force, Runnable whenDone) {
        proUpdates.check(force, whenDone);
    }

    @Override
    public BetaUpdate getUpdate() {
        return proUpdates.getUpdate();
    }

    static String getAppName(Context context) {
        try {
            return context.getString(R.string.Nekogram);
        } catch (Exception e) {
            return LocaleController.getString(R.string.Nekogram);
        }
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (!(update instanceof ProUpdateManager.ProUpdate) || !(context instanceof Activity)) {
            return false;
        }
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        ProUpdateManager.ProUpdate proUpdate = (ProUpdateManager.ProUpdate) update;
        String message = LocaleController.formatString(R.string.ProUpdateAvailable, update.version);
        if (update.changelog != null && !update.changelog.isEmpty()) {
            message += "\n\n" + update.changelog;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(getAppName(activity))
                    .setMessage(message)
                    .setPositiveButton(LocaleController.getString(R.string.ProUpdateDownload),
                            (dialog, which) -> ProUpdateDownloader.show(activity, proUpdate))
                    .setNegativeButton(LocaleController.getString(R.string.ProUpdateLater), null)
                    .show();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        return true;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return new UpdateLayout(activity, sideMenuContainer);
    }
}
