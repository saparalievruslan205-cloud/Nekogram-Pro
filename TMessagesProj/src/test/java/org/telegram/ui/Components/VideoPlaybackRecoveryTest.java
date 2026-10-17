package org.telegram.ui.Components;

import android.app.Application;

import androidx.media3.common.ParserException;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class)
public class VideoPlaybackRecoveryTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void streamRecoveryIsLimitedToOneAttemptPerSession() {
        VideoPlaybackRecovery recovery = new VideoPlaybackRecovery();
        assertTrue(recovery.tryStreamRecovery());
        assertFalse(recovery.tryStreamRecovery());
        assertFalse(recovery.tryStreamRecovery());
        assertTrue(new VideoPlaybackRecovery().tryStreamRecovery());
    }

    @Test
    public void recognizesNestedInterruptionsCancellationAndTimeouts() {
        assertTrue(VideoPlaybackRecovery.isStreamInterruption(new IOException(new InterruptedIOException())));
        assertTrue(VideoPlaybackRecovery.isStreamInterruption(new IOException(new SocketTimeoutException())));
        assertTrue(VideoPlaybackRecovery.isStreamInterruption(new CancellationException()));
        assertTrue(VideoPlaybackRecovery.isStreamInterruption(new PlaybackException("timeout", null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)));
        assertFalse(VideoPlaybackRecovery.isContainerError(new SocketTimeoutException()));
        assertFalse(VideoPlaybackRecovery.isStreamInterruption(new EOFException()));
    }

    @Test
    public void recognizesContainerErrorsWithoutTreatingThemAsDecoderFailures() {
        assertTrue(VideoPlaybackRecovery.isContainerError(new IOException(new EOFException())));
        assertTrue(VideoPlaybackRecovery.isContainerError(ParserException.createForMalformedContainer("broken", null)));
        assertTrue(VideoPlaybackRecovery.isContainerError(new UnrecognizedInputFormatException("broken", android.net.Uri.EMPTY)));
        assertFalse(VideoPlaybackRecovery.isDecoderError(ParserException.createForMalformedContainer("broken", null)));
    }

    @Test
    public void decoderPlaybackErrorsTriggerSoftwareFallback() {
        assertTrue(VideoPlaybackRecovery.isDecoderError(new PlaybackException("decoder", null,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)));
        assertTrue(VideoPlaybackRecovery.isDecoderError(new IOException(new PlaybackException("decoder", null,
                PlaybackException.ERROR_CODE_DECODING_FAILED))));
        assertFalse(VideoPlaybackRecovery.isDecoderError(new IOException("network")));
    }

    @Test
    public void externalPlayerNeedsAnExistingNonemptyPlainFile() throws Exception {
        assertFalse(VideoPlaybackRecovery.canOpenExternally(null));
        assertFalse(VideoPlaybackRecovery.canOpenExternally(temporaryFolder.getRoot()));
        assertFalse(VideoPlaybackRecovery.canOpenExternally(new File(temporaryFolder.getRoot(), "missing.mp4")));
        File file = temporaryFolder.newFile("video.mp4");
        assertFalse(VideoPlaybackRecovery.canOpenExternally(file));
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.write(1);
        }
        assertTrue(VideoPlaybackRecovery.canOpenExternally(file));
        for (String name : new String[]{"video.mp4.enc", "1_42.temp", "1_42_64.preload"}) {
            File temporary = temporaryFolder.newFile(name);
            try (RandomAccessFile output = new RandomAccessFile(temporary, "rw")) {
                output.write(1);
            }
            assertFalse(VideoPlaybackRecovery.canOpenExternally(temporary));
        }
    }
}
