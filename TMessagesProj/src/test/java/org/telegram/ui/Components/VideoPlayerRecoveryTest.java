package org.telegram.ui.Components;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, application = Application.class,
        shadows = VideoPlayerRecoveryTest.ShadowPlayerBuilder.class)
public class VideoPlayerRecoveryTest {
    private PlayerState previous;
    private PlayerState replacement;
    private VideoPlayer video;

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler = new Handler(Looper.getMainLooper());
        previous = new PlayerState();
        previous.position = 42000;
        previous.volume = 0.35f;
        previous.parameters = new PlaybackParameters(1.5f);
        replacement = new PlayerState();
        ShadowPlayerBuilder.previous = previous;
        ShadowPlayerBuilder.next = replacement;
        video = new VideoPlayer(false, false);
        video.player = previous.proxy();
        ReflectionHelpers.setField(video, "videoUri", Uri.parse("tg://video"));
        ReflectionHelpers.setField(video, "videoType", "other");
    }

    @Test
    public void releasesPreviousDecoderBeforeBuildingAndPreservesPlaybackState() {
        previous.playWhenReady = true;
        assertTrue(video.retryWithSoftwareDecoder());
        assertTrue(previous.released);
        assertEquals(42000, replacement.position);
        assertTrue(replacement.playWhenReady);
        assertEquals(0.35f, replacement.volume, 0.001f);
        assertEquals(1.5f, replacement.parameters.speed, 0.001f);
        assertTrue(ReflectionHelpers.getField(video, "preferSoftwareDecoder"));
        assertFalse(video.retryWithSoftwareDecoder());
        video.releasePlayer(false);
    }

    @Test
    public void pausedVideoStaysPausedAfterDecoderFallback() {
        previous.playWhenReady = false;
        assertTrue(video.retryWithSoftwareDecoder());
        assertFalse(replacement.playWhenReady);
        assertEquals(42000, replacement.position);
        video.releasePlayer(false);
    }

    @Test
    public void decoderErrorRecoversWithoutTextureView() {
        video.onPlayerError(new PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(previous.released);
        assertEquals(42000, replacement.position);
        assertFalse(video.retryWithSoftwareDecoder());
        video.releasePlayer(false);
    }

    @Test
    public void queuedErrorCannotRecreateReleasedPlayer() throws Exception {
        Thread callback = new Thread(() -> video.onPlayerError(
                new PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED)));
        callback.start();
        callback.join(5000);
        assertFalse(callback.isAlive());
        video.releasePlayer(false);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, replacement.prepareCount);
        assertNull(video.player);
    }

    @Test
    public void interruptedSourceIsPreparedOnlyWhenPlayIsRequested() {
        previous.playWhenReady = true;
        video.reprepareOnNextPlay(42000);
        assertFalse(previous.playWhenReady);
        assertEquals(0, previous.prepareCount);
        video.play();
        assertEquals(1, previous.prepareCount);
        assertEquals(42000, previous.position);
        assertTrue(previous.playWhenReady);
        video.releasePlayer(false);
    }

    @Test
    public void pauseDuringSourceCleanupKeepsPlaybackPaused() {
        previous.playWhenReady = true;
        video.stopForRecovery();
        assertTrue(video.isPlaying());
        video.pause();
        assertFalse(video.isPlaying());
        video.releasePlayer(false);
    }

    @Test
    public void resumeThroughSetPlayWhenReadyAndSeekUsesLatestPosition() {
        video.reprepareOnNextPlay(42000);
        video.seekTo(48000);
        video.setPlayWhenReady(true);
        assertEquals(1, previous.prepareCount);
        assertEquals(48000, previous.position);
        video.releasePlayer(false);
    }

    @Test
    public void fallbackEnablesExtensionPreferenceAndDecoderFallback() {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(RuntimeEnvironment.getApplication());
        VideoPlayer.configureRenderersFactory(factory, true);
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                (int) ReflectionHelpers.getField(factory, "extensionRendererMode"));
        assertTrue(ReflectionHelpers.getField(factory, "enableDecoderFallback"));
    }

    @Implements(ExoPlayer.Builder.class)
    public static class ShadowPlayerBuilder {
        static PlayerState previous;
        static PlayerState next;

        @Implementation
        protected ExoPlayer build() {
            assertTrue("Old MediaCodec must be released before building its replacement", previous.released);
            return next.proxy();
        }
    }

    private static class PlayerState implements InvocationHandler {
        boolean released;
        boolean playWhenReady;
        long position;
        float volume = 1;
        int prepareCount;
        PlaybackParameters parameters = PlaybackParameters.DEFAULT;

        ExoPlayer proxy() {
            return (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class[]{ExoPlayer.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "release": released = true; return null;
                case "getPlayWhenReady": case "isPlaying": return playWhenReady;
                case "setPlayWhenReady": playWhenReady = (boolean) args[0]; return null;
                case "getCurrentPosition": return position;
                case "getDuration": return 120000L;
                case "seekTo": position = (long) args[0]; return null;
                case "getVolume": return volume;
                case "setVolume": volume = (float) args[0]; return null;
                case "getPlaybackParameters": return parameters;
                case "setPlaybackParameters": parameters = (PlaybackParameters) args[0]; return null;
                case "getAudioAttributes": return AudioAttributes.DEFAULT;
                case "getApplicationLooper": return Looper.getMainLooper();
                case "prepare": prepareCount++; return null;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "FakeExoPlayer";
                default:
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
            }
        }
    }
}
