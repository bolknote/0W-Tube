package ru.tubetv.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ImageLoader {
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    ImageLoader() {
        int sizeKb = (int) Math.min(8 * 1024L, Runtime.getRuntime().maxMemory() / 1024L / 24L);
        cache = new LruCache<String, Bitmap>(sizeKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    void load(String url, ImageView target) {
        target.setTag(url);
        target.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoInput(true);
                connection.connect();
                byte[] bytes = readLimited(connection, 3 * 1024 * 1024);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 360, 210);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap != null) {
                    cache.put(url, bitmap);
                    target.post(() -> {
                        if (url.equals(target.getTag())) target.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static byte[] readLimited(HttpURLConnection connection, int limit) throws Exception {
        java.io.InputStream input = connection.getInputStream();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1 && total < limit) {
            int count = Math.min(read, limit - total);
            output.write(buffer, 0, count);
            total += count;
        }
        input.close();
        return output.toByteArray();
    }

    private static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }
}
