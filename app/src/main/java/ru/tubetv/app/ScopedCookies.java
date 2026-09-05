package ru.tubetv.app;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Installs one router; starting a player never changes another request's cookie policy. */
final class ScopedCookies extends CookieHandler {
    static final CookieHandler NONE = new CookieHandler() {
        @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
            return Collections.emptyMap();
        }
        @Override public void put(URI uri, Map<String, List<String>> headers) { }
    };
    private static final ThreadLocal<CookieHandler> CURRENT = new ThreadLocal<>();
    private final CookieHandler fallback;

    private ScopedCookies(CookieHandler fallback) { this.fallback = fallback; }

    static synchronized void install() {
        CookieHandler current = CookieHandler.getDefault();
        if (current instanceof ScopedCookies) return;
        CookieHandler.setDefault(new ScopedCookies(current == null
                ? new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER) : current));
    }

    interface Request<T> { T run() throws IOException; }

    static <T> T with(CookieHandler cookies, Request<T> request) throws IOException {
        CookieHandler previous = CURRENT.get();
        CURRENT.set(cookies);
        try {
            return request.run();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) throws IOException {
        CookieHandler cookies = CURRENT.get();
        return (cookies == null ? fallback : cookies).get(uri, headers);
    }

    @Override public void put(URI uri, Map<String, List<String>> headers) throws IOException {
        CookieHandler cookies = CURRENT.get();
        (cookies == null ? fallback : cookies).put(uri, headers);
    }
}
