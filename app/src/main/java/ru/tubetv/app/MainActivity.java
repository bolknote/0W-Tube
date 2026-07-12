package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private final SearchClient searchClient = new SearchClient();
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final AtomicInteger generation = new AtomicInteger();
    private final List<VideoItem> allItems = new ArrayList<>();
    private final List<VideoItem> items = new ArrayList<>();
    private final Set<String> qualityRequested = new HashSet<>();
    private static final String[] FILTER_LABELS = {"Любое", "720+", "1080+", "1440+", "2160 / 4K"};
    private static final int[] FILTER_WIDTHS = {0, 1280, 1920, 2560, 3840};
    private ImageLoader imageLoader;
    private VideoAdapter adapter;
    private GridView grid;
    private EditText query;
    private TextView status;
    private final List<Button> filterButtons = new ArrayList<>();
    private int selectedFilter;
    private final List<Future<?>> activeSearches = new ArrayList<>();
    private boolean rutubeDone;
    private boolean vkDone;
    private boolean dzenDone;
    private int rutubeCount;
    private int vkCount;
    private int dzenCount;
    private String rutubeError;
    private String vkError;
    private String dzenError;
    private String currentSearchQuery = "";
    private int qualityJobs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        imageLoader = new ImageLoader();
        setContentView(createContent());
        showPreviousCrash();
        restoreState();
    }

    private void showPreviousCrash() {
        String crash = CrashLog.consume(this);
        if (crash == null) crash = ExitDiagnostics.consume(this);
        if (crash == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Ошибка предыдущего запуска")
                .setMessage(crash)
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(36), dp(24), dp(36), dp(20));
        root.setBackgroundColor(Color.rgb(16, 18, 24));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(70), dp(60)));

        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(Color.rgb(160, 166, 178));
        query.setHint("Введите запрос для поиска в RUTUBE, VK Video и Дзене");
        query.setTextSize(20);
        query.setBackgroundResource(R.drawable.search_field_background);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search(); return true;
            }
            return false;
        });
        bar.addView(query, new LinearLayout.LayoutParams(0, dp(40), 1f));

        LinearLayout searchColumn = new LinearLayout(this);
        searchColumn.setOrientation(LinearLayout.VERTICAL);
        searchColumn.setGravity(Gravity.CENTER);

        Button search = new Button(this);
        search.setText("Найти");
        search.setTextColor(Color.WHITE);
        search.setBackgroundResource(R.drawable.search_button_background);
        search.setOnClickListener(v -> search());
        searchColumn.addView(search, new LinearLayout.LayoutParams(-1, dp(40)));

        status = text("", 11, Color.rgb(169, 176, 190));
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.INVISIBLE);
        searchColumn.addView(status, new LinearLayout.LayoutParams(-1, dp(18)));
        bar.addView(searchColumn, new LinearLayout.LayoutParams(dp(120), dp(60)));

        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(64)));

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(dp(70), dp(4), 0, dp(4));
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            final int index = i;
            Button chip = new Button(this);
            chip.setText(FILTER_LABELS[i]);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(13);
            chip.setAllCaps(false);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setBackgroundResource(R.drawable.filter_chip_background);
            chip.setOnClickListener(v -> selectFilter(index, true));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, dp(28));
            chipParams.setMargins(0, 0, dp(10), 0);
            filters.addView(chip, chipParams);
            filterButtons.add(chip);
        }
        root.addView(filters, new LinearLayout.LayoutParams(-1, dp(36)));

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setHorizontalSpacing(dp(14));
        grid.setVerticalSpacing(dp(14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(4), 0, dp(20));
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setFocusable(true);
        grid.setSelector(getDrawable(ru.tubetv.app.R.drawable.tv_grid_selector));
        grid.setDrawSelectorOnTop(true);
        adapter = new VideoAdapter(this);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> play(items.get(position)));
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private void search() {
        String value = query.getText().toString().trim();
        if (value.length() < 2) return;
        int current = generation.incrementAndGet();
        currentSearchQuery = value;
        for (Future<?> search : activeSearches) search.cancel(true);
        activeSearches.clear();
        allItems.clear();
        items.clear();
        qualityRequested.clear();
        qualityJobs = 0;
        adapter.notifyDataSetChanged();
        StateStore.saveSearch(this, value, selectedFilter);
        rutubeDone = false;
        vkDone = false;
        dzenDone = false;
        rutubeCount = 0;
        vkCount = 0;
        dzenCount = 0;
        rutubeError = null;
        vkError = null;
        dzenError = null;
        updateSearchStatus();
        activeSearches.add(network.submit(
                () -> runSource(current, "RUTUBE", () -> searchClient.searchRutube(value, 0))));
        activeSearches.add(network.submit(
                () -> runSource(current, "VK Video", () -> searchClient.searchVk(value, 0))));
        activeSearches.add(network.submit(
                () -> runSource(current, "Дзен", () -> searchClient.searchDzen(value, 0))));
    }

    private void selectFilter(int index, boolean rerunSearch) {
        selectedFilter = Math.max(0, Math.min(index, FILTER_LABELS.length - 1));
        for (int i = 0; i < filterButtons.size(); i++) filterButtons.get(i).setSelected(i == selectedFilter);
        if (rerunSearch) StateStore.saveSearch(this, query == null ? "" : query.getText().toString(), selectedFilter);
        if (rerunSearch && query.getText().toString().trim().length() >= 2) {
            refreshDisplayedItems(false);
            if (selectedFilter > 0) requestMissingQualities(generation.get(), new ArrayList<>(allItems));
        }
    }

    private void runSource(int current, String source, SearchCall call) {
        try {
            List<VideoItem> found = call.run();
            runOnUiThread(() -> {
                if (current != generation.get()) return;
                allItems.addAll(found);
                finishSource(source, found.size(), null);
                refreshDisplayedItems(true);
                requestMissingQualities(current, found);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (current == generation.get()) finishSource(source, 0, safeMessage(e));
            });
        }
    }

    private void finishSource(String source, int count, String error) {
        if ("RUTUBE".equals(source)) {
            rutubeDone = true;
            rutubeCount = count;
            rutubeError = error;
        } else if ("VK Video".equals(source)) {
            vkDone = true;
            vkCount = count;
            vkError = error;
        } else {
            dzenDone = true;
            dzenCount = count;
            dzenError = error;
        }
        updateSearchStatus();
    }

    private void updateSearchStatus() {
        status.setVisibility(View.VISIBLE);
        boolean working = !rutubeDone || !vkDone || !dzenDone || qualityJobs > 0;
        status.setText("Найдено: " + items.size() + (working ? "…" : ""));
    }

    private void refreshDisplayedItems(boolean selectFirst) {
        boolean hadItems = !items.isEmpty();
        boolean gridHadFocus = grid.hasFocus();
        String selectedKey = selectedItemKey();
        int minWidth = FILTER_WIDTHS[selectedFilter];
        items.clear();
        for (VideoItem item : allItems) {
            if (minWidth == 0 || item.maxWidth >= minWidth) items.add(item);
        }
        Collections.sort(items, VideoRanker.comparator(currentSearchQuery));
        adapter.notifyDataSetChanged();
        restoreGridSelection(selectedKey, gridHadFocus, selectFirst && !hadItems && !items.isEmpty());
        updateSearchStatus();
    }

    private void requestMissingQualities(int current, List<VideoItem> candidates) {
        List<VideoItem> regular = new ArrayList<>();
        List<VideoItem> dzen = new ArrayList<>();
        for (VideoItem item : candidates) {
            String key = item.stableKey();
            if (item.maxWidth != 0 || !qualityRequested.add(key)) continue;
            ("ДЗЕН".equals(item.source) ? dzen : regular).add(item);
        }
        submitQualityJob(current, regular, 4);
        submitQualityJob(current, dzen, 2);
    }

    private void submitQualityJob(int current, List<VideoItem> missing, int parallel) {
        if (missing.isEmpty()) return;
        qualityJobs++;
        updateSearchStatus();
        activeSearches.add(network.submit(() -> {
            List<VideoItem> inspected = SearchClient.inspectQualities(missing, parallel);
            runOnUiThread(() -> {
                if (current != generation.get()) return;
                Map<String, VideoItem> byKey = new HashMap<>();
                for (VideoItem item : inspected) byKey.put(item.stableKey(), item);
                for (int i = 0; i < allItems.size(); i++) {
                    VideoItem replacement = byKey.get(allItems.get(i).stableKey());
                    if (replacement != null) allItems.set(i, replacement);
                }
                qualityJobs = Math.max(0, qualityJobs - 1);
                refreshDisplayedItems(false);
            });
        }));
    }

    private String selectedItemKey() {
        int position = grid.getSelectedItemPosition();
        if (position < 0 || position >= items.size()) return null;
        return items.get(position).stableKey();
    }

    private void restoreGridSelection(String selectedKey, boolean gridHadFocus, boolean selectFirst) {
        int position = -1;
        if (selectedKey != null) {
            for (int i = 0; i < items.size(); i++) {
                if (selectedKey.equals(items.get(i).stableKey())) {
                    position = i;
                    break;
                }
            }
        }
        if (position < 0 && (selectFirst || gridHadFocus) && !items.isEmpty()) position = 0;
        if (position < 0) return;
        final String restoredKey = selectedKey;
        final int fallbackPosition = position;
        grid.setSelection(fallbackPosition);
        grid.post(() -> {
            int restoredPosition = fallbackPosition;
            if (restoredKey != null) {
                for (int i = 0; i < items.size(); i++) {
                    if (restoredKey.equals(items.get(i).stableKey())) {
                        restoredPosition = i;
                        break;
                    }
                }
            }
            if (restoredPosition >= items.size()) return;
            grid.setSelection(restoredPosition);
            if (gridHadFocus || selectFirst) grid.requestFocus();
        });
    }

    private void play(VideoItem item) {
        long position = WatchProgressStore.get(this, item).positionMs;
        StateStore.savePlayer(this, item, position);
        launchPlayer(item, position, true);
    }

    private void launchPlayer(VideoItem item, long position, boolean autoPlay) {
        try {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("source", item.source);
            intent.putExtra("title", item.title);
            intent.putExtra("resolver_url", item.playUrl);
            intent.putExtra("page_url", item.pageUrl);
            intent.putExtra("duration_ms", item.durationMs);
            intent.putExtra("resume_position", position);
            intent.putExtra("auto_play", autoPlay);
            startActivity(intent);
        } catch (Throwable error) {
            CrashLog.save(getApplicationContext(), error);
            new AlertDialog.Builder(this).setTitle("Не удалось открыть плеер")
                    .setMessage(error.toString()).setPositiveButton("Закрыть", null).show();
        }
    }

    private void restoreState() {
        selectedFilter = StateStore.filter(this);
        selectFilter(selectedFilter, false);
        String lastQuery = StateStore.query(this);
        query.setText(lastQuery);
        if ("player".equals(StateStore.screen(this))) {
            VideoItem item = StateStore.playerItem(this);
            if (item != null) {
                launchPlayer(item, StateStore.position(this), false);
                return;
            }
        }
        if (lastQuery.trim().length() >= 2) search(); else query.requestFocus();
    }

    @Override protected void onDestroy() {
        generation.incrementAndGet();
        network.shutdownNow();
        imageLoader.close();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? "ошибка сети" : e.getMessage(); }
    private interface SearchCall { List<VideoItem> run() throws Exception; }

    private final class VideoAdapter extends BaseAdapter {
        private final Context context;
        VideoAdapter(Context context) { this.context = context; }
        @Override public int getCount() { return items.size(); }
        @Override public VideoItem getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return getItem(position).stableKey().hashCode(); }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Holder holder;
            if (recycled == null) {
                LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(7), dp(7), dp(7), dp(7));
                card.setBackgroundColor(Color.rgb(27, 31, 41));
                card.setFocusable(false);
                card.setClickable(false);
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                FrameLayout poster = new FrameLayout(context);
                poster.addView(image, new FrameLayout.LayoutParams(-1, -1));
                ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000);
                progress.setProgressDrawable(getDrawable(R.drawable.card_progress));
                progress.setVisibility(View.GONE);
                poster.addView(progress, new FrameLayout.LayoutParams(-1, dp(6), Gravity.BOTTOM));
                card.addView(poster, new LinearLayout.LayoutParams(-1, dp(126)));
                TextView source = text("", 9, Color.rgb(154, 134, 255));
                source.setTypeface(Typeface.DEFAULT_BOLD);
                card.addView(source, new LinearLayout.LayoutParams(-1, dp(22)));
                TextView title = text("", 16, Color.WHITE);
                title.setMaxLines(2);
                card.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
                TextView subtitle = text("", 12, Color.rgb(169, 176, 190));
                subtitle.setSingleLine(true);
                card.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(24)));
                holder = new Holder(image, progress, source, title, subtitle);
                card.setTag(holder);
                recycled = card;
            } else holder = (Holder) recycled.getTag();
            VideoItem item = getItem(position);
            String quality = item.qualityLabel();
            if (quality.endsWith("p")) quality = quality.substring(0, quality.length() - 1);
            holder.source.setText(item.source + (quality.isEmpty() ? "" : " (" + quality + ")"));
            holder.title.setText(item.title);
            holder.subtitle.setText(item.subtitle);
            WatchProgressStore.Progress watched = WatchProgressStore.get(context, item);
            long duration = watched.durationMs > 0 ? watched.durationMs : item.durationMs;
            if (watched.positionMs >= WatchProgressStore.MIN_POSITION_MS && duration > 0) {
                holder.progress.setProgress((int) Math.min(1000L, watched.positionMs * 1000L / duration));
                holder.progress.setVisibility(View.VISIBLE);
            } else {
                holder.progress.setVisibility(View.GONE);
            }
            imageLoader.load(item.thumbnail, holder.image);
            return recycled;
        }
    }

    private static final class Holder {
        final ImageView image; final ProgressBar progress;
        final TextView source; final TextView title; final TextView subtitle;
        Holder(ImageView image, ProgressBar progress,
               TextView source, TextView title, TextView subtitle) {
            this.image = image; this.progress = progress;
            this.source = source; this.title = title; this.subtitle = subtitle;
        }
    }
}
