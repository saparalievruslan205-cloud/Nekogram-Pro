package org.telegram.messenger;

import android.app.Application;

import androidx.media3.common.C;

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
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class,
        shadows = FileStreamLoadOperationTest.ShadowLoadOperation.class)
public class FileStreamLoadOperationTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void videoFileSelectionRejectsEmptyAndTruncatedFiles() throws Exception {
        TLRPC.Document document = new TLRPC.TL_document();
        document.mime_type = "video/mp4";
        document.size = 8;
        File file = temporaryFolder.newFile();
        assertFalse(FileLoader.isDocumentFileComplete(document, file));
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(4);
            assertFalse(FileLoader.isDocumentFileComplete(document, file));
            output.setLength(8);
            assertTrue(FileLoader.isDocumentFileComplete(document, file));
        }
    }

    @Test
    public void onlyReturnsEofAfterAllExpectedBytes() throws Exception {
        TestStream stream = stream(4, 4, true);
        try {
            assertEquals(4, stream.read(new byte[8], 0, 8));
            assertEquals(C.RESULT_END_OF_INPUT, stream.read(new byte[8], 0, 8));
            assertEquals(0, stream.read(new byte[0], 0, 0));
        } finally {
            stream.close();
        }
    }

    @Test
    public void truncatedFinishedFileThrowsIoInsteadOfEof() throws Exception {
        TestStream stream = stream(8, 4, true);
        try {
            IOException error = assertThrows(IOException.class, () -> stream.read(new byte[8], 0, 8));
            assertFalse(error instanceof java.io.EOFException);
            assertEquals(8L, (long) ReflectionHelpers.getField(stream, "bytesRemaining"));
        } finally {
            stream.close();
        }
    }

    @Test
    public void physicalEofInTemporaryFileThrowsIoInsteadOfEof() throws Exception {
        TestStream stream = stream(8, 4, false);
        stream.shadow.available = 8;
        try {
            assertEquals(4, stream.read(new byte[8], 0, 8));
            IOException error = assertThrows(IOException.class, () -> stream.read(new byte[8], 0, 8));
            assertFalse(error instanceof java.io.EOFException);
            assertEquals(4L, (long) ReflectionHelpers.getField(stream, "bytesRemaining"));
        } finally {
            stream.close();
        }
    }

    @Test
    public void fileRenamedBeforeOpenRemainsRetryable() throws Exception {
        TestStream stream = stream(8, 8, true);
        stream.shadow.file = new File(temporaryFolder.getRoot(), "renamed.temp") {
            @Override
            public boolean isFile() {
                return true;
            }

            @Override
            public long length() {
                return 8;
            }
        };
        try {
            IOException error = assertThrows(IOException.class, () -> stream.read(new byte[8], 0, 8));
            assertFalse(error instanceof java.io.FileNotFoundException);
            assertNull(error.getCause());
            assertEquals(1, error.getSuppressed().length);
            assertTrue(error.getSuppressed()[0] instanceof java.io.FileNotFoundException);
        } finally {
            stream.close();
        }
    }

    @Test
    public void cancellationWhileWaitingThrowsInterruptedIo() throws Exception {
        assertWaitingReadInterrupted(true);
    }

    @Test
    public void closeWhileWaitingThrowsInterruptedIo() throws Exception {
        assertWaitingReadInterrupted(false);
    }

    @Test
    public void threadInterruptionPreservesInterruptFlag() throws Exception {
        TestStream stream = stream(8, 0, false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try {
                stream.read(new byte[8], 0, 8);
            } catch (Throwable e) {
                error.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        reader.start();
        try {
            assertTrue(stream.requested.await(5, TimeUnit.SECONDS));
            reader.interrupt();
            reader.join(5000);
            assertFalse(reader.isAlive());
            assertTrue(error.get() instanceof InterruptedIOException);
            assertTrue(interrupted.get());
        } finally {
            stream.close();
            reader.interrupt();
            reader.join(5000);
        }
    }

    @Test(timeout = 10000)
    public void rechecksAvailabilityWhenReplacementDoesNotSendNotification() throws Exception {
        TestStream stream = stream(8, 8, false);
        stream.shadow.available = 0;
        stream.onRequest = () -> stream.shadow.available = 8;
        try {
            assertEquals(8, stream.read(new byte[8], 0, 8));
        } finally {
            stream.close();
        }
    }

    @Test
    public void notificationWithoutDataDoesNotReturnZeroOrEof() throws Exception {
        TestStream stream = stream(8, 0, false);
        stream.onRequest = stream::newDataAvailable;
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread reader = reader(stream, error);
        reader.start();
        try {
            assertTrue(stream.requested.await(5, TimeUnit.SECONDS));
            stream.cancel();
            reader.join(5000);
            assertFalse(reader.isAlive());
            assertTrue(error.get() instanceof InterruptedIOException);
        } finally {
            stream.close();
            reader.interrupt();
            reader.join(5000);
        }
    }

    private void assertWaitingReadInterrupted(boolean cancel) throws Exception {
        TestStream stream = stream(8, 0, false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread reader = reader(stream, error);
        reader.start();
        try {
            assertTrue(stream.requested.await(5, TimeUnit.SECONDS));
            if (cancel) {
                stream.cancel();
            } else {
                stream.close();
            }
            reader.join(5000);
            assertFalse(reader.isAlive());
            assertTrue(error.get() instanceof InterruptedIOException);
        } finally {
            stream.close();
            reader.interrupt();
            reader.join(5000);
        }
    }

    private Thread reader(TestStream stream, AtomicReference<Throwable> error) {
        return new Thread(() -> {
            try {
                int count = stream.read(new byte[8], 0, 8);
                error.set(new AssertionError("Read returned " + count + " before EOF"));
            } catch (Throwable e) {
                error.set(e);
            }
        });
    }

    private TestStream stream(long expectedSize, long actualSize, boolean finished) throws Exception {
        TLRPC.Document document = new TLRPC.TL_document();
        document.size = expectedSize;
        document.mime_type = "video/mp4";
        File file = temporaryFolder.newFile();
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(actualSize);
        }
        FileLoadOperation operation = new FileLoadOperation(document, null);
        ShadowLoadOperation shadow = Shadow.extract(operation);
        shadow.file = file;
        shadow.available = actualSize;
        shadow.finished = finished;
        TestStream stream = new TestStream(operation, shadow);
        ReflectionHelpers.setField(stream, "document", document);
        ReflectionHelpers.setField(stream, "opened", true);
        ReflectionHelpers.setField(stream, "bytesRemaining", expectedSize);
        ReflectionHelpers.setField(stream, "loadOperation", operation);
        return stream;
    }

    private static class TestStream extends FileStreamLoadOperation {
        final FileLoadOperation operation;
        final ShadowLoadOperation shadow;
        final CountDownLatch requested = new CountDownLatch(1);
        Runnable onRequest;

        TestStream(FileLoadOperation operation, ShadowLoadOperation shadow) {
            this.operation = operation;
            this.shadow = shadow;
        }

        @Override
        protected FileLoadOperation loadStreamFile() {
            if (onRequest != null) {
                onRequest.run();
            }
            requested.countDown();
            return operation;
        }
    }

    @Implements(FileLoadOperation.class)
    public static class ShadowLoadOperation {
        File file;
        volatile long available;
        boolean finished;

        @Implementation
        protected void __constructor__(TLRPC.Document document, Object parent) {
        }

        @Implementation
        protected long[] getDownloadedLengthFromOffset(long offset, long length) {
            return new long[]{Math.min(length, Math.max(0, available - offset)), finished ? 1 : 0};
        }

        @Implementation
        protected File getCurrentFileFast() {
            return file;
        }

        @Implementation
        protected boolean isFinished() {
            return finished;
        }

        @Implementation
        protected boolean isPreloadFinished() {
            return false;
        }

        @Implementation
        protected void removeStreamListener(FileLoadOperationStream listener) {
        }
    }
}
