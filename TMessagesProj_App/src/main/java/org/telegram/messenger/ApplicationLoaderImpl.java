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

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (!(update instanceof ProUpdateManager.ProUpdate) || !(context instanceof Activity)) {
            return false;
        }
        ProUpdateManager.ProUpdate proUpdate = (ProUpdateManager.ProUpdate) update;
        String message = context.getString(R.string.ProUpdateAvailable, update.version);
        if (update.changelog != null && !update.changelog.isEmpty()) {
            message += "\n\n" + update.changelog;
        }
        new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.Nekogram))
                .setMessage(message)
                .setPositiveButton(context.getString(R.string.ProUpdateDownload),
                        (dialog, which) -> ProUpdateDownloader.show((Activity) context, proUpdate))
                .setNegativeButton(context.getString(R.string.ProUpdateLater), null)
                .show();
        return true;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return new UpdateLayout(activity, sideMenuContainer);
    }
}
