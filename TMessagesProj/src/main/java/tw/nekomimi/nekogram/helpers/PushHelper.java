package tw.nekomimi.nekogram.helpers;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import org.telegram.messenger.FileLog;

import tw.nekomimi.nekogram.helpers.remote.ConfigHelper;

public class PushHelper {

    private static final Gson GSON = new Gson();

    public static void processRemoteMessage(String data) {
        try {
            var message = GSON.fromJson(data, RemoteMessage.class);
            if (message != null && "set_remote_config".equals(message.action) && message.data != null) {
                ConfigHelper.getInstance().onLoadSuccess(message.data);
            }
        } catch (Exception e) {
            FileLog.e("failed to do remote action", e);
        }
    }

    public static class RemoteMessage {

        @SerializedName("action")
        @Expose
        public String action;

        @SerializedName("data")
        @Expose
        public String data;

    }
}
