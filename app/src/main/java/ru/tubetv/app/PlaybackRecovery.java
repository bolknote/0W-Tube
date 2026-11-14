package ru.tubetv.app;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** One fresh source resolution per opening, shared by the resolver and the player. */
@OptIn(markerClass = UnstableApi.class)
final class PlaybackRecovery {
    private boolean attempted;

    boolean afterPlaybackError(int code, Throwable cause) {
        if (attempted) return false;
        switch (code) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return claim(true);
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return claim(retryableHttpStatus(httpStatus(cause)));
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED:
                return claim(networkFailure(cause));
            default:
                return false;
        }
    }

    boolean afterResolutionError(Throwable cause) {
        return !attempted && claim(networkFailure(cause));
    }

    private boolean claim(boolean recoverable) {
        if (recoverable) attempted = true;
        return recoverable;
    }

    static boolean retryableHttpStatus(int status) {
        // An expired CDN URL can be refused or disappear. Resolve the same public
        // video again; do not retry rate limits or change its access requirements.
        switch (status) {
            case 401: case 403: case 404: case 408: case 410: case 416:
            case 500: case 502: case 503: case 504:
                return true;
            default:
                return false;
        }
    }

    static int httpStatus(Throwable cause) {
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
            }
        }
        return -1;
    }

    private static boolean networkFailure(Throwable cause) {
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketException || cause instanceof UnknownHostException
                    || cause instanceof SocketTimeoutException || cause instanceof EOFException) return true;
        }
        return false;
    }

    static long restartPosition(long currentPosition, boolean live) {
        return live ? 0L : Math.max(0L, currentPosition);
    }
}
