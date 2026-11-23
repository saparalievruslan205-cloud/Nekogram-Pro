package tw.nekomimi.nekogram.forward;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.SparseIntArray;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;

import java.util.Arrays;
import java.util.Collection;

import tw.nekomimi.nekogram.NekoConfig;

public class ForwardItem {
    public static final int ID_FORWARD = -100;
    public static final int ID_FORWARD_NOQUOTE = -101;
    public static final int ID_FORWARD_NOCAPTION = -102;

    private static final SharedPreferences preferences = ApplicationLoader.applicationContext != null
            ? ApplicationLoader.applicationContext.getSharedPreferences("nekoforward", Context.MODE_PRIVATE)
            : null;
    public static int lastForwardOption = preferences != null
            ? preferences.getInt("lastForwardOption", ForwardItem.ID_FORWARD)
            : ForwardItem.ID_FORWARD;

    static final int[] ITEM_IDS = new int[]{
            ID_FORWARD,
            ID_FORWARD_NOQUOTE,
            ID_FORWARD_NOCAPTION,
    };
    static final SparseIntArray ITEM_TITLES = new SparseIntArray() {{
        put(ID_FORWARD, R.string.Forward);
        put(ID_FORWARD_NOQUOTE, R.string.NoQuoteForward);
        put(ID_FORWARD_NOCAPTION, R.string.NoCaptionForward);
    }};
    static final SparseIntArray ITEM_TITLES_SHORT = new SparseIntArray() {{
        put(ID_FORWARD, R.string.Forward);
        put(ID_FORWARD_NOQUOTE, R.string.NoQuoteForwardShort);
        put(ID_FORWARD_NOCAPTION, R.string.NoCaptionForwardShort);
    }};

    public static void setLastForwardOption(int option) {
        lastForwardOption = option;
        if (preferences != null) {
            preferences.edit().putInt("lastForwardOption", lastForwardOption).apply();
        }
    }

    public static void setupForwardItem(ActionBarMenuItem item, boolean hasCaption, Theme.ResourcesProvider resourcesProvider, ActionBarMenuItem.ActionBarMenuItemDelegate delegate) {
        setupForwardItem(item, true, false, hasCaption, resourcesProvider, delegate);
    }

    public static void setupForwardItem(ActionBarMenuItem item, boolean setIcon, boolean darkTheme, boolean hasCaption, Theme.ResourcesProvider resourcesProvider, ActionBarMenuItem.ActionBarMenuItemDelegate delegate) {
        setupForwardItem(item, setIcon, darkTheme, hasCaption, false, resourcesProvider, delegate);
    }

    public static void setupForwardItem(ActionBarMenuItem item, boolean setIcon, boolean darkTheme, boolean hasCaption, boolean share, Theme.ResourcesProvider resourcesProvider, ActionBarMenuItem.ActionBarMenuItemDelegate delegate) {
        int option = getLastForwardOption(hasCaption);
        setupActionBarForwardItem(item, option, setIcon, darkTheme, hasCaption, share, resourcesProvider, delegate);
    }

    public static void setupNoQuoteForwardItem(ActionBarMenuItem item, boolean hasCaption, Theme.ResourcesProvider resourcesProvider, ActionBarMenuItem.ActionBarMenuItemDelegate delegate) {
        setupNoQuoteForwardItem(item, true, false, hasCaption, false, resourcesProvider, delegate);
    }

    public static void setupNoQuoteForwardItem(ActionBarMenuItem item, boolean setIcon, boolean darkTheme, boolean hasCaption, boolean share, Theme.ResourcesProvider resourcesProvider, ActionBarMenuItem.ActionBarMenuItemDelegate delegate) {
        int option = getNoQuoteForwardOption(hasCaption);
        setupActionBarForwardItem(item, option, setIcon, darkTheme, hasCaption, share, resourcesProvider, delegate);
    }

