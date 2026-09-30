package tw.nekomimi.nekogram.helpers;

import android.app.Application;
import android.content.SharedPreferences;

import org.telegram.ui.ActionBar.BaseFragment;

import java.util.HashMap;

public class AnalyticsHelper {
    private static SharedPreferences preferences;

    public static boolean analyticsDisabled = true;

    public static void start(Application application) {
        preferences = application.getSharedPreferences("nekoanalytics", Application.MODE_PRIVATE);
        analyticsDisabled = true;
        preferences.edit()
                .putBoolean("analyticsDisabled", true)
                .putBoolean("sendBugReport", false)
                .remove("userId")
                .apply();
    }

    public static void trackFragmentLifecycle(String lifecycle, BaseFragment fragment) {
        // Analytics are disabled in this build.
    }

    public static void trackEvent(String event, HashMap<String, String> map) {
        // Analytics are disabled in this build.
    }

    public static boolean isSettingsAvailable() {
        return false;
    }

    public static void setAnalyticsDisabled() {
        analyticsDisabled = true;
        if (preferences != null) {
            preferences.edit()
                    .putBoolean("analyticsDisabled", true)
                    .putBoolean("sendBugReport", false)
                    .remove("userId")
                    .apply();
        }
    }
}
