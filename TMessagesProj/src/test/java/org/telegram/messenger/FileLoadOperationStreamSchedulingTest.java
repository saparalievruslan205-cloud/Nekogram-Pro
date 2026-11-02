package org.telegram.messenger;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.ConnectionsManager;
import tw.nekomimi.nekogram.NekoConfig;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class,
        shadows = FileLoadOperationStreamSchedulingTest.ShadowLoadOperation.class)
public class FileLoadOperationStreamSchedulingTest {
    private DispatchQueue previousQueue;

    @Before
    public void setUp() {
        previousQueue = Utilities.stageQueue;
        Utilities.stageQueue = new ImmediateQueue();
    }

    @After
    public void tearDown() {
        Utilities.stageQueue = previousQueue;
    }

    @Test
    public void activeLoadSchedulesNewReadPositionsFromBothReaders() {
        RecordingOperation operation = new RecordingOperation();
        ReflectionHelpers.setField(operation, "started", true);
        ReflectionHelpers.setField(operation, "state", 1);
        ReflectionHelpers.setField(operation, "currentDownloadChunkSize", 1024);
        FileStreamLoadOperation first = new FileStreamLoadOperation();
        FileStreamLoadOperation second = new FileStreamLoadOperation();

        operation.setStream(first, false, 1500);
        operation.setStream(second, false, 7200);
        operation.setStream(first, false, 0);

        assertEquals(Arrays.asList(1024L, 7168L, 0L), operation.requestedPositions);
    }

    @Test
    public void uninitializedLoadDoesNotStartNetworkRequests() {
        RecordingOperation operation = new RecordingOperation();
        ReflectionHelpers.setField(operation, "state", 1);
        ReflectionHelpers.setField(operation, "currentDownloadChunkSize", 1024);
        operation.setStream(new FileStreamLoadOperation(), false, 1500);
        assertTrue(operation.requestedPositions.isEmpty());
    }

    @Test
    public void repeatedActiveReadDoesNotPromoteUntilReaderOrPositionChanges() {
        RecordingOperation operation = new RecordingOperation();
        ReflectionHelpers.setField(operation, "started", true);
        ReflectionHelpers.setField(operation, "state", 1);
        FileStreamLoadOperation first = new FileStreamLoadOperation();
        FileStreamLoadOperation second = new FileStreamLoadOperation();
        operation.stream = first;
        operation.streamOffset = 4096;
        int current = FileLoaderPriorityQueue.PRIORITY_VALUE_MAX + 10;
        int requested = current + 1;
        operation.setPriority(current);

        assertEquals(current, FileLoader.priorityForRepeatedStreamRead(operation, first, 4096, false, requested));
        assertEquals(requested, FileLoader.priorityForRepeatedStreamRead(operation, first, 8192, false, requested));
        assertEquals(requested, FileLoader.priorityForRepeatedStreamRead(operation, second, 4096, false, requested));
        assertEquals(requested, FileLoader.priorityForRepeatedStreamRead(operation, first, 4096, true, requested));
        assertEquals(Integer.MAX_VALUE, FileLoader.priorityForRepeatedStreamRead(operation, first, 4096, false, Integer.MAX_VALUE));
        operation.setPriority(Integer.MAX_VALUE);
        assertEquals(requested, FileLoader.priorityForRepeatedStreamRead(operation, first, 4096, false, requested));
        operation.setPriority(current);
        ReflectionHelpers.setField(operation, "paused", true);
        assertEquals(requested, FileLoader.priorityForRepeatedStreamRead(operation, first, 4096, false, requested));
        ReflectionHelpers.setField(operation, "paused", false);
        ReflectionHelpers.setField(operation, "state", 2);
        assertEquals(requested, FileLoader.priorityForRepeatedStreamRead(operation, first, 4096, false, requested));
    }

    @Test
    public void extremeBoostUsesSmallerWindowOnlyForLargePlaybackVideo() {
        FileStreamLoadOperation player = new FileStreamLoadOperation();
        long threshold = 10L * 1024 * 1024;
        int video = ConnectionsManager.FileTypeVideo;
        assertTrue(FileLoadOperation.usePlaybackRequestWindow(player, video, false, threshold, threshold, NekoConfig.BOOST_EXTREME));
        assertTrue(!FileLoadOperation.usePlaybackRequestWindow(null, video, false, threshold, threshold, NekoConfig.BOOST_EXTREME));
        assertTrue(!FileLoadOperation.usePlaybackRequestWindow(player, ConnectionsManager.FileTypeAudio, false, threshold, threshold, NekoConfig.BOOST_EXTREME));
        assertTrue(!FileLoadOperation.usePlaybackRequestWindow(player, video, true, threshold, threshold, NekoConfig.BOOST_EXTREME));
        assertTrue(!FileLoadOperation.usePlaybackRequestWindow(player, video, false, threshold - 1, threshold, NekoConfig.BOOST_EXTREME));
        assertTrue(!FileLoadOperation.usePlaybackRequestWindow(player, video, false, threshold, threshold, NekoConfig.BOOST_NONE));
    }

    @Test
    public void playbackWindowCdnRangesCoverLargeFiles() {
        RecordingOperation operation = new RecordingOperation();
        ReflectionHelpers.setField(operation, "cdnChunkCheckSize", 128 * 1024);
        long size = FileLoader.DEFAULT_MAX_FILE_SIZE_PREMIUM + 1;
        ReflectionHelpers.setField(operation, "totalBytesCount", size);
        ReflectionHelpers.callInstanceMethod(operation, "updatePlaybackCdnParts");
        int parts = ReflectionHelpers.getField(operation, "maxCdnParts");
        assertTrue((long) parts * 128 * 1024 >= size);
    }

    @Test
    public void playbackWindowDoesNotReuseAnOversizedEarlierPreload() {
        assertTrue(!FileLoadOperation.canReusePreload(true, 0, 1024 * 1024, 128 * 1024, 1024 * 1024));
        assertTrue(FileLoadOperation.canReusePreload(true, 0, 128 * 1024, 128 * 1024, 1024 * 1024));
        assertTrue(!FileLoadOperation.canReusePreload(true, 0, 12, 128 * 1024, 1024 * 1024));
        assertTrue(FileLoadOperation.canReusePreload(true, 1024 * 1024 - 12, 12, 128 * 1024, 1024 * 1024));
        assertTrue(FileLoadOperation.canReusePreload(false, 0, 1024 * 1024, 128 * 1024, 1024 * 1024));
    }

    private static class ImmediateQueue extends DispatchQueue {
        ImmediateQueue() { super("stream-test", false); }
        @Override public boolean postRunnable(Runnable runnable) { runnable.run(); return true; }
        @Override public void cancelRunnable(Runnable runnable) { }
    }

    private static class RecordingOperation extends FileLoadOperation {
        final ArrayList<Long> requestedPositions = new ArrayList<>();
        RecordingOperation() { super(new TLRPC.TL_document(), null); }
        @Override protected void startDownloadRequest(int connectionType) {
            requestedPositions.add(ReflectionHelpers.getField(this, "streamStartOffset"));
        }
    }

    @Implements(FileLoadOperation.class)
    public static class ShadowLoadOperation {
        @Implementation protected void __constructor__(TLRPC.Document document, Object parent) { }
    }
}
