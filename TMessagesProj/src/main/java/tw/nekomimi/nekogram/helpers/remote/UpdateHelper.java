package tw.nekomimi.nekogram.helpers.remote;

import android.content.pm.PackageInfo;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.Calendar;
import java.util.Date;

public final class UpdateHelper {

    private static final UpdateHelper INSTANCE = new UpdateHelper();

    private UpdateHelper() {
    }

    public static UpdateHelper getInstance() {
        return INSTANCE;
    }

    /** Legacy update entry point retained for callers in Telegram core; Pro updates use GitHub. */
    public void checkNewVersionAvailable(Delegate delegate) {
        if (delegate != null) {
            AndroidUtilities.runOnUIThread(() -> delegate.onTLResponse(null, null));
        }
    }

    public interface Delegate {
        void onTLResponse(TLRPC.TL_help_appUpdate response, String error);
    }

    public static String formatDateUpdate(long date) {
        long epoch;
        try {
            PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            epoch = packageInfo.lastUpdateTime;
        } catch (Exception e) {
            epoch = 0;
        }
        if (date <= epoch) {
            return LocaleController.formatString(R.string.LastUpdateNever);
        }
        try {
            Calendar rightNow = Calendar.getInstance();
            int day = rightNow.get(Calendar.DAY_OF_YEAR);
            int year = rightNow.get(Calendar.YEAR);
            rightNow.setTimeInMillis(date);
            int dateDay = rightNow.get(Calendar.DAY_OF_YEAR);
            int dateYear = rightNow.get(Calendar.YEAR);

            if (dateDay == day && year == dateYear) {
                if (Math.abs(System.currentTimeMillis() - date) < 60000L) {
                    return LocaleController.formatString(R.string.LastUpdateRecently);
                }
                return LocaleController.formatString(R.string.LastUpdateFormatted, LocaleController.formatString(R.string.TodayAtFormatted,
                        LocaleController.getInstance().getFormatterDay().format(new Date(date))));
            } else if (dateDay + 1 == day && year == dateYear) {
                return LocaleController.formatString(R.string.LastUpdateFormatted, LocaleController.formatString(R.string.YesterdayAtFormatted,
                        LocaleController.getInstance().getFormatterDay().format(new Date(date))));
            } else if (Math.abs(System.currentTimeMillis() - date) < 31536000000L) {
                String format = LocaleController.formatString(R.string.formatDateAtTime,
                        LocaleController.getInstance().getFormatterDayMonth().format(new Date(date)),
                        LocaleController.getInstance().getFormatterDay().format(new Date(date)));
                return LocaleController.formatString(R.string.LastUpdateDateFormatted, format);
            } else {
                String format = LocaleController.formatString(R.string.formatDateAtTime,
                        LocaleController.getInstance().getFormatterYear().format(new Date(date)),
                        LocaleController.getInstance().getFormatterDay().format(new Date(date)));
                return LocaleController.formatString(R.string.LastUpdateDateFormatted, format);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "LOC_ERR";
    }
}
