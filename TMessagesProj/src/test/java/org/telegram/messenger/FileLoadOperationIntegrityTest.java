package org.telegram.messenger;

import android.app.Application;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class,
        shadows = FileLoadOperationIntegrityTest.ShadowLoadOperation.class)
public class FileLoadOperationIntegrityTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void incompleteByteCountCannotFinishOrRenameTempFile() throws Exception {
        checkIncomplete(99, false, 1);
    }

    @Test
    public void cancellationCannotFinalizeIncompleteFile() throws Exception {
        checkIncomplete(99, false, 5);
    }

    @Test
    public void sparseFileWithMissingRangesCannotFinish() throws Exception {
        checkIncomplete(100, true, 1);
    }

    @Test
    public void completedPreloadDoesNotExposeMissingRangeAsDownloaded() throws Exception {
        FileLoadOperation operation = new FileLoadOperation(new TLRPC.TL_document(), null);
        ReflectionHelpers.setField(operation, "state", 3);
        ReflectionHelpers.setField(operation, "preloadFinished", true);
        ReflectionHelpers.setField(operation, "totalBytesCount", 100L);
        ReflectionHelpers.setField(operation, "downloadedBytes", 50L);
        ArrayList<FileLoadOperation.Range> ranges = ranges(50, 100);
        Method query = FileLoadOperation.class.getDeclaredMethod("getDownloadedLengthFromOffsetInternal", ArrayList.class, long.class, long.class);
        query.setAccessible(true);
        assertEquals(0L, query.invoke(operation, ranges, 50L, 50L));
        assertEquals(50L, query.invoke(operation, ranges, 0L, 100L));
    }

    private void checkIncomplete(long downloadedBytes, boolean missingRanges, int state) throws Exception {
        FileLoadOperation operation = new FileLoadOperation(new TLRPC.TL_document(), null);
        File temp = temporaryFolder.newFile("video.temp");
        File target = new File(temporaryFolder.getRoot(), "video.mp4");
        try (RandomAccessFile output = new RandomAccessFile(temp, "rw")) {
            output.setLength(100); // Logical length alone cannot prove that every range arrived.
        }
        ReflectionHelpers.setField(operation, "state", state);
        ReflectionHelpers.setField(operation, "cacheFileTemp", temp);
        ReflectionHelpers.setField(operation, "cacheFileFinal", target);
        ReflectionHelpers.setField(operation, "totalBytesCount", 100L);
        ReflectionHelpers.setField(operation, "downloadedBytes", downloadedBytes);
        if (missingRanges) {
            ReflectionHelpers.setField(operation, "notLoadedBytesRanges", ranges(50, 60));
        }
        Method finish = FileLoadOperation.class.getDeclaredMethod("onFinishLoadingFile", boolean.class, int.class, boolean.class);
        finish.setAccessible(true);
        finish.invoke(operation, false, 0, false);
        ShadowLoadOperation shadow = Shadow.extract(operation);
        assertTrue(shadow.failed);
        assertFalse(operation.isFinished());
        assertTrue(temp.exists());
        assertFalse(target.exists());
    }

    private ArrayList<FileLoadOperation.Range> ranges(long start, long end) throws Exception {
        Constructor<FileLoadOperation.Range> constructor = FileLoadOperation.Range.class.getDeclaredConstructor(long.class, long.class);
        constructor.setAccessible(true);
        ArrayList<FileLoadOperation.Range> ranges = new ArrayList<>();
        ranges.add(constructor.newInstance(start, end));
        return ranges;
    }

    @Implements(FileLoadOperation.class)
    public static class ShadowLoadOperation {
        boolean failed;

        @Implementation
        protected void __constructor__(TLRPC.Document document, Object parent) {
        }

        @Implementation
        protected void onFail(boolean thread, int reason) {
            failed = true;
        }
    }
}
