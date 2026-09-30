package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.os.Bundle;

import org.telegram.messenger.BaseController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.TopicsFragment;

import java.util.Locale;
import java.util.function.Consumer;

public class UserHelper extends BaseController {

    private static final UserHelper[] Instance = new UserHelper[UserConfig.MAX_ACCOUNT_COUNT];

    public UserHelper(int num) {
        super(num);
    }

    public static UserHelper getInstance(int num) {
        UserHelper localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (UserHelper.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new UserHelper(num);
                }
            }
        }
        return localInstance;
    }

    public void openByDialogId(long dialogId, Activity activity, Consumer<BaseFragment> callback, Browser.Progress progress) {
        if (dialogId == 0 || activity == null) {
            return;
        }
        AlertDialog progressDialog = progress != null ? null : new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        searchPeer(dialogId, peer -> {
            if (progress != null) {
                progress.end();
            }
            if (progressDialog != null) {
                try {
                    progressDialog.dismiss();
                } catch (Exception ignored) {
                }
            }
            if (peer == null || callback == null) {
                return;
            }
            Bundle args = new Bundle();
            if (peer instanceof TLRPC.User user) {
                args.putLong("user_id", user.id);
                callback.accept(new ProfileActivity(args));
            } else if (peer instanceof TLRPC.Chat chat) {
                args.putLong("chat_id", chat.id);
                callback.accept(ChatObject.isForum(chat) ? new TopicsFragment(args) : new ChatActivity(args));
            }
        });
        if (progress != null) {
            progress.init();
        } else {
            try {
                progressDialog.showDelayed(300);
            } catch (Exception ignored) {
            }
        }
    }

    public void searchPeer(long dialogId, Consumer<Object> callback) {
        if (callback == null) {
            return;
        }
        if (dialogId < 0) {
            searchChat(-dialogId, callback::accept);
        } else {
            searchUser(dialogId, callback::accept);
        }
    }

    public void searchUser(long userId, Consumer<TLRPC.User> callback) {
        if (callback != null) {
            callback.accept(getMessagesController().getUser(userId));
        }
    }

    public void searchChat(long chatId, Consumer<TLRPC.Chat> callback) {
        if (callback != null) {
            callback.accept(getMessagesController().getChat(chatId));
        }
    }

    private static String getDCLocation(int dc) {
        return switch (dc) {
            case 1, 3 -> "Miami";
            case 2, 4 -> "Amsterdam";
            case 5 -> "Singapore";
            default -> "Unknown";
        };
    }

    private static String getDCName(int dc) {
        return switch (dc) {
            case 1 -> "Pluto";
            case 2 -> "Venus";
            case 3 -> "Aurora";
            case 4 -> "Vesta";
            case 5 -> "Flora";
            default -> "Unknown";
        };
    }

    public static String formatDCString(int dc) {
        return String.format(Locale.US, "DC%d %s, %s", dc, UserHelper.getDCName(dc), UserHelper.getDCLocation(dc));
    }

    public static long getOwnerFromStickerSetId(long id) {
        var ownerId = id >> 32;
        var extByte = (id >> 24) & 0xff;
        var sepByte = (id >> 16) & 0xff;
        if (sepByte == 0x3f) {
            ownerId |= 0x80000000L;
        }
        if (extByte != 0) {
            ownerId += 0x100000000L;
        }
        return ownerId;
    }
}
