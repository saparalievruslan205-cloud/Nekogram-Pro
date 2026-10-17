package org.telegram.messenger;

import java.io.File;
import java.util.Collection;

/** Size and range checks shared by download completion and local video playback. */
final class FileDownloadIntegrity {
    private FileDownloadIntegrity() {
    }

    static boolean isFileComplete(File file, long expectedSize) {
        if (file == null || !file.isFile()) {
            return false;
        }
        long length = file.length();
        // Temporary/preload files and their lengths cannot prove that a final file is complete.
        return length > 0 && (expectedSize <= 0 || length >= expectedSize);
    }

    static boolean canFinish(long totalBytesCount, long downloadedBytes, Collection<?> missingRanges) {
        return (totalBytesCount <= 0 || downloadedBytes >= totalBytesCount)
                && (missingRanges == null || missingRanges.isEmpty());
    }
}
