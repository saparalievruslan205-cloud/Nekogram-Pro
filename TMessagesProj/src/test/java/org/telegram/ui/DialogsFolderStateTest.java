package org.telegram.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DialogsFolderStateTest {
    @Test
    public void forwardingPickerCannotChangeMainFolderOrReturnPosition() {
        DialogsFolderState main = new DialogsFolderState();
        main.selectFilter(42);
        main.saveScroll(2, -19, -200);

        DialogsFolderState picker = new DialogsFolderState();
        assertEquals(0, picker.resolveFilterIndex(new int[]{0, 42, 77}));
        picker.selectFilter(77);
        picker.saveScroll(0, 0, 100);
        picker.selectFilter(0);

        assertEquals(1, main.resolveFilterIndex(new int[]{0, 42, 77}));
        assertEquals(42, main.getFilterId());
        assertEquals(2, position(main, 100, -100, -200, -300));
        assertEquals(-19, main.getScrollOffset());
    }

    @Test
    public void repeatedSavedMessagesReloadsRetainOriginalAnchor() {
        DialogsFolderState state = folder(42);
        state.saveScroll(2, -25, -200);
        for (int notification = 0; notification < 4; notification++) {
            assertEquals(1, state.resolveFilterIndex(new int[]{0, 42, 77}));
            state.saveScroll(0, 0, 100);
        }
        assertEquals(3, position(state, 999, 100, -100, -200, -300));
        assertEquals(-25, state.getScrollOffset());
    }

    @Test
    public void quickSaveRetainsAnchorWhenSavedMessagesMovesToTop() {
        DialogsFolderState state = folder(42);
        state.saveScroll(1, -12, -200);
        assertEquals(1, state.resolveFilterIndex(new int[]{0, 42}));
        assertEquals(2, position(state, 999, -100, -200, -300));
        assertEquals(-12, state.getScrollOffset());
    }

    @Test
    public void shareAlertRetainsAnchorWhenFolderExcludesSavedMessages() {
        DialogsFolderState state = folder(42);
        state.saveScroll(1, -12, -200);
        assertEquals(1, state.resolveFilterIndex(new int[]{0, 42}));
        assertEquals(1, position(state, -100, -200, -300));
    }

    @Test
    public void reorderUsesFolderIdentityEvenIfOldIndexStillExists() {
        DialogsFolderState state = folder(77);
        state.saveScroll(1, 9, -200);
        assertEquals(1, state.resolveFilterIndex(new int[]{0, 77, 42}));
        assertEquals(77, state.getFilterId());
        assertEquals(1, position(state, -100, -200));
        assertEquals(9, state.getScrollOffset());
    }

    @Test
    public void hiddenAllChatsAndMovedDefaultKeepControllerIndex() {
        DialogsFolderState state = folder(77);
        assertEquals(2, state.resolveFilterIndex(new int[]{42, -1, 77}));
        assertEquals(1, new DialogsFolderState().resolveFilterIndex(new int[]{-1, 42, 77}));
    }

    @Test
    public void deletedOrLockedFolderFallsBackAndDropsOldFolderScroll() {
        for (int[] available : new int[][]{{0, 42}, {-1, 42, -1}}) {
            DialogsFolderState state = folder(77);
            state.saveScroll(2, -30, -300);
            int fallback = state.resolveFilterIndex(available);
            assertEquals(available[0] == 0 ? 0 : 1, fallback);
            assertEquals(available[fallback], state.getFilterId());
            assertEquals(-1, position(state, -100, -200, -300));
        }
    }

    @Test
    public void deliberateFolderSwitchCannotRestorePreviousFoldersScroll() {
        DialogsFolderState state = folder(42);
        state.saveScroll(1, -7, -200);
        state.selectFilter(77);
        assertEquals(-1, position(state, -100, -200, -300));
    }

    @Test
    public void deletedAnchorFallsBackToNearestValidRow() {
        DialogsFolderState state = folder(42);
        state.saveScroll(4, -7, -500);
        assertEquals(1, position(state, -100, -200));
        assertEquals(-7, state.getScrollOffset());
    }

    @Test
    public void nonDialogRowsRetainPositionAndEmptyListsHaveNoAnchor() {
        DialogsFolderState state = folder(42);
        state.saveScroll(1, 14, 0);
        assertEquals(1, position(state, 0, 0, -100));
        assertEquals(-1, position(state));
    }

    @Test
    public void consumedSnapshotDoesNotOverrideNextNavigation() {
        DialogsFolderState state = folder(42);
        state.saveScroll(1, -12, -200);
        state.clearScroll();
        assertEquals(-1, position(state, -100, -200));
        state.saveScroll(0, 5, -100);
        assertEquals(0, position(state, -100, -200));
        assertEquals(5, state.getScrollOffset());
    }

    private static DialogsFolderState folder(int id) {
        DialogsFolderState state = new DialogsFolderState();
        state.selectFilter(id);
        return state;
    }

    private static int position(DialogsFolderState state, long... dialogIds) {
        return state.resolveScrollPosition(dialogIds.length, i -> dialogIds[i]);
    }
}
