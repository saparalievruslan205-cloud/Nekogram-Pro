package org.telegram.ui.Components;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        MediaPreviewLifecycleTest.ShadowTheme.class,
        MediaPreviewLifecycleTest.ShadowLocaleController.class
})
public class RecyclerListViewMultiselectTest {

    private RecyclerListView listView;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationContext = context;
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.density = 1f;
        AndroidUtilities.displaySize.set(300, 300);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new GridLayoutManager(context, 3));
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return true;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = new View(parent.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(100, 100));
                return new RecyclerListView.Holder(v);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            }

            @Override
            public int getItemCount() {
                return 9;
            }
        });
        listView.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
        );
        listView.layout(0, 0, 300, 300);
    }

    @Test
    public void horizontalDragIn2DGridSelectsAnchorAndRowItemsInOrder() {
        List<Integer> selectionOrder = new ArrayList<>();
        Set<Integer> selectedSet = new LinkedHashSet<>();
        AtomicInteger dragStartedCount = new AtomicInteger();
        AtomicBoolean endedWithDrag = new AtomicBoolean(false);

        listView.startMultiselect(0, false, new RecyclerListView.onMultiSelectionChanged() {
            @Override
            public void onSelectionChanged(int position, boolean selected, float x, float y) {
                if (selected) {
                    selectedSet.add(position);
                    selectionOrder.add(position);
                } else {
                    selectedSet.remove(position);
                }
            }

            @Override
            public boolean canSelect(int position) {
                return true;
            }

            @Override
            public int checkPosition(int position, boolean selectionFromTop) {
                return position;
            }

            @Override
            public boolean limitReached() {
                return false;
            }

            @Override
            public void getPaddings(int[] paddings) {
                paddings[0] = 0;
                paddings[1] = 0;
            }

            @Override
            public void scrollBy(int dy) {
            }

            @Override
            public boolean allowHorizontalDrag() {
                return true;
            }

            @Override
            public void onMultiselectDragStarted() {
                dragStartedCount.incrementAndGet();
                selectedSet.add(0);
                selectionOrder.add(0);
            }

            @Override
            public void onMultiselectEnded(boolean dragStarted, boolean canceled) {
                endedWithDrag.set(dragStarted && !canceled);
            }
        });

        // Initial touch anchor inside cell 0 (50, 50)
        dispatchMove(50, 50);
        assertEquals(0, dragStartedCount.get());

        // Drag horizontally to cell 1 (150, 50) and cell 2 (250, 50) on same row (dy == 0)
        dispatchMove(150, 50);
        dispatchMove(250, 50);
        dispatchUp(250, 50);

        assertEquals(1, dragStartedCount.get());
        assertTrue(endedWithDrag.get());
        assertEquals(List.of(0, 1, 2), selectionOrder);
        assertEquals(Set.of(0, 1, 2), selectedSet);
    }

    @Test
    public void crossingAnchorPositionInGridReversesSelectionCleanly() {
        Set<Integer> selectedSet = new LinkedHashSet<>();
        selectedSet.add(4); // anchor cell (row 1, col 1)

        listView.startMultiselect(4, false, new RecyclerListView.onMultiSelectionChanged() {
            @Override
            public void onSelectionChanged(int position, boolean selected, float x, float y) {
                if (selected) {
                    selectedSet.add(position);
                } else {
                    selectedSet.remove(position);
                }
            }

            @Override
            public boolean canSelect(int position) {
                return true;
            }

            @Override
            public int checkPosition(int position, boolean selectionFromTop) {
                return position;
            }

            @Override
            public boolean limitReached() {
                return false;
            }

            @Override
            public void getPaddings(int[] paddings) {
                paddings[0] = 0;
                paddings[1] = 0;
            }

            @Override
            public void scrollBy(int dy) {
            }

            @Override
            public boolean allowHorizontalDrag() {
                return true;
            }
        });

        // Start at cell 4 (150, 150), drag right to cell 5 (250, 150)
        dispatchMove(150, 150);
        dispatchMove(250, 150);
        assertEquals(Set.of(4, 5), selectedSet);

        // Now drag straight up to cell 2 (250, 50), crossing anchor 4 in one jump!
        dispatchMove(250, 50);
        dispatchUp(250, 50);

        // Cell 5 must be unselected, and cells 3 & 2 selected alongside anchor 4
        assertEquals(Set.of(4, 3, 2), selectedSet);
    }

    @Test
    public void releaseWithoutDragNotifiesEndAndCancelMultiselectSuppressesFurtherEvents() {
        AtomicInteger endedNoDragCount = new AtomicInteger();
        AtomicInteger dragCount = new AtomicInteger();

        listView.startMultiselect(0, false, new RecyclerListView.onMultiSelectionChanged() {
            @Override
            public void onSelectionChanged(int position, boolean selected, float x, float y) {
            }

            @Override
            public boolean canSelect(int position) {
                return true;
            }

            @Override
            public int checkPosition(int position, boolean selectionFromTop) {
                return position;
            }

            @Override
            public boolean limitReached() {
                return false;
            }

            @Override
            public void getPaddings(int[] paddings) {
                paddings[0] = 0;
                paddings[1] = 0;
            }

            @Override
            public void scrollBy(int dy) {
            }

            @Override
            public boolean allowHorizontalDrag() {
                return true;
            }

            @Override
            public void onMultiselectDragStarted() {
                dragCount.incrementAndGet();
            }

            @Override
            public void onMultiselectEnded(boolean dragStarted, boolean canceled) {
                if (!dragStarted && !canceled) {
                    endedNoDragCount.incrementAndGet();
                }
            }
        });

        // Release without dragging
        dispatchUp(50, 50);
        assertEquals(0, dragCount.get());
        assertEquals(1, endedNoDragCount.get());
        assertFalse(listView.isMultiselect());

        // Now test cancelMultiselect (simulating hold-to-open timer firing while finger is still down)
        listView.startMultiselect(0, false, new RecyclerListView.onMultiSelectionChanged() {
            @Override
            public void onSelectionChanged(int position, boolean selected, float x, float y) {
            }

            @Override
            public boolean canSelect(int position) {
                return true;
            }

            @Override
            public int checkPosition(int position, boolean selectionFromTop) {
                return position;
            }

            @Override
            public boolean limitReached() {
                return false;
            }

            @Override
            public void getPaddings(int[] paddings) {
                paddings[0] = 0;
                paddings[1] = 0;
            }

            @Override
            public void scrollBy(int dy) {
            }

            @Override
            public boolean allowHorizontalDrag() {
                return true;
            }

            @Override
            public void onMultiselectDragStarted() {
                dragCount.incrementAndGet();
            }

            @Override
            public void onMultiselectEnded(boolean dragStarted, boolean canceled) {
                endedNoDragCount.incrementAndGet();
            }
        });

        assertTrue(listView.isMultiselect());
        listView.cancelMultiselect();
        assertFalse(listView.isMultiselect());

        dispatchMove(250, 250);
        dispatchUp(250, 250);
        assertEquals(0, dragCount.get());
        assertEquals(1, endedNoDragCount.get());
    }

    private void dispatchMove(float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, x, y, 0);
        listView.onTouchEvent(event);
        event.recycle();
    }

    private void dispatchUp(float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, x, y, 0);
        listView.onTouchEvent(event);
        event.recycle();
    }
}
