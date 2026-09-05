package ru.tubetv.app;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ScopedCookiesTest {
    private static final URI URL = URI.create("https://example.org/video");
    private CookieHandler original;
    @Before public void setUp() {
        original = CookieHandler.getDefault();
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        ScopedCookies.install();
    }
    @After public void tearDown() { CookieHandler.setDefault(original); }

    private String cookies() throws IOException {
        return CookieHandler.getDefault().get(URL, Collections.emptyMap()).toString();
    }
    private void store(String value) throws IOException {
        CookieHandler.getDefault().put(URL, Collections.singletonMap("Set-Cookie",
                Collections.singletonList("session=" + value + "; Path=/")));
    }

    @Test public void playerWithoutCookiesCannotReplaceSearchCookiePolicy() throws Exception {
        CookieHandler router = CookieHandler.getDefault();
        store("search");
        ScopedCookies.with(ScopedCookies.NONE, () -> {
            assertFalse(cookies().contains("search"));
            store("ignored");
            return null;
        });
        assertSame(router, CookieHandler.getDefault());
        assertTrue(cookies().contains("search"));
        assertFalse(cookies().contains("ignored"));
    }

    @Test(timeout = 5000) public void parallelPlayersDoNotShareCookiesEvenForSameHost() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier ready = new CyclicBarrier(2);
        try {
            Future<String> first = pool.submit(() -> isolated("first", ready));
            Future<String> second = pool.submit(() -> isolated("second", ready));
            assertTrue(first.get().contains("first"));
            assertFalse(first.get().contains("second"));
            assertTrue(second.get().contains("second"));
            assertFalse(second.get().contains("first"));
            assertFalse(cookies().contains("session"));
        } finally { pool.shutdownNow(); }
    }

    private String isolated(String value, CyclicBarrier ready) throws Exception {
        return ScopedCookies.with(new CookieManager(null, CookiePolicy.ACCEPT_ALL), () -> {
            store(value);
            try { ready.await(2, TimeUnit.SECONDS); }
            catch (Exception error) { throw new IOException(error); }
            return cookies();
        });
    }

    @Test public void failingRequestRestoresPreviousScope() throws Exception {
        store("search");
        assertThrows(IOException.class, () -> ScopedCookies.with(ScopedCookies.NONE, () -> {
            throw new IOException("network failed");
        }));
        assertTrue(cookies().contains("search"));
    }
}
