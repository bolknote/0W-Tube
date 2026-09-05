package ru.tubetv.app;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class BoundedHttpReaderTest {
    private static final class Body extends ByteArrayInputStream {
        boolean closed;
        Body(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() { closed = true; }
    }

    @Test public void acceptsExactByteLimitAndClosesBody() throws Exception {
        Body body = new Body("кино");
        assertEquals("кино", BoundedHttpReader.readText(body, 8));
        assertTrue(body.closed);
    }

    @Test public void oversizedResponseFailsWithoutReturningTruncatedJson() {
        Body body = new Body("{\"film\":true}");
        assertThrows(IOException.class, () -> BoundedHttpReader.readText(body, 12));
        assertTrue(body.closed);
    }

    @Test public void cancelledReadClosesBodyAndKeepsInterruptFlag() {
        Body body = new Body("body");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> BoundedHttpReader.read(body, 100));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(body.closed);
            assertEquals(4, body.available());
        } finally { Thread.interrupted(); }
    }
}
