package org.telegram.ui.Components;

import android.media.MediaCodec;

import androidx.media3.common.ParserException;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer;

import java.io.EOFException;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.concurrent.CancellationException;

/** Error classification and retry budget for one video viewing session. */
public final class VideoPlaybackRecovery {
    private boolean triedStreamRecovery;

    public boolean tryStreamRecovery() {
        if (triedStreamRecovery) {
            return false;
        }
        triedStreamRecovery = true;
        return true;
    }

    public static boolean isDecoderError(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause()) {
            if (error instanceof MediaCodecRenderer.DecoderInitializationException ||
                    error instanceof MediaCodecDecoderException || error instanceof MediaCodec.CodecException) {
                return true;
            }
            if (error instanceof PlaybackException) {
                int code = ((PlaybackException) error).errorCode;
                if (code >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                        code <= PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
                    return true;
                }
            }
            if (error instanceof ExoPlaybackException) {
                ExoPlaybackException exoError = (ExoPlaybackException) error;
                if (exoError.type == ExoPlaybackException.TYPE_RENDERER &&
                        exoError.rendererName != null && exoError.rendererName.contains("MediaCodec")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isStreamInterruption(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause()) {
            if (error instanceof InterruptedIOException || error instanceof CancellationException) {
                return true;
            }
            if (error instanceof PlaybackException) {
                int code = ((PlaybackException) error).errorCode;
                if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isContainerError(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause()) {
            if (error instanceof ParserException || error instanceof EOFException) {
                return true;
            }
        }
        return false;
    }

    public static boolean canOpenExternally(File file) {
        return file != null && file.isFile() && file.length() > 0 &&
                !file.getName().endsWith(".enc") && !file.getName().endsWith(".temp") &&
                !file.getName().endsWith(".preload");
    }
}