    private static void setupActionBarForwardItem(ActionBarMenuItem item, int defaultOption, boolean setIcon, boolean darkTheme, boolean hasCaption, boolean share, Theme.ResourcesProvider resourcesProvider, ActionBarMenuItem.ActionBarMenuItemDelegate delegate) {
        if (setIcon) {
            if (item.getIconView() == null || !(item.getIconView().getDrawable() instanceof ForwardDrawable) || !((ForwardDrawable) item.getIconView().getDrawable()).matches(defaultOption, share)) {
                item.setIcon(new ForwardDrawable(defaultOption, share));
            }
            item.setContentDescription(getForwardOptionTitle(defaultOption, false));
        }
        if (!item.hasSubMenu()) {
            Arrays.stream(ITEM_IDS).forEach(itemId -> {
                var subItem = item.addSubItem(itemId, new ForwardDrawable(itemId, share), LocaleController.getString(ITEM_TITLES.get(itemId)), resourcesProvider);
                if (darkTheme) subItem.setColors(0xfffafafa, 0xfffafafa);
            });
            if (darkTheme) {
                item.redrawPopup(0xf9222222);
                item.setPopupItemsSelectorColor(0x0fffffff);
            }
        }
        if (hasCaption) {
            item.showSubItem(ID_FORWARD_NOCAPTION);
        } else {
            item.hideSubItem(ID_FORWARD_NOCAPTION);
        }
        item.setOnClickListener(v -> delegate.onItemClick(defaultOption));
        item.setLongClickEnabled(true);
        item.setShowSubmenuByMove(true);
    }

    public static boolean hasCaption(Collection<MessageObject> messages) {
        return messages != null && messages.stream().anyMatch(messageObject -> messageObject != null && !TextUtils.isEmpty(messageObject.caption));
    }

    public static boolean hasCaption(MessageObject selectedObject, MessageObject.GroupedMessages selectedObjectGroup) {
        if (selectedObject != null && !TextUtils.isEmpty(selectedObject.caption)) {
            return true;
        } else if (selectedObjectGroup != null) {
            return selectedObjectGroup.messages.stream().anyMatch(messageObject -> messageObject != null && !TextUtils.isEmpty(messageObject.caption));
        } else {
            return false;
        }
    }

    public static String getForwardOptionTitle(int option, boolean shortTitle) {
        return LocaleController.getString((shortTitle ? ITEM_TITLES_SHORT : ITEM_TITLES).get(option));
    }

    public static String getNoQuoteForwardOptionTitle(boolean hasCaption, boolean shortTitle) {
        return getForwardOptionTitle(getNoQuoteForwardOption(hasCaption), shortTitle);
    }

    public static ForwardDrawable getNoQuoteForwardOptionIcon(boolean hasCaption) {
        return getNoQuoteForwardOptionIcon(hasCaption, false);
    }

    public static ForwardDrawable getNoQuoteForwardOptionIcon(boolean hasCaption, boolean share) {
        return new ForwardDrawable(getNoQuoteForwardOption(hasCaption), share);
    }

    public static int getNoQuoteForwardOption(boolean hasCaption) {
        return hasCaption ? ID_FORWARD_NOCAPTION : ID_FORWARD_NOQUOTE;
    }

    public static String getLastForwardOptionTitle(boolean hasCaption, boolean shortTitle) {
        return getForwardOptionTitle(getLastForwardOption(hasCaption), shortTitle);
    }

    public static ForwardDrawable getLastForwardOptionIcon(boolean hasCaption) {
        return getLastForwardOptionIcon(hasCaption, false);
    }

    public static ForwardDrawable getLastForwardOptionIcon(boolean hasCaption, boolean share) {
        return new ForwardDrawable(getLastForwardOption(hasCaption), share);
    }

    public static int getLastForwardOption(boolean hasCaption) {
        return resolveForwardOption(ForwardItem.lastForwardOption, NekoConfig.showNoQuoteForward, hasCaption);
    }

    static int resolveForwardOption(int lastOption, boolean showNoQuoteForward, boolean hasCaption) {
        if (showNoQuoteForward) {
            if (lastOption == ID_FORWARD_NOQUOTE || lastOption == ID_FORWARD_NOCAPTION) {
                return ID_FORWARD;
            }
            return lastOption;
        }
        if (lastOption == ID_FORWARD_NOQUOTE || lastOption == ID_FORWARD_NOCAPTION) {
            return getNoQuoteForwardOption(hasCaption);
        }
        return lastOption;
    }
}
