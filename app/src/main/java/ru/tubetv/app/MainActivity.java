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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private final SearchClient searchClient = new SearchClient();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final List<VideoItem> items = new ArrayList<>();
    private static final String[] FILTER_LABELS = {"Любое", "720+", "1080+", "1440+", "2160 / 4K"};
    private static final int[] FILTER_WIDTHS = {0, 1280, 1920, 2560, 3840};
    private ImageLoader imageLoader;
    private VideoAdapter adapter;
    private GridView grid;
    private EditText query;
    private TextView status;
    private final List<Button> filterButtons = new ArrayList<>();
    private int selectedFilter;
    private Future<?> activeSearch;
    private boolean rutubeDone;
    private boolean vkDone;
    private int rutubeCount;
    private int vkCount;
    private String rutubeError;
    private String vkError;

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
        query.setHint("Найти в RUTUBE и VK Video");
        query.setTextSize(20);
        query.setBackgroundResource(R.drawable.search_field_background);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search(); return true;
            }
            return false;
        });
        bar.addView(query, new LinearLayout.LayoutParams(0, dp(56), 1f));

        Button search = new Button(this);
        search.setText("Найти");
        search.setOnClickListener(v -> search());
        bar.addView(search, new LinearLayout.LayoutParams(dp(120), dp(56)));

        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(64)));

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(dp(70), dp(5), 0, dp(5));
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            final int index = i;
            Button chip = new Button(this);
            chip.setText(FILTER_LABELS[i]);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(15);
            chip.setAllCaps(false);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setBackgroundResource(R.drawable.filter_chip_background);
            chip.setOnClickListener(v -> selectFilter(index, true));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, dp(42));
            chipParams.setMargins(0, 0, dp(10), 0);
            filters.addView(chip, chipParams);
            filterButtons.add(chip);
        }
        root.addView(filters, new LinearLayout.LayoutParams(-1, dp(52)));

        status = text("Введите запрос для поиска в RUTUBE и VK Video.", 15,
                Color.rgb(169, 176, 190));
        status.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));

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
        if (activeSearch != null) activeSearch.cancel(true);
        items.clear();
        adapter.notifyDataSetChanged();
        StateStore.saveSearch(this, value, selectedFilter);
        rutubeDone = false;
        vkDone = false;
        rutubeCount = 0;
        vkCount = 0;
        rutubeError = null;
        vkError = null;
        updateSearchStatus();
        int minWidth = FILTER_WIDTHS[selectedFilter];
        activeSearch = network.submit(() -> {
            runSource(current, "RUTUBE", () -> searchClient.searchRutube(value, minWidth));
            if (current != generation.get() || Thread.currentThread().isInterrupted()) return;
            runSource(current, "VK Video", () -> searchClient.searchVk(value, minWidth));
        });
    }

    private void selectFilter(int index, boolean rerunSearch) {
        selectedFilter = Math.max(0, Math.min(index, FILTER_LABELS.length - 1));
        for (int i = 0; i < filterButtons.size(); i++) filterButtons.get(i).setSelected(i == selectedFilter);
        if (rerunSearch) StateStore.saveSearch(this, query == null ? "" : query.getText().toString(), selectedFilter);
        if (rerunSearch && query.getText().toString().trim().length() >= 2) search();
    }

    private void runSource(int current, String source, SearchCall call) {
        try {
            List<VideoItem> found = call.run();
            runOnUiThread(() -> {
                if (current != generation.get()) return;
                boolean selectFirst = items.isEmpty() && !found.isEmpty();
                items.addAll(found);
                adapter.notifyDataSetChanged();
                if (selectFirst) {
                    grid.setSelection(0);
                    grid.requestFocus();
                }
                finishSource(source, found.size(), null);
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
        } else {
            vkDone = true;
            vkCount = count;
            vkError = error;
        }
        updateSearchStatus();
    }

    private void updateSearchStatus() {
        String prefix = FILTER_WIDTHS[selectedFilter] == 0 ? "Найдено: " + items.size()
                : FILTER_LABELS[selectedFilter] + "  •  Найдено: " + items.size();
        status.setText(prefix
                + "  •  RUTUBE: " + sourceState(rutubeDone, rutubeCount, rutubeError)
                + "  •  VK Video: " + sourceState(vkDone, vkCount, vkError));
    }

    private static String sourceState(boolean done, int count, String error) {
        if (!done) return "ищу…";
        if (error != null) return "ошибка — " + error;
        return String.valueOf(count);
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
        @Override public long getItemId(int position) { return position; }

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
                TextView source = text("", 12, Color.rgb(154, 134, 255));
                source.setTypeface(Typeface.DEFAULT_BOLD);
                card.addView(source, new LinearLayout.LayoutParams(-1, dp(25)));
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
            holder.source.setText(item.source);
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
        Holder(ImageView image, ProgressBar progress, TextView source, TextView title, TextView subtitle) {
            this.image = image; this.progress = progress;
            this.source = source; this.title = title; this.subtitle = subtitle;
        }
    }
}
