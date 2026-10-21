package ru.tubetv.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercise real HTTP requests, not just the header-map builder. */
public final class PeerTubeHttpTest {
    private static final String PAGE = "https://films.example/videos/watch/test";
    private static final String MANIFEST = "#EXTM3U\n";
    private final List<Map<String, String>> requests = new ArrayList<>();
    private CookieHandler original;
    private CookieManager fallback;
    private ServerSocket server;
    private Thread worker;
    private final AtomicReference<Throwable> serverError = new AtomicReference<>();
    private String base;
    private volatile int status = 200;

    @Before public void setUp() throws Exception {
        original = CookieHandler.getDefault();
        fallback = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(fallback);
        ScopedCookies.install();
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        base = "http://127.0.0.1:" + server.getLocalPort();
        fallback.put(URI.create(base + "/"), Collections.singletonMap("Set-Cookie",
                Collections.singletonList("session=other-service; Path=/")));
        assertTrue(fallback.get(URI.create(base + "/"), new LinkedHashMap<>())
                .toString().contains("other-service"));
        worker = new Thread(this::serve, "peertube-test-http");
        worker.setDaemon(true);
        worker.start();
    }

    @After public void tearDown() throws Exception {
        try {
            if (server != null) server.close();
            if (worker != null) worker.join(2000);
            assertNull(serverError.get());
        } finally {
            CookieHandler.setDefault(original);
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000);
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.ISO_8859_1));
                String request = input.readLine();
                if (request == null) continue;
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) headers.put(line.substring(0, colon), line.substring(colon + 1).trim());
                }
                synchronized (requests) { requests.add(headers); }
                boolean redirect = request.startsWith("GET /redirect ");
                String body = redirect ? "" : MANIFEST;
                String response = "HTTP/1.1 " + (redirect ? 302 : status) + " Test\r\n"
                        + "Content-Length: " + body.length() + "\r\nConnection: close\r\n"
                        + "Set-Cookie: probe=ignored; Path=/\r\n"
                        + (redirect ? "Location: " + base + "/master.m3u8\r\n" : "")
                        + "\r\n" + body;
                socket.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException error) {
                if (!server.isClosed()) serverError.compareAndSet(null, error);
                return;
            }
        }
    }

    @Test public void manifestRequestSendsSharedHeadersWithoutSessionCookies() throws Exception {
        assertEquals(MANIFEST, PeerTubeClient.getText(base + "/master.m3u8", 1024,
                Long.MAX_VALUE, PeerTubeClient.mediaHeaders(PAGE)));
        assertMediaRequests(1);
        assertEquals(1, fallback.getCookieStore().getCookies().size());
        assertEquals("session", fallback.getCookieStore().getCookies().get(0).getName());
        assertTrue(CookieHandler.getDefault().get(URI.create(base + "/"), new LinkedHashMap<>())
                .toString().contains("other-service"));
    }

    @Test public void redirectKeepsTheSameMediaHeaders() throws Exception {
        assertEquals(MANIFEST, PeerTubeClient.getText(base + "/redirect", 1024,
                Long.MAX_VALUE, PeerTubeClient.mediaHeaders(PAGE)));
        assertMediaRequests(2);
    }

    @Test public void failedRequestRestoresCookieScope() throws Exception {
        status = 403;
        IOException error = assertThrows(IOException.class, () -> PeerTubeClient.getText(
                base + "/master.m3u8", 1024, Long.MAX_VALUE, PeerTubeClient.mediaHeaders(PAGE)));
        assertEquals("PeerTube: HTTP 403", error.getMessage());
        assertMediaRequests(1);
        assertTrue(CookieHandler.getDefault().get(URI.create(base + "/"), new LinkedHashMap<>())
                .toString().contains("other-service"));
    }

    @Test public void bodyLimitStillRejectsOversizedManifest() {
        assertThrows(IOException.class, () -> PeerTubeClient.getText(base + "/master.m3u8", 4,
                Long.MAX_VALUE, PeerTubeClient.mediaHeaders(PAGE)));
        assertMediaRequests(1);
    }

    @Test public void expiredDeadlineDoesNotMakeARequest() {
        assertThrows(InterruptedIOException.class, () -> PeerTubeClient.getText(base + "/master.m3u8",
                1024, System.nanoTime() - 1_000_000L, PeerTubeClient.mediaHeaders(PAGE)));
        synchronized (requests) { assertTrue(requests.isEmpty()); }
    }

    @Test public void apiRequestKeepsItsOwnUserAgentAndNoReferer() throws Exception {
        PeerTubeClient.getText(base + "/api", 1024, Long.MAX_VALUE, PeerTubeClient.apiHeaders());
        synchronized (requests) {
            assertEquals(1, requests.size());
            Map<String, String> request = requests.get(0);
            assertEquals("0W-Tube/" + BuildConfig.VERSION_NAME + " PeerTube", request.get("User-Agent"));
            assertEquals(PeerTubeClient.apiHeaders().get("Accept"), request.get("Accept"));
            assertNull(request.get("Referer"));
            assertNull(request.get("Cookie"));
        }
    }

    private void assertMediaRequests(int count) {
        synchronized (requests) {
            assertEquals(count, requests.size());
            for (Map<String, String> request : requests) {
                assertEquals(PeerTubeClient.MEDIA_USER_AGENT, request.get("User-Agent"));
                assertEquals("*/*", request.get("Accept"));
                assertEquals(PAGE, request.get("Referer"));
                assertNull(request.get("Cookie"));
            }
        }
    }
}
