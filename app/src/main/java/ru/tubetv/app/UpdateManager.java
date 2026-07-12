package ru.tubetv.app;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateManager {
    private static final String LATEST_URL =
            "https://github.com/bolknote/0W-Tube/releases/latest";
    private static final String USER_AGENT = "0W-Tube/0.5.9 AndroidTV";
    private static final int TIMEOUT_MS = 10_000;
    private static final long MAX_APK_SIZE = 150L * 1024L * 1024L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECK_STARTED = new AtomicBoolean();

    interface CheckCallback { void onUpdate(Release release); }
    interface DownloadCallback {
        void onProgress(int percent);
        void onReady(File apk);
        void onError(String message);
    }

    static final class Release {
        final String version;
        final String apkUrl;
        final String digest;
        final String checksumUrl;
        final long size;

        Release(String version, String apkUrl, String digest, String checksumUrl, long size) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.digest = digest;
            this.checksumUrl = checksumUrl;
            this.size = size;
        }
    }

    static void check(Context context, CheckCallback callback) {
        if (!CHECK_STARTED.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection latest = (HttpURLConnection) new URL(LATEST_URL).openConnection();
                latest.setInstanceFollowRedirects(false);
                latest.setRequestMethod("HEAD");
                latest.setConnectTimeout(TIMEOUT_MS);
                latest.setReadTimeout(TIMEOUT_MS);
                latest.setRequestProperty("User-Agent", USER_AGENT);
                int code = latest.getResponseCode();
                String location = latest.getHeaderField("Location");
                latest.disconnect();
                if (code < 300 || code >= 400 || location == null) return;
                int tag = location.indexOf("/releases/tag/");
                if (tag < 0) return;
                String version = cleanVersion(location.substring(tag + "/releases/tag/".length()));
                int suffix = version.indexOf('?');
                if (suffix >= 0) version = version.substring(0, suffix);
                if (!version.matches("[0-9]+(?:\\.[0-9]+){1,3}")) return;
                if (version.isEmpty() || compareVersions(version, currentVersion(app)) <= 0) return;
                String base = "https://github.com/bolknote/0W-Tube/releases/download/" + version
                        + "/0W-Tube-" + version + ".apk";
                callback.onUpdate(new Release(version, base, null, base + ".sha256", 0L));
            } catch (Exception ignored) {
                // An update check must never delay startup or show a network error.
            }
        });
    }

    static void download(Context context, Release release, DownloadCallback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File partial = null;
            try {
                String expected = release.digest;
                if ((expected == null || expected.isEmpty()) && release.checksumUrl != null) {
                    String checksum = readText(release.checksumUrl, 4096).trim();
                    int separator = checksum.indexOf(' ');
                    expected = normalizeDigest(separator < 0 ? checksum : checksum.substring(0, separator));
                }
                if (expected == null || expected.length() != 64) {
                    throw new Exception("GitHub не отдал контрольную сумму APK");
                }
                File directory = new File(app.getCacheDir(), "updates");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new Exception("Не удалось создать каталог обновления");
                }
                partial = new File(directory, "0W-Tube-" + release.version + ".apk.part");
                File ready = new File(directory, "0W-Tube-" + release.version + ".apk");
                if (partial.exists()) partial.delete();

                HttpURLConnection connection = open(release.apkUrl);
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                long total = release.size > 0 ? release.size : connection.getContentLength();
                if (total > MAX_APK_SIZE) throw new Exception("APK обновления слишком большой");
                long loaded = 0;
                int reported = -1;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        loaded += count;
                        if (loaded > MAX_APK_SIZE) throw new Exception("APK обновления слишком большой");
                        output.write(buffer, 0, count);
                        sha256.update(buffer, 0, count);
                        int percent = total > 0 ? (int) Math.min(100L, loaded * 100L / total) : -1;
                        if (percent != reported) {
                            reported = percent;
                            callback.onProgress(percent);
                        }
                    }
                } finally {
                    connection.disconnect();
                }
                String actual = hex(sha256.digest());
                if (!expected.equalsIgnoreCase(actual)) throw new Exception("SHA-256 обновления не совпадает");
                PackageInfo archive = app.getPackageManager().getPackageArchiveInfo(partial.getAbsolutePath(), 0);
                if (archive == null || !app.getPackageName().equals(archive.packageName)
                        || !release.version.equals(cleanVersion(archive.versionName))) {
                    throw new Exception("Загружен APK другого приложения или версии");
                }
                if (ready.exists() && !ready.delete()) throw new Exception("Не удалось заменить старое обновление");
                if (!partial.renameTo(ready)) throw new Exception("Не удалось подготовить APK обновления");
                callback.onReady(ready);
            } catch (Exception error) {
                if (partial != null) partial.delete();
                String message = error.getMessage();
                callback.onError(message == null ? "Не удалось скачать обновление" : message);
            }
        });
    }

    private static String currentVersion(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return cleanVersion(info.versionName);
    }

    private static int compareVersions(String left, String right) {
        String[] a = cleanVersion(left).split("\\.");
        String[] b = cleanVersion(right).split("\\.");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int av = i < a.length ? number(a[i]) : 0;
            int bv = i < b.length ? number(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int number(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        try { return end == 0 ? 0 : Integer.parseInt(value.substring(0, end)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String cleanVersion(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.startsWith("v") || value.startsWith("V") ? value.substring(1) : value;
    }

    private static String normalizeDigest(String value) {
        if (value == null) return null;
        value = value.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("sha256:") ? value.substring(7) : value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String readText(String address, int limit) throws Exception {
        HttpURLConnection connection = open(address);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new Exception("Слишком большой ответ сервера обновлений");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/octet-stream,text/plain,*/*");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new Exception("GitHub: HTTP " + code);
        }
        return connection;
    }

    private UpdateManager() { }
}
