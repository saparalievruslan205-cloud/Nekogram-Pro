package tw.nekomimi.nekogram;

import org.telegram.messenger.BuildConfig;

public class Extra {

    public static int APP_ID = BuildConfig.API_ID;
    public static String APP_HASH = BuildConfig.API_HASH;

    public static boolean isDirectApp() {
        return "release".equals(BuildConfig.BUILD_TYPE) || "debug".equals(BuildConfig.BUILD_TYPE);
    }
}
