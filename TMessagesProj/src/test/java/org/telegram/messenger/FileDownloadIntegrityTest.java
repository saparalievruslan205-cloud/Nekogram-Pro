package org.telegram.messenger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileDownloadIntegrityTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectsMissingFilesAndDirectories() {
        assertFalse(FileDownloadIntegrity.isFileComplete(null, 100));
        assertFalse(FileDownloadIntegrity.isFileComplete(new File(temporaryFolder.getRoot(), "missing.mp4"), 100));
        assertFalse(FileDownloadIntegrity.isFileComplete(temporaryFolder.getRoot(), 0));
    }

    @Test
    public void rejectsEmptyVideoEvenWhenSizeIsUnknown() throws Exception {
        File file = temporaryFolder.newFile("empty.mp4");
        assertFalse(FileDownloadIntegrity.isFileComplete(file, 100));
        assertFalse(FileDownloadIntegrity.isFileComplete(file, 0));
        assertFalse(FileDownloadIntegrity.isFileComplete(file, -1));
    }

    @Test
    public void rejectsTruncatedFinalFileWithoutTemporaryFiles() throws Exception {
        File file = video(99);
        assertFalse(FileDownloadIntegrity.isFileComplete(file, 100));
    }

    @Test
    public void sidecarsDoNotMakeTruncatedFinalFileComplete() throws Exception {
        File file = video(99);
        temporaryFolder.newFile("1_42.temp");
        temporaryFolder.newFile("1_42_64.preload");
        assertFalse(FileDownloadIntegrity.isFileComplete(file, 100));
    }

    @Test
    public void acceptsCompleteVideoAndStaleSidecars() throws Exception {
        File file = video(100);
        temporaryFolder.newFile("1_42.temp");
        temporaryFolder.newFile("1_42_64.preload");
        assertTrue(FileDownloadIntegrity.isFileComplete(file, 100));
    }

    @Test
    public void acceptsLargerVideoAndNonemptyVideoWithUnknownSize() throws Exception {
        File file = video(101);
        assertTrue(FileDownloadIntegrity.isFileComplete(file, 100));
        assertTrue(FileDownloadIntegrity.isFileComplete(file, 0));
        assertTrue(FileDownloadIntegrity.isFileComplete(file, -1));
    }

    @Test
    public void cannotFinishBeforeAllExpectedBytesArrive() {
        assertFalse(FileDownloadIntegrity.canFinish(100, 99, null));
        assertFalse(FileDownloadIntegrity.canFinish(100, 99, Collections.emptyList()));
        assertTrue(FileDownloadIntegrity.canFinish(100, 100, null));
        assertTrue(FileDownloadIntegrity.canFinish(100, 101, Collections.emptyList()));
    }

    @Test
    public void sparseFileLengthCannotOverrideMissingRanges() throws Exception {
        File file = video(100);
        assertTrue(FileDownloadIntegrity.isFileComplete(file, 100));
        assertFalse(FileDownloadIntegrity.canFinish(100, 100, Collections.singletonList("missing middle chunk")));
        assertFalse(FileDownloadIntegrity.canFinish(100, 99, Collections.singletonList("missing tail")));
    }

    @Test
    public void unknownSizeStillRequiresAllRanges() {
        assertTrue(FileDownloadIntegrity.canFinish(0, 100, null));
        assertTrue(FileDownloadIntegrity.canFinish(-1, 100, Collections.emptyList()));
        assertFalse(FileDownloadIntegrity.canFinish(0, 100, Collections.singletonList("missing chunk")));
    }

    @Test
    public void handlesSizesAboveTwoGigabytesWithoutIntegerOverflow() {
        long size = (long) Integer.MAX_VALUE + 1024;
        assertFalse(FileDownloadIntegrity.canFinish(size, size - 1, null));
        assertTrue(FileDownloadIntegrity.canFinish(size, size, null));
    }

    private File video(long size) throws Exception {
        File file = temporaryFolder.newFile("1_42.mp4");
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(size);
        }
        return file;
    }
}
