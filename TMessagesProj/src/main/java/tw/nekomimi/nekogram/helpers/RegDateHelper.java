package tw.nekomimi.nekogram.helpers;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.util.Calendar;
import java.util.HashMap;
import java.util.function.BiConsumer;

public class RegDateHelper {
    private static final HashMap<Long, Integer> regDates = new HashMap<>();

    public static String formatRegDate(int regDate, String error) {
        if (error != null) return error;
        return LocaleController.formatString(R.string.RegistrationDateApproximately, LocaleController.getInstance().getFormatterMonthYear().format(regDate * 1000L));
    }

    public static Integer getRegDate(long userId) {
        return regDates.get(userId);
    }

    public static void getRegDate(long userId, BiConsumer<Integer, String> callback) {
        Integer date = regDates.get(userId);
        if (callback != null) {
            callback.accept(date, date == null ? LocaleController.getString(R.string.UnknownError) : null);
        }
    }

    public static void setRegDate(long dialogId, TLRPC.PeerSettings settings) {
        if (settings == null || settings.registration_month == null) {
            return;
        }
        String[] parts = settings.registration_month.split("\\.");
        if (parts.length != 2) {
            return;
        }
        try {
            int month = Integer.parseInt(parts[0]);
            int year = Integer.parseInt(parts[1]);
            if (month < 1 || month > 12) {
                return;
            }
            Calendar calendar = Calendar.getInstance();
            calendar.set(year, month - 1, 2, 0, 0, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            regDates.put(dialogId, (int) (calendar.getTimeInMillis() / 1000L));
        } catch (NumberFormatException e) {
            FileLog.e(e);
        }
    }
}
