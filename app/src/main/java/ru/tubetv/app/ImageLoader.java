package ru.tubetv.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ImageLoader {
    private final LruCache<String, Bitmap> cache;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2,
            0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    // Only accessed on the UI thread. Queued work never holds an Activity or View strongly.
    private final Map<String, Request> pending = new HashMap<>();

    ImageLoader() {
        int sizeKb = (int) Math.min(8 * 1024L, Runtime.getRuntime().maxMemory() / 1024L / 24L);
        cache = new LruCache<String, Bitmap>(sizeKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    void load(String url, ImageView target) {
        boolean same = url != null && url.equals(target.getTag());
        target.setTag(url);
        if (!same) target.setImageDrawable(null);
        pruneRequests();
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) { target.setImageBitmap(cached); return; }
        Request request = pending.get(url);
        if (request != null) {
            for (WeakReference<ImageView> ref : request.targets) if (ref.get() == target) return;
            request.targets.add(new WeakReference<>(target));
            return;
        }
        request = new Request(url);
        request.targets.add(new WeakReference<>(target));
        if (executor.getQueue().remainingCapacity() == 0) {
            Request dropped = (Request) executor.getQueue().poll();
            if (dropped != null) pending.remove(dropped.url);
        }
        pending.put(url, request);
        executor.execute(request);
    }

    void trimMemory() { cache.evictAll(); }

    private void pruneRequests() {
        Iterator<Request> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Request request = iterator.next();
            Iterator<WeakReference<ImageView>> targets = request.targets.iterator();
            while (targets.hasNext()) {
                ImageView target = targets.next().get();
                if (target == null || !request.url.equals(target.getTag())) targets.remove();
            }
            if (request.targets.isEmpty() && executor.remove(request)) iterator.remove();
        }
    }

    private final class Request implements Runnable {
        final String url;
        final List<WeakReference<ImageView>> targets = new ArrayList<>();
        Request(String url) { this.url = url; }

        @Override public void run() {
            HttpURLConnection connection = null;
            Bitmap result = null;
            try {
                result = cache.get(url);
                if (result == null) {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) return;
                    byte[] bytes = BoundedHttpReader.read(connection.getInputStream(), 3 * 1024 * 1024);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.RGB_565;
                    options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 360, 210);
                    result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    if (result != null) cache.put(url, result);
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
                Bitmap bitmap = result;
                ui.post(() -> {
                    if (pending.get(url) != this) return;
                    pending.remove(url);
                    if (bitmap == null) return;
                    for (WeakReference<ImageView> ref : targets) {
                        ImageView target = ref.get();
                        if (target != null && url.equals(target.getTag())) target.setImageBitmap(bitmap);
                    }
                });
            }
        }
    }

    static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2;
        // Panoramic and portrait images need a ceiling even if their short side fits.
        long maxPixels = (long) targetWidth * targetHeight * 4;
        while ((long) (width / sample) * (height / sample) > maxPixels) sample *= 2;
        return sample;
    }
}
