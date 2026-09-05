package ru.tubetv.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Limits decoded response bodies, including chunked and compressed HTTP responses. */
final class BoundedHttpReader {
    static final int JSON_LIMIT = 2 * 1024 * 1024;
    static final int PAGE_LIMIT = 4 * 1024 * 1024;
    static final int MANIFEST_LIMIT = 2 * 1024 * 1024;

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Запрос отменён");
    }

    static byte[] read(InputStream input, int limit) throws IOException {
        try (InputStream body = input) {
            checkInterrupted();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
            byte[] buffer = new byte[8192];
            while (true) {
                checkInterrupted();
                int count = body.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1));
                if (count == -1) return output.toByteArray();
                checkInterrupted();
                if (count > limit - output.size()) throw new IOException("Ответ сервера слишком большой");
                output.write(buffer, 0, count);
            }
        }
    }

    static String readText(InputStream input, int limit) throws IOException {
        return readText(input, limit, StandardCharsets.UTF_8);
    }

    static String readText(InputStream input, int limit, Charset charset) throws IOException {
        return new String(read(input, limit), charset);
    }

    private BoundedHttpReader() { }
}
