package tw.nekomimi.nekogram.helpers.remote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Local cache for data delivered by push notifications; it does not make network requests. */
public abstract class BaseRemoteHelper {
    protected static final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoremoteconfig", Activity.MODE_PRIVATE);
    public static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

    protected abstract String getRequestMethod();

    protected String getJSON() {
        String value = preferences.getString(getRequestMethod(), null);
        return TextUtils.isEmpty(value) ? null : value;
    }

    public void onLoadSuccess(String result) {
        String key = getRequestMethod();
        if (TextUtils.isEmpty(result)) {
            preferences.edit().remove(key + "_update_time").remove(key).apply();
        } else {
            preferences.edit()
                    .putLong(key + "_update_time", System.currentTimeMillis())
                    .putString(key, result)
                    .apply();
        }
    }

    protected static List<TLRPC.MessageEntity> parseBotAPIEntities(MessageEntity[] botEntities, boolean emojiOnly) {
        if (botEntities == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(botEntities)
                .filter(entity -> !emojiOnly || entity.customEmojiId != null)
                .map(entity -> {
                    TLRPC.MessageEntity result = switch (entity.type) {
                        case "mention" -> new TLRPC.TL_messageEntityMention();
                        case "hashtag" -> new TLRPC.TL_messageEntityHashtag();
                        case "cashtag" -> new TLRPC.TL_messageEntityCashtag();
                        case "bot_command" -> new TLRPC.TL_messageEntityBotCommand();
                        case "url" -> new TLRPC.TL_messageEntityUrl();
                        case "email" -> new TLRPC.TL_messageEntityEmail();
                        case "phone_number" -> new TLRPC.TL_messageEntityPhone();
                        case "bold" -> new TLRPC.TL_messageEntityBold();
                        case "italic" -> new TLRPC.TL_messageEntityItalic();
                        case "underline" -> new TLRPC.TL_messageEntityUnderline();
                        case "strikethrough" -> new TLRPC.TL_messageEntityStrike();
                        case "text_link" -> new TLRPC.TL_messageEntityTextUrl();
                        case "custom_emoji" -> {
                            TLRPC.TL_messageEntityCustomEmoji emoji = new TLRPC.TL_messageEntityCustomEmoji();
                            emoji.document_id = entity.customEmojiId;
                            yield emoji;
                        }
                        default -> new TLRPC.TL_messageEntityUnknown();
                    };
                    result.offset = entity.offset;
                    result.length = entity.length;
                    result.url = entity.url;
                    return result;
                })
                .toList();
    }

    public static class MessageEntity {
        @SerializedName("type")
        @Expose
        public String type;
        @SerializedName("offset")
        @Expose
        public Integer offset;
        @SerializedName("length")
        @Expose
        public Integer length;
        @SerializedName("url")
        @Expose
        public String url;
        @SerializedName("custom_emoji_id")
        @Expose
        public Long customEmojiId;
    }
}
