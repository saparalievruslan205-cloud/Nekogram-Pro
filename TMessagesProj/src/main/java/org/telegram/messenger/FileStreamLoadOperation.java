/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;


import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.castNonNull;

import android.net.Uri;

import androidx.annotation.Nullable;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

@OptIn(markerClass = UnstableApi.class)
public class FileStreamLoadOperation implements DataSource, FileLoadOperationStream {

    private static final ConcurrentHashMap<String, Set<FileStreamLoadOperation>> allStreams = new ConcurrentHashMap<>();

    private static String streamKey(int account, long documentId) {
        return account + ":" + documentId;
    }

    static FileStreamLoadOperation getStream(int account, long documentId) {
        Set<FileStreamLoadOperation> streams = allStreams.get(streamKey(account, documentId));
        return streams == null || streams.isEmpty() ? null : streams.iterator().next();
    }

    private volatile FileLoadOperation loadOperation;

    private Uri uri;
    private long bytesRemaining;
    private long bytesTransferred;
    private long requestedLength;
    private volatile boolean opened;
    private volatile boolean cancelled;
    private boolean transferWasStarted;
    private long currentOffset;
    private volatile CountDownLatch countDownLatch;
    private RandomAccessFile file;
    private TLRPC.Document document;
    private Object parentObject;
    private int currentAccount;
    File currentFile;

    private static final ConcurrentHashMap<Long, Integer> priorityMap = new ConcurrentHashMap<>();

    public FileStreamLoadOperation() {
        this.isNetwork = true;
        this.listeners = new ArrayList<>(/* initialCapacity= */ 1);
    }

    @Deprecated
    public FileStreamLoadOperation(@Nullable TransferListener listener) {
        this();
        if (listener != null) {
            addTransferListener(listener);
        }
    }

