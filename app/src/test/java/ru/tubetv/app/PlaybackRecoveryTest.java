package ru.tubetv.app;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.*;

public class PlaybackRecoveryTest {
    @Test public void repeatsOnlyOnceEvenWhenTheFreshResolutionFails() {
        PlaybackRecovery recovery = new PlaybackRecovery();
        assertTrue(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null));
        assertFalse(recovery.afterResolutionError(new SocketTimeoutException()));
        assertFalse(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null));
    }

    @Test public void connectionFailureDuringInitialResolutionUsesTheSameBudget() {
        PlaybackRecovery recovery = new PlaybackRecovery();
        assertTrue(recovery.afterResolutionError(new IOException(new SocketTimeoutException())));
        assertFalse(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null));
    }

    @Test public void doesNotRestartForDecoderOrUnsupportedContentErrors() {
        PlaybackRecovery recovery = new PlaybackRecovery();
        assertFalse(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_DECODING_FAILED,
                new SocketTimeoutException()));
        assertFalse(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null));
        assertFalse(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, new IOException()));
        assertFalse(recovery.afterResolutionError(new IOException("Video is not available")));
        assertTrue(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                new IOException(new EOFException())));
    }

    @Test public void refreshesStaleMediaUrlsButDoesNotRetryRateLimits() {
        for (int status : new int[]{401, 403, 404, 408, 410, 416, 500, 502, 503, 504}) {
            assertTrue("HTTP " + status, PlaybackRecovery.retryableHttpStatus(status));
        }
        for (int status : new int[]{-1, 200, 400, 405, 429, 451, 501}) {
            assertFalse("HTTP " + status, PlaybackRecovery.retryableHttpStatus(status));
        }
    }

    @Test public void preservesOnDemandPositionAndUsesTheDefaultPositionForLiveVideo() {
        assertEquals(123_456L, PlaybackRecovery.restartPosition(123_456L, false));
        assertEquals(0L, PlaybackRecovery.restartPosition(-1L, false));
        assertEquals(0L, PlaybackRecovery.restartPosition(123_456L, true));
        PlaybackRecovery recovery = new PlaybackRecovery();
        assertTrue(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null));
        assertFalse(recovery.afterPlaybackError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null));
    }
}
