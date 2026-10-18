package org.telegram.ui;

import java.util.function.IntToLongFunction;

/** Folder identity and return position owned by one dialogs page, never by a chat picker. */
final class DialogsFolderState {
    private int filterId = -1;
    private int scrollPosition = -1;
    private int scrollOffset;
    private long scrollDialogId;

    int getFilterId() {
        return filterId;
    }

    void selectFilter(int id) {
        if (filterId != id) {
            filterId = id;
            clearScroll();
        }
    }

    // Entries retain controller indices; -1 marks a hidden or unavailable folder.
    int resolveFilterIndex(int[] availableFilterIds) {
        int first = -1;
        for (int i = 0; i < availableFilterIds.length; i++) {
            if (availableFilterIds[i] < 0) {
                continue;
            }
            if (first < 0) {
                first = i;
            }
            if (availableFilterIds[i] == filterId) {
                return i;
            }
        }
        selectFilter(first < 0 ? -1 : availableFilterIds[first]);
        return first;
    }

    void saveScroll(int position, int offset, long dialogId) {
        // Keep the pre-navigation anchor through repeated background updates.
        if (scrollPosition < 0 && position >= 0) {
            scrollPosition = position;
            scrollOffset = offset;
            scrollDialogId = dialogId;
        }
    }

    int getScrollOffset() {
        return scrollOffset;
    }

    int resolveScrollPosition(int itemCount, IntToLongFunction dialogIdAtPosition) {
        if (scrollPosition < 0 || itemCount <= 0) {
            return -1;
        }
        if (scrollDialogId != 0) {
            for (int i = 0; i < itemCount; i++) {
                if (dialogIdAtPosition.applyAsLong(i) == scrollDialogId) {
                    return i;
                }
            }
        }
        return Math.min(scrollPosition, itemCount - 1);
    }

    void clearScroll() {
        scrollPosition = -1;
        scrollDialogId = 0;
    }
}