    public static int getStreamPrioriy(TLRPC.Document document) {
        if (document == null) {
            return FileLoader.PRIORITY_HIGH;
        }
        Integer integer = priorityMap.get(document.id);
        if (integer == null) {
            return FileLoader.PRIORITY_HIGH;
        }
        return integer;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        transferInitializing(dataSpec);
        currentAccount = Utilities.parseInt(uri.getQueryParameter("account"));
        parentObject = FileLoader.getInstance(currentAccount).getParentObject(Utilities.parseInt(uri.getQueryParameter("rid")));
        document = new TLRPC.TL_document();
        document.access_hash = Utilities.parseLong(uri.getQueryParameter("hash"));
        document.id = Utilities.parseLong(uri.getQueryParameter("id"));
        document.size = Utilities.parseLong(uri.getQueryParameter("size"));
        document.dc_id = Utilities.parseInt(uri.getQueryParameter("dc"));
        document.mime_type = uri.getQueryParameter("mime");
        document.file_reference = Utilities.hexToBytes(uri.getQueryParameter("reference"));
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = uri.getQueryParameter("name");
        document.attributes.add(filename);
        if (document.mime_type.startsWith("video")) {
            document.attributes.add(new TLRPC.TL_documentAttributeVideo());
        } else if (document.mime_type.startsWith("audio")) {
            document.attributes.add(new TLRPC.TL_documentAttributeAudio());
        }
        if (document.size > 0 && dataSpec.position > document.size) {
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
        }
        cancelled = false;
        isNetwork = true;
        transferWasStarted = false;
        opened = true;
        allStreams.compute(streamKey(currentAccount, document.id), (key, streams) -> {
            if (streams == null) {
                streams = ConcurrentHashMap.newKeySet();
            }
            streams.add(this);
            return streams;
        });
        currentOffset = dataSpec.position;
        requestedLength = dataSpec.length;
        loadOperation = loadStreamFile();
        bytesTransferred = 0;
        bytesRemaining = document.size - dataSpec.position;
        if (requestedLength != C.LENGTH_UNSET) {
            bytesRemaining = Math.min(bytesRemaining, requestedLength - bytesTransferred);
        }
        if (cancelled || !opened) {
            close();
            throw new InterruptedIOException("Stream load cancelled");
        }
        synchronized (this) {
            checkStreamOpen();
            transferStarted(dataSpec);
            transferWasStarted = true;
        }
        if (loadOperation != null) {
            currentFile = loadOperation.getCurrentFile();
            if (currentFile != null) {
                try {
                    RandomAccessFile openedFile = new RandomAccessFile(currentFile, "r");
                    openedFile.seek(currentOffset);
                    synchronized (this) {
                        if (cancelled || !opened) {
                            openedFile.close();
                            throw new InterruptedIOException("Stream load cancelled");
                        }
                        file = openedFile;
                    }
                    if (loadOperation.isFinished() && !loadOperation.isPreloadFinished()) {
                        isNetwork = false;
                        checkFinishedFile(currentFile);
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Throwable e) {
                }
            }
        }
        FileLog.e("FileStreamLoadOperation " + document.id + " open operation=" + loadOperation + " currentFile=" + currentFile + " file=" + file + " bytesRemaining=" + bytesRemaining + " me=" + this);
        FileLog.e("FileStreamLoadOperation " + document.id + " " + MessageObject.getVideoWidth(document) + "x" + MessageObject.getVideoWidth(document) + " mime_type="+document.mime_type+" codec="+MessageObject.getVideoCodec(document)+" size="+ document.size);
        return bytesRemaining;
    }

    private int getCurrentPriority() {
        Integer priority = priorityMap.getOrDefault(document.id, null);
        if (priority != null) {
            return priority;
        }
        return FileLoader.PRIORITY_HIGH;
    }

    protected FileLoadOperation loadStreamFile() {
        return FileLoader.getInstance(currentAccount).loadStreamFile(this, document, null, parentObject, currentOffset, false, getCurrentPriority());
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public static void cancelStream(int account, long documentId) {
        Set<FileStreamLoadOperation> streams = allStreams.get(streamKey(account, documentId));
        if (streams != null) {
            for (FileStreamLoadOperation stream : streams) {
                stream.cancel();
            }
        }
    }

    public synchronized void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        FileLog.d("cancel stream requested document=" + (document == null ? 0 : document.id) + " account=" + currentAccount);
        closeInternal();
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        checkStreamOpen();
        if (readLength == 0) {
            return 0;
        }
        if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        readLength = (int) Math.min(bytesRemaining, readLength);
        while (true) {
            // Publish before checking availability: a chunk or close arriving during the
            // check must wake this read, including when loadStreamFile replaces the operation.
            CountDownLatch latch = new CountDownLatch(1);
            synchronized (this) {
                checkStreamOpen();
                countDownLatch = latch;
            }
            try {
                FileLoadOperation operation = loadOperation;
                if (operation == null) {
                    throw new IOException("Stream load operation is unavailable");
                }
                int availableLength = (int) operation.getDownloadedLengthFromOffset(currentOffset, readLength)[0];
                checkStreamOpen();
                if (availableLength == 0) {
                    FileLoadOperation nextOperation = loadStreamFile();
                    synchronized (this) {
                        if (cancelled || !opened) {
                            if (nextOperation != null) {
                                nextOperation.removeStreamListener(this);
                            }
                            checkStreamOpen();
                        }
                        if (nextOperation == null) {
                            throw new IOException("Stream load operation is unavailable");
                        }
                        if (loadOperation != nextOperation) {
                            if (loadOperation != null) {
                                loadOperation.removeStreamListener(this);
                            }
                            loadOperation = nextOperation;
                        }
                    }
                    // Recheck after registration; an already completed operation may
                    // have sent its last notification before this listener was added.
                    if (nextOperation.getDownloadedLengthFromOffset(currentOffset, readLength)[0] == 0) {
                        checkStreamOpen();
                        latch.await();
                    }
                    continue;
                }
                synchronized (this) {
                    checkStreamOpen();
                    File nextFile = operation.getCurrentFileFast();
                    if (nextFile == null) {
                        throw new IOException("Stream file is unavailable");
                    }
                    if (operation.isFinished() && !operation.isPreloadFinished()) {
                        checkFinishedFile(nextFile);
                        isNetwork = false;
                    }
                    if (file == null || !Objects.equals(currentFile, nextFile)) {
                        if (file != null) {
                            file.close();
                            file = null;
                        }
                        try {
                            file = new RandomAccessFile(nextFile, "r");
                            file.seek(currentOffset);
                            currentFile = nextFile;
                        } catch (FileNotFoundException e) {
                            // The temp file can be renamed between checking and opening it.
                            // A FileNotFoundException cause disables Media3's normal IO retry.
                            IOException retryable = new IOException("Stream file moved before it could be opened");
                            retryable.addSuppressed(e);
                            throw retryable;
                        } catch (IOException e) {
                            throw new IOException("Unable to open stream file", e);
                        }
                    }
                    int bytesRead = file.read(buffer, offset, availableLength);
                    if (bytesRead <= 0) {
                        throw new IOException("Stream file ended before the expected size at " + currentOffset);
                    }
                    currentOffset += bytesRead;
                    bytesRemaining -= bytesRead;
                    bytesTransferred += bytesRead;
                    bytesTransferred(bytesRead);
                    return bytesRead;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("Stream read interrupted");
                interrupted.initCause(e);
                throw interrupted;
            } finally {
                synchronized (this) {
                    if (countDownLatch == latch) {
                        countDownLatch = null;
                    }
                }
            }
        }
    }

    private void checkStreamOpen() throws InterruptedIOException {
        if (cancelled || !opened || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Stream read interrupted or closed");
        }
    }

    private void checkFinishedFile(File finishedFile) throws IOException {
        if (!FileDownloadIntegrity.isFileComplete(finishedFile, document.size)) {
            throw new IOException("Completed stream file is truncated");
        }
        if (document.size <= 0) {
            bytesRemaining = Math.max(0, finishedFile.length() - currentOffset);
            if (requestedLength != C.LENGTH_UNSET) {
                bytesRemaining = Math.min(bytesRemaining, requestedLength - bytesTransferred);
            }
        }
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        closeInternal();
    }

    private synchronized void closeInternal() {
        FileLog.d("FileStreamLoadOperation " + (document == null ? 0 : document.id) + " close me=" + this);
        FileLoadOperation currentLoadOperation = loadOperation;
        if (currentLoadOperation != null) {
            currentLoadOperation.removeStreamListener(this);
        }
        if (file != null) {
            try {
                file.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
            file = null;
        }
        uri = null;
        if (document != null) {
            String key = streamKey(currentAccount, document.id);
            allStreams.computeIfPresent(key, (ignored, streams) -> {
                streams.remove(this);
                return streams.isEmpty() ? null : streams;
            });
        }
        if (opened) {
            opened = false;
            if (transferWasStarted) {
                transferWasStarted = false;
                transferEnded();
            }
        }
        if (countDownLatch != null) {
         //   FileLog.d("FileStreamLoadOperation count down");
            countDownLatch.countDown();
            countDownLatch = null;
        }
    }

    @Override
    public synchronized void newDataAvailable() {
//        FileLog.e("FileStreamLoadOperation " + document.id + " newDataAvailable me=" + this);
        CountDownLatch latch = countDownLatch;
        countDownLatch = null;
        if (latch != null) {
            latch.countDown();
        }
    }

    public static void setPriorityForDocument(TLRPC.Document document, int priority) {
        if (document != null) {
            priorityMap.put(document.id, priority);
        }
    }

    @Nullable
    public static Uri prepareUri(int currentAccount, TLRPC.Document document, Object parent) {
        String attachFileName = FileLoader.getAttachFileName(document);
        File file = FileLoader.getInstance(currentAccount).getPathToAttach(document);

        if (FileLoader.isDocumentFileComplete(document, file)) {
            return Uri.fromFile(file);
        }
        try {
            String params =
                "?account=" + currentAccount +
                "&id=" + document.id +
                "&hash=" + document.access_hash +
                "&dc=" + document.dc_id +
                "&size=" + document.size +
                "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
                "&rid=" + FileLoader.getInstance(currentAccount).getFileReference(parent) +
                "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
            return Uri.parse("tg://" + attachFileName + params);
        } catch (UnsupportedEncodingException e) {
            FileLog.e(e);
        }
        return null;
    }




    protected boolean isNetwork;
    private final ArrayList<TransferListener> listeners;

    private int listenerCount;
    @Nullable private DataSpec dataSpec;

    @Override
    public final void addTransferListener(TransferListener transferListener) {
        checkNotNull(transferListener);
        if (!listeners.contains(transferListener)) {
            listeners.add(transferListener);
            listenerCount++;
        }
    }

    /**
     * Notifies listeners that data transfer for the specified {@link DataSpec} is being initialized.
     *
     * @param dataSpec {@link DataSpec} describing the data for initializing transfer.
     */
    protected final void transferInitializing(DataSpec dataSpec) {
        for (int i = 0; i < listenerCount; i++) {
            listeners.get(i).onTransferInitializing(/* source= */ this, dataSpec, isNetwork);
        }
    }

    /**
     * Notifies listeners that data transfer for the specified {@link DataSpec} started.
     *
     * @param dataSpec {@link DataSpec} describing the data being transferred.
     */
    protected final void transferStarted(DataSpec dataSpec) {
        this.dataSpec = dataSpec;
        for (int i = 0; i < listenerCount; i++) {
            listeners.get(i).onTransferStart(/* source= */ this, dataSpec, isNetwork);
        }
    }

    /**
     * Notifies listeners that bytes were transferred.
     *
     * @param bytesTransferred The number of bytes transferred since the previous call to this method
     *     (or if the first call, since the transfer was started).
     */
    protected final void bytesTransferred(int bytesTransferred) {
        DataSpec dataSpec = castNonNull(this.dataSpec);
        for (int i = 0; i < listenerCount; i++) {
            listeners
                    .get(i)
                    .onBytesTransferred(/* source= */ this, dataSpec, isNetwork, bytesTransferred);
        }
    }

    /** Notifies listeners that a transfer ended. */
    protected final void transferEnded() {
        DataSpec dataSpec = castNonNull(this.dataSpec);
        for (int i = 0; i < listenerCount; i++) {
            listeners.get(i).onTransferEnd(/* source= */ this, dataSpec, isNetwork);
        }
        this.dataSpec = null;
    }
}
