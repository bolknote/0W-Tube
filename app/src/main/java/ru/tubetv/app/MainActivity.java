package ru.tubetv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
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
    private static final int REQUEST_INSTALL_SOURCE = 4307;
    private final SearchClient searchClient = new SearchClient();
    private final ExecutorService network = Executors.newFixedThreadPool(4);
    private final ExecutorService historyIo = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger historyGeneration = new AtomicInteger();
    private final List<VideoItem> allItems = new ArrayList<>();
    private final List<VideoItem> searchItems = new ArrayList<>();
    private final List<VideoItem> historyItems = new ArrayList<>();
    private final List<VideoItem> items = new ArrayList<>();
    private final Map<String, WatchHistoryStore.Entry> historyByKey = new HashMap<>();
    private final Set<String> qualityRequested = new HashSet<>();
    private static final String[] NORMAL_FILTER_LABELS = {"Любое", "720+", "1080+", "1440+", "2160 / 4K"};
    private static final int[] NORMAL_FILTER_WIDTHS = {0, 1280, 1920, 2560, 3840};
    private static final String[] TRAFFIC_FILTER_LABELS = {"HD+", "480", "360", "240", "144", "Звук"};
    private static final int[] TRAFFIC_FILTER_WIDTHS = {1280, 854, 640, 426, 256, 0};
    private static final int[] TRAFFIC_FILTER_HEIGHTS = {720, 480, 360, 240, 144, 0};
    private ImageLoader imageLoader;
    private VideoAdapter adapter;
    private GridView grid;
    private EditText query;
    private TextView status;
    private TextView historyStatus;
    private View searchBar;
    private View filterBar;
    private View historyBar;
    private final List<Button> filterButtons = new ArrayList<>();
    private ImageButton trafficButton;
    private int selectedFilter;
    private boolean trafficMode;
    private boolean searchStarted;
    private int thumbnailTargetWidth;
    private int gridColumns;
    private int gridSpacingDp;
    private final List<Future<?>> activeSearches = new ArrayList<>();
    private boolean rutubeDone;
    private boolean vkDone;
    private boolean dzenDone;
    private boolean okDone;
    private boolean peerTubeDone;
    private int rutubeCount;
    private int vkCount;
    private int dzenCount;
    private int okCount;
    private int peerTubeCount;
    private String rutubeError;
    private String vkError;
    private String dzenError;
    private String okError;
    private String peerTubeError;
    private String currentSearchQuery = "";
    private int qualityJobs;
    private boolean historyMode;
    private int modeSwitchHeldKey = KeyEvent.KEYCODE_UNKNOWN;
    private final GridState searchGridState = new GridState();
    private final GridState historyGridState = new GridState();
    private UpdateManager.Release pendingUpdate;
    private File pendingUpdateApk;
    private boolean activityResumed;
    private boolean updateDialogVisible;
    private String offeredUpdateVersion;
    private Object systemBackCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        imageLoader = ((TubeApplication) getApplication()).imageLoader();
        setContentView(createContent());
        restoreState();
        registerSystemBackCallback();
        checkForUpdate();
    }

    private View createContent() {
        filterButtons.clear();
        boolean compact = isCompactLayout();
        boolean compactLandscape = compact
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int baseLeft = dp(compact ? 12 : 36);
        final int baseTop = dp(compactLandscape ? 0 : compact ? 12 : 24);
        final int baseRight = dp(compact ? 12 : 36);
        final int baseBottom = dp(compact ? 12 : 20);
        root.setPadding(baseLeft, baseTop, baseRight, baseBottom);
        root.setBackgroundColor(Color.rgb(16, 18, 24));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(baseLeft + safe.left, baseTop + safe.top,
                    baseRight + safe.right, baseBottom + safe.bottom);
            updateThumbnailTarget();
            return windowInsets;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(compact ? 48 : 70), dp(compact ? 44 : 60)));

        query = new EditText(this);
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(Color.rgb(160, 166, 178));
        query.setHint(compact ? "Найти видео"
                : "Введите запрос для поиска в RUTUBE, VK Video, Дзене, OK и PeerTube");
        query.setTextSize(compact ? 16 : 20);
        query.setBackgroundResource(R.drawable.search_field_background);
        if (compact) {
            query.setMinimumHeight(0);
            query.setPadding(dp(14), 0, dp(14), 0);
        }
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search(); return true;
            }
            return false;
        });
        bar.addView(query, new LinearLayout.LayoutParams(0, dp(compact ? 30 : 40), 1f));

        if (!compact) {
            Button search = new Button(this);
            search.setText("Найти");
            search.setTextColor(Color.WHITE);
            search.setBackgroundResource(R.drawable.search_button_background);
            search.setOnClickListener(v -> search());
            search.setTextSize(14);
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(dp(90), dp(40));
            searchParams.setMargins(dp(8), 0, 0, 0);
            bar.addView(search, searchParams);
        }

        searchBar = bar;
        root.addView(searchBar, new LinearLayout.LayoutParams(-1, dp(compact ? 48 : 64)));

        LinearLayout historyHeader = new LinearLayout(this);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);
        ImageView historyLogo = new ImageView(this);
        historyLogo.setImageResource(R.drawable.ic_launcher);
        historyLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        historyHeader.addView(historyLogo,
                new LinearLayout.LayoutParams(dp(compact ? 48 : 70), dp(compact ? 44 : 60)));
        TextView historyTitle = text("История просмотров", compact ? 18 : 22, Color.WHITE);
        historyTitle.setGravity(Gravity.CENTER_VERTICAL);
        historyHeader.addView(historyTitle, new LinearLayout.LayoutParams(0, -1, 1f));
        historyStatus = text("", compact ? 11 : 13, Color.rgb(169, 176, 190));
        historyStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        historyHeader.addView(historyStatus,
                new LinearLayout.LayoutParams(dp(compact ? 78 : 120), -1));
        historyBar = historyHeader;
        root.addView(historyBar, new LinearLayout.LayoutParams(-1, dp(compact ? 48 : 64)));

        LinearLayout filtersAndStatus = new LinearLayout(this);
        filtersAndStatus.setOrientation(LinearLayout.HORIZONTAL);
        filtersAndStatus.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(dp(compact ? 0 : 70), dp(4), dp(8), dp(4));
        for (int i = 0; i < TRAFFIC_FILTER_LABELS.length; i++) {
            final int index = i;
            Button chip = new Button(this);
            chip.setText(i < NORMAL_FILTER_LABELS.length ? NORMAL_FILTER_LABELS[i] : "");
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
        trafficButton = new ImageButton(this);
        trafficButton.setImageResource(R.drawable.ic_tortoise);
        trafficButton.setContentDescription("Трафик");
        trafficButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        trafficButton.setPadding(dp(5), dp(4), dp(5), dp(4));
        trafficButton.setMinimumWidth(0);
        trafficButton.setMinimumHeight(0);
        trafficButton.setBackgroundResource(R.drawable.filter_chip_background);
        trafficButton.setOnClickListener(v -> toggleTrafficMode());
        LinearLayout.LayoutParams trafficParams = new LinearLayout.LayoutParams(dp(44), dp(28));
        trafficParams.setMargins(dp(12), 0, 0, 0);
        filters.addView(trafficButton, trafficParams);
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setFillViewport(!compact);
        filterScroll.addView(filters, new HorizontalScrollView.LayoutParams(-2, dp(36)));

        status = text("", 11, Color.rgb(169, 176, 190));
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.INVISIBLE);
        if (compact && !compactLandscape) {
            filtersAndStatus.setOrientation(LinearLayout.VERTICAL);
            filtersAndStatus.setGravity(Gravity.NO_GRAVITY);
            filtersAndStatus.addView(filterScroll, new LinearLayout.LayoutParams(-1, dp(36)));
            status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            filtersAndStatus.addView(status, new LinearLayout.LayoutParams(-1, dp(26)));
            filterBar = filtersAndStatus;
            root.addView(filterBar, new LinearLayout.LayoutParams(-1, dp(62)));
        } else {
            filtersAndStatus.addView(filterScroll, new LinearLayout.LayoutParams(0, dp(36), 1f));
            status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    dp(compact ? 88 : 90), dp(36));
            statusParams.setMargins(dp(8), 0, 0, 0);
            filtersAndStatus.addView(status, statusParams);
            filterBar = filtersAndStatus;
            root.addView(filterBar, new LinearLayout.LayoutParams(-1, dp(36)));
        }

        grid = new GridView(this);
        gridColumns = gridColumnCount();
        gridSpacingDp = compact ? 8 : 14;
        grid.setNumColumns(gridColumns);
        grid.setHorizontalSpacing(dp(gridSpacingDp));
        grid.setVerticalSpacing(dp(compact ? 8 : 14));
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
        GestureDetector swipe = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent event) { return true; }

                    @Override public boolean onFling(MotionEvent start, MotionEvent end,
                                                     float velocityX, float velocityY) {
                        if (DeviceType.isTelevision(MainActivity.this)
                                || start == null || end == null) return false;
                        float dx = end.getX() - start.getX();
                        float dy = end.getY() - start.getY();
                        if (Math.abs(dx) < dp(72) || Math.abs(dx) < Math.abs(dy) * 1.4f) {
                            return false;
                        }
                        if (dx < 0 && !historyMode) showHistory(false);
                        else if (dx > 0 && historyMode) showSearch(false);
                        else return false;
                        return true;
                    }
                });
        grid.setOnTouchListener((view, event) -> {
            swipe.onTouchEvent(event);
            return false;
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        updateModeChrome();
        updateThumbnailTarget();
        ViewCompat.requestApplyInsets(root);
        return root;
    }

    private void updateThumbnailTarget() {
        float density = getResources().getDisplayMetrics().density;
        int landscapeWidth = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int landscapeColumns = landscapeWidth / density < 900 ? 3 : 4;
        boolean compactLandscape = isCompactLandscapeDevice();
        int horizontalPadding = dp(compactLandscape ? 24 : 72);
        int spacing = dp(compactLandscape ? 8 : 14) * (landscapeColumns - 1);
        int columnWidth = Math.max(1,
                (landscapeWidth - horizontalPadding - spacing) / landscapeColumns);
        thumbnailTargetWidth = Math.max(1, columnWidth - dp(14));
    }

    private void showHistory(boolean focusGrid) {
        if (historyMode) return;
        captureGridState(searchGridState);
        historyMode = true;
        StateStore.markHistory(this);
        items.clear();
        items.addAll(historyItems);
        adapter.notifyDataSetChanged();
        updateModeChrome();
        hideKeyboard();
        reloadHistory(false, focusGrid);
    }

    private void showSearch(boolean focusGrid) {
        if (!historyMode) return;
        captureGridState(historyGridState);
        historyMode = false;
        StateStore.markSearch(this);
        items.clear();
        items.addAll(searchItems);
        updateModeChrome();
        adapter.notifyDataSetChanged();
        if (focusGrid && items.isEmpty()) query.requestFocus();
        else restoreGridState(searchGridState, focusGrid);
        updateSearchStatus();
        if (searchItems.isEmpty() && query.getText().toString().trim().length() >= 2) {
            grid.post(this::search);
        }
    }

    private void reloadHistory(boolean preserveGridState, boolean focusGrid) {
        if (preserveGridState && grid != null && historyMode) captureGridState(historyGridState);
        int current = historyGeneration.incrementAndGet();
        if (historyStatus != null && historyItems.isEmpty()) historyStatus.setText("…");
        historyIo.execute(() -> {
            List<WatchHistoryStore.Entry> history = WatchHistoryStore.load(this);
            runOnUiThread(() -> {
                if (current != historyGeneration.get() || !historyMode || isFinishing()) return;
                historyByKey.clear();
                historyItems.clear();
                for (WatchHistoryStore.Entry entry : history) {
                    historyItems.add(entry.item);
                    historyByKey.put(entry.item.stableKey(), entry);
                }
                items.clear();
                items.addAll(historyItems);
                if (historyStatus != null) {
                    historyStatus.setText(items.isEmpty() ? "Пусто" : String.valueOf(items.size()));
                }
                if (adapter != null) adapter.notifyDataSetChanged();
                restoreGridState(historyGridState,
                        focusGrid || (preserveGridState && historyGridState.hadFocus));
            });
        });
    }

    private void updateModeChrome() {
        if (searchBar == null || filterBar == null || historyBar == null) return;
        searchBar.setVisibility(historyMode ? View.GONE : View.VISIBLE);
        filterBar.setVisibility(historyMode ? View.GONE : View.VISIBLE);
        historyBar.setVisibility(historyMode ? View.VISIBLE : View.GONE);
        if (historyStatus != null && historyMode) {
            historyStatus.setText(items.isEmpty() ? "Пусто" : String.valueOf(items.size()));
        }
    }

    private void captureGridState(GridState state) {
        if (grid == null) return;
        state.selectedKey = selectedItemKey();
        state.firstVisible = grid.getFirstVisiblePosition();
        View first = grid.getChildAt(0);
        state.firstTop = first == null ? 0 : first.getTop();
        state.hadFocus = grid.hasFocus();
    }

    private void restoreGridState(GridState state, boolean requestFocus) {
        if (grid == null || items.isEmpty()) return;
        int position = Math.max(0, Math.min(state.firstVisible,
                items.size() - 1));
        grid.setSelectionFromTop(position, state.firstTop);
        grid.post(() -> {
            if (items.isEmpty()) return;
            int restoredPosition = Math.max(0, Math.min(position, items.size() - 1));
            grid.setSelectionFromTop(restoredPosition, state.firstTop);
            restoreGridSelection(state.selectedKey,
                    requestFocus || state.hadFocus, requestFocus && state.selectedKey == null);
        });
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && query != null) {
            keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
            query.clearFocus();
        }
    }

    private void search() {
        String value = query.getText().toString().trim();
        if (value.length() < 2) return;
        searchStarted = true;
        int current = generation.incrementAndGet();
        currentSearchQuery = value;
        for (Future<?> search : activeSearches) search.cancel(true);
        activeSearches.clear();
        allItems.clear();
        searchItems.clear();
        if (!historyMode) items.clear();
        qualityRequested.clear();
        qualityJobs = 0;
        if (!historyMode) adapter.notifyDataSetChanged();
        StateStore.saveSearch(this, value, selectedFilter, trafficMode);
        if (historyMode) StateStore.markHistory(this);
        rutubeDone = false;
        vkDone = false;
        dzenDone = false;
        okDone = false;
        peerTubeDone = false;
        rutubeCount = 0;
        vkCount = 0;
        dzenCount = 0;
        okCount = 0;
        peerTubeCount = 0;
        rutubeError = null;
        vkError = null;
        dzenError = null;
        okError = null;
        peerTubeError = null;
        updateSearchStatus();
        activeSearches.add(network.submit(
                () -> runSource(current, "RUTUBE", () -> searchClient.searchRutube(value, 0))));
        activeSearches.add(network.submit(
                () -> runVkSource(current, value)));
        activeSearches.add(network.submit(
                () -> runDzenSource(current, value)));
        activeSearches.add(network.submit(
                () -> runSource(current, "OK", () -> searchClient.searchOk(value, 0))));
        // Submitted last on a four-thread executor: PeerTube starts only after one of the
        // primary services has returned and can never delay their first cards.
        activeSearches.add(network.submit(() -> runSource(current, PeerTubeClient.SOURCE,
                () -> searchClient.searchPeerTube(value, 0, thumbnailTargetWidth))));
    }

    private void selectFilter(int index, boolean rerunSearch) {
        selectedFilter = Math.max(0, Math.min(index, activeFilterLabels().length - 1));
        for (int i = 0; i < filterButtons.size(); i++) filterButtons.get(i).setSelected(i == selectedFilter);
        if (rerunSearch) StateStore.saveSearch(this, query == null ? "" : query.getText().toString(), selectedFilter, trafficMode);
        if (rerunSearch && query.getText().toString().trim().length() >= 2) {
            refreshDisplayedItems(false);
            if (selectedFilter > 0) requestMissingQualities(generation.get(), new ArrayList<>(allItems));
        }
    }

    private void toggleTrafficMode() {
        trafficMode = !trafficMode;
        selectedFilter = 0;
        applyFilterMode();
        StateStore.saveSearch(this, query == null ? "" : query.getText().toString(), selectedFilter, trafficMode);
        refreshDisplayedItems(false);
        if (!allItems.isEmpty()) requestMissingQualities(generation.get(), new ArrayList<>(allItems));
    }

    private void applyFilterMode() {
        String[] labels = activeFilterLabels();
        for (int i = 0; i < filterButtons.size(); i++) {
            Button button = filterButtons.get(i);
            boolean visible = i < labels.length;
            button.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) button.setText(labels[i]);
            button.setSelected(visible && i == selectedFilter);
        }
        trafficButton.setSelected(trafficMode);
    }

    private String[] activeFilterLabels() {
        return trafficMode ? TRAFFIC_FILTER_LABELS : NORMAL_FILTER_LABELS;
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

    private void runVkSource(int current, String value) {
        int[] delivered = {0};
        try {
            int count = searchClient.searchVkPages(value, 0, thumbnailTargetWidth, found -> {
                if (Thread.currentThread().isInterrupted() || current != generation.get()) return false;
                delivered[0] += found.size();
                runOnUiThread(() -> {
                    if (current != generation.get()) return;
                    allItems.addAll(found);
                    refreshDisplayedItems(true);
                    requestMissingQualities(current, found);
                });
                return true;
            });
            runOnUiThread(() -> {
                if (current == generation.get()) finishSource("VK Video", count, null);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (current == generation.get()) {
                    finishSource("VK Video", delivered[0], safeMessage(e));
                }
            });
        }
    }

    private void runDzenSource(int current, String value) {
        int[] delivered = {0};
        try {
            int count = searchClient.searchDzenPages(value, found -> {
                if (Thread.currentThread().isInterrupted() || current != generation.get()) {
                    return false;
                }
                delivered[0] += found.size();
                runOnUiThread(() -> {
                    if (current != generation.get()) return;
                    allItems.addAll(found);
                    refreshDisplayedItems(true);
                    requestMissingQualities(current, found);
                });
                return true;
            });
            runOnUiThread(() -> {
                if (current == generation.get()) finishSource("Дзен", count, null);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (current == generation.get()) {
                    finishSource("Дзен", delivered[0], safeMessage(e));
                }
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
        } else if ("Дзен".equals(source)) {
            dzenDone = true;
            dzenCount = count;
            dzenError = error;
        } else if ("OK".equals(source)) {
            okDone = true;
            okCount = count;
            okError = error;
        } else if (PeerTubeClient.SOURCE.equals(source)) {
            peerTubeDone = true;
            peerTubeCount = count;
            peerTubeError = error;
        }
        updateSearchStatus();
    }

    private void updateSearchStatus() {
        if (!searchStarted) {
            status.setText("");
            status.setVisibility(View.INVISIBLE);
            return;
        }
        status.setVisibility(View.VISIBLE);
        boolean working = !rutubeDone || !vkDone || !dzenDone || !okDone
                || !peerTubeDone || qualityJobs > 0;
        status.setText("Найдено: " + searchItems.size() + (working ? "…" : ""));
    }

    private void refreshDisplayedItems(boolean selectFirst) {
        boolean hadItems = !searchItems.isEmpty();
        boolean gridHadFocus = !historyMode && grid.hasFocus();
        String selectedKey = historyMode ? searchGridState.selectedKey : selectedItemKey();
        int minWidth = trafficMode ? TRAFFIC_FILTER_WIDTHS[selectedFilter] : NORMAL_FILTER_WIDTHS[selectedFilter];
        searchItems.clear();
        for (VideoItem item : allItems) {
            if (minWidth == 0 || item.maxWidth >= minWidth) searchItems.add(item);
        }
        Collections.sort(searchItems, VideoRanker.comparator(currentSearchQuery, trafficMode));
        if (historyMode) {
            updateSearchStatus();
            return;
        }
        items.clear();
        items.addAll(searchItems);
        adapter.notifyDataSetChanged();
        restoreGridSelection(selectedKey, gridHadFocus, selectFirst && !hadItems && !items.isEmpty());
        updateSearchStatus();
    }

    private void requestMissingQualities(int current, List<VideoItem> candidates) {
        List<VideoItem> regular = new ArrayList<>();
        List<VideoItem> dzen = new ArrayList<>();
        List<VideoItem> ok = new ArrayList<>();
        for (VideoItem item : candidates) {
            String key = item.stableKey();
            if (item.maxWidth != 0 || !qualityRequested.add(key)) continue;
            if ("ДЗЕН".equals(item.source)) dzen.add(item);
            else if ("OK".equals(item.source)) ok.add(item);
            else regular.add(item);
        }
        submitQualityJob(current, regular, 4);
        submitQualityJob(current, dzen, 2);
        submitQualityJob(current, ok, 2);
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
        WatchProgressStore.Progress watched = WatchProgressStore.get(this, item);
        long position = watched.positionMs;
        long duration = watched.durationMs > 0 ? watched.durationMs : item.durationMs;
        if (duration > 0 && position * 100L >= duration * 95L) position = 0L;
        WatchHistoryStore.Entry history = historyMode ? historyByKey.get(item.stableKey()) : null;
        boolean playerTrafficMode = history == null ? trafficMode : history.trafficMode;
        int targetHeight = history == null
                ? (trafficMode ? TRAFFIC_FILTER_HEIGHTS[selectedFilter] : 0)
                : history.targetHeight;
        boolean audioOnly = history == null
                ? trafficMode && targetHeight == 0
                : history.audioOnly;
        if (watched.positionMs >= WatchProgressStore.MIN_POSITION_MS) {
            WatchHistoryStore.record(this, item.withDuration(duration),
                    playerTrafficMode, targetHeight, audioOnly);
        }
        StateStore.savePlayer(this, item, position, playerTrafficMode, targetHeight,
                audioOnly, historyMode);
        launchPlayer(item, position, true, playerTrafficMode, targetHeight, audioOnly);
    }

    private void launchPlayer(VideoItem item, long position, boolean autoPlay,
                              boolean playerTrafficMode, int targetHeight, boolean audioOnly) {
        try {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("source", item.source);
            intent.putExtra("title", item.title);
            intent.putExtra("subtitle", item.subtitle);
            intent.putExtra("thumbnail", item.thumbnail);
            intent.putExtra("resolver_url", item.playUrl);
            intent.putExtra("page_url", item.pageUrl);
            intent.putExtra("duration_ms", item.durationMs);
            intent.putExtra("max_width", item.maxWidth);
            intent.putExtra("max_height", item.maxHeight);
            intent.putExtra("resume_position", position);
            intent.putExtra("auto_play", autoPlay);
            intent.putExtra("traffic_mode", playerTrafficMode);
            intent.putExtra("target_height", targetHeight);
            intent.putExtra("audio_only", audioOnly);
            startActivity(intent);
        } catch (Throwable error) {
            CrashLog.save(getApplicationContext(), error);
            new AlertDialog.Builder(this).setTitle("Не удалось открыть плеер")
                    .setMessage(error.toString()).setPositiveButton("Закрыть", null).show();
        }
    }

    private void restoreState() {
        trafficMode = StateStore.trafficMode(this);
        selectedFilter = StateStore.filter(this);
        selectedFilter = Math.max(0, Math.min(selectedFilter, activeFilterLabels().length - 1));
        applyFilterMode();
        selectFilter(selectedFilter, false);
        String lastQuery = StateStore.query(this);
        query.setText(lastQuery);
        WatchHistoryStore.migrateLegacyPlayer(this, StateStore.playerItem(this),
                StateStore.playerTrafficMode(this), StateStore.playerTargetHeight(this),
                StateStore.playerAudioOnly(this));
        String screen = StateStore.screen(this);
        if ("player".equals(screen)) {
            VideoItem item = StateStore.playerItem(this);
            if (item != null) {
                launchPlayer(item, StateStore.position(this), false,
                        StateStore.playerTrafficMode(this), StateStore.playerTargetHeight(this),
                        StateStore.playerAudioOnly(this));
                return;
            }
        }
        if ("history".equals(screen)) {
            showHistory(false);
            return;
        }
        if (lastQuery.trim().length() >= 2) grid.post(this::search); else query.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (DeviceType.isTelevision(this)) {
            int keyCode = event.getKeyCode();
            if (modeSwitchHeldKey == keyCode) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    modeSwitchHeldKey = KeyEvent.KEYCODE_UNKNOWN;
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                boolean simpleOpen = !historyMode
                        && keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        && event.getRepeatCount() == 0
                        && (grid == null || !grid.hasFocus());
                boolean longOpen = !historyMode
                        && keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        && event.getRepeatCount() > 0
                        && grid != null && grid.hasFocus();
                int selected = grid == null ? -1 : grid.getSelectedItemPosition();
                boolean ceilingClose = historyMode
                        && keyCode == KeyEvent.KEYCODE_DPAD_UP
                        && event.getRepeatCount() == 0
                        && (items.isEmpty() || selected < gridColumns);
                boolean longClose = historyMode
                        && keyCode == KeyEvent.KEYCODE_DPAD_UP
                        && event.getRepeatCount() > 0;
                if (simpleOpen || longOpen || ceilingClose || longClose) {
                    modeSwitchHeldKey = keyCode;
                    if (historyMode) showSearch(true); else showHistory(true);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        if (handleBackNavigation()) return;
        super.onBackPressed();
    }

    private boolean handleBackNavigation() {
        if (!historyMode) return false;
        showSearch(true);
        return true;
    }

    private void registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || systemBackCallback != null) return;
        android.window.OnBackInvokedCallback callback = () -> {
            if (!handleBackNavigation()) finish();
        };
        systemBackCallback = callback;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
    }

    private void unregisterSystemBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || systemBackCallback == null) return;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (android.window.OnBackInvokedCallback) systemBackCallback);
        systemBackCallback = null;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        String queryText = query == null ? currentSearchQuery : query.getText().toString();
        int querySelection = query == null ? queryText.length() : query.getSelectionStart();
        boolean queryHadFocus = query != null && query.hasFocus();
        boolean gridHadFocus = grid != null && grid.hasFocus();
        String selectedKey = grid == null ? null : selectedItemKey();
        int firstVisible = grid == null ? 0 : grid.getFirstVisiblePosition();
        View firstChild = grid == null ? null : grid.getChildAt(0);
        int firstTop = firstChild == null ? 0 : firstChild.getTop();

        super.onConfigurationChanged(configuration);
        setContentView(createContent());
        query.setText(queryText);
        query.setSelection(Math.max(0, Math.min(querySelection, queryText.length())));
        applyFilterMode();
        updateModeChrome();
        adapter.notifyDataSetChanged();
        updateSearchStatus();

        if (items.isEmpty()) {
            if (queryHadFocus && !historyMode) query.requestFocus();
        } else {
            final int visiblePosition = Math.max(0, Math.min(firstVisible, items.size() - 1));
            grid.setSelectionFromTop(visiblePosition, firstTop);
            grid.post(() -> {
                if (items.isEmpty()) return;
                int restoredPosition = Math.max(0,
                        Math.min(visiblePosition, items.size() - 1));
                grid.setSelectionFromTop(restoredPosition, firstTop);
                if (gridHadFocus) {
                    restoreGridSelection(selectedKey, true, false);
                } else if (queryHadFocus) {
                    query.requestFocus();
                }
            });
        }
    }

    @Override protected void onDestroy() {
        unregisterSystemBackCallback();
        generation.incrementAndGet();
        historyGeneration.incrementAndGet();
        network.shutdownNow();
        historyIo.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        String savedScreen = StateStore.screen(this);
        if ("history".equals(savedScreen) && !historyMode) showHistory(true);
        else if ("search".equals(savedScreen) && historyMode) showSearch(true);
        if (historyMode) reloadHistory(true, historyGridState.hadFocus);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (grid != null) grid.post(() -> {
            checkForUpdate();
            maybeOfferUpdate();
        });
    }

    @Override protected void onPause() {
        activityResumed = false;
        super.onPause();
    }

    private void maybeOfferUpdate() {
        if (!activityResumed || pendingUpdate == null || updateDialogVisible || isFinishing()) return;
        UpdateManager.Release release = pendingUpdate;
        pendingUpdate = null;
        if (release.version.equals(offeredUpdateVersion)) return;
        offeredUpdateVersion = release.version;
        updateDialogVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Доступна версия " + release.version)
                .setMessage("Скачать и установить обновление 0W-Tube?")
                .setNegativeButton("Позже", null)
                .setPositiveButton("Обновить", (ignored, which) -> downloadUpdate(release))
                .create();
        dialog.setOnDismissListener(ignored -> updateDialogVisible = false);
        dialog.show();
    }

    private void checkForUpdate() {
        UpdateManager.check(this, release -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()
                    || release.version.equals(offeredUpdateVersion)) return;
            pendingUpdate = release;
            maybeOfferUpdate();
        }));
    }

    private void downloadUpdate(UpdateManager.Release release) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Обновление 0W-Tube")
                .setMessage("Скачиваю APK…")
                .setCancelable(false)
                .create();
        progress.show();
        UpdateManager.download(this, release, new UpdateManager.DownloadCallback() {
            @Override public void onProgress(int percent) {
                runOnUiThread(() -> progress.setMessage(percent >= 0
                        ? "Скачиваю APK: " + percent + "%" : "Скачиваю APK…"));
            }

            @Override public void onReady(File apk) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    installUpdate(apk);
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    showUpdateError(message);
                });
            }
        });
    }

    private void installUpdate(File apk) {
        pendingUpdateApk = apk;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(permission, REQUEST_INSTALL_SOURCE);
            } catch (Exception error) {
                showUpdateError("Не удалось открыть разрешение установки приложений");
            }
            return;
        }
        launchPackageInstaller(apk);
    }

    private void launchPackageInstaller(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.setClipData(ClipData.newRawUri("0W-Tube update", uri));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception error) {
            showUpdateError("Не удалось открыть системный установщик: " + safeMessage(error));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_INSTALL_SOURCE || pendingUpdateApk == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls()) {
            launchPackageInstaller(pendingUpdateApk);
        } else {
            showUpdateError("Разрешение на установку обновления не выдано");
        }
    }

    private void showUpdateError(String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle("Не удалось обновить 0W-Tube")
                .setMessage(message).setPositiveButton("Закрыть", null).show();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private boolean isCompactLayout() {
        Configuration configuration = getResources().getConfiguration();
        if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT) return true;

        return isCompactLandscapeDevice();
    }

    private boolean isCompactLandscapeDevice() {
        Configuration configuration = getResources().getConfiguration();
        int smallestWidthDp = configuration.smallestScreenWidthDp;
        if (smallestWidthDp > 0) return smallestWidthDp < 720;

        float density = getResources().getDisplayMetrics().density;
        int shortestSide = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        return shortestSide / density < 720;
    }

    private int gridColumnCount() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            return widthDp < 600 ? 2 : 3;
        }
        return widthDp < 900 ? 3 : 4;
    }
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
                TextView duration = text("", 9, Color.WHITE);
                duration.setGravity(Gravity.CENTER);
                duration.setBackgroundResource(R.drawable.duration_badge_background);
                duration.setVisibility(View.GONE);
                FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(-2, dp(18),
                        Gravity.BOTTOM | Gravity.END);
                durationParams.setMargins(dp(6), dp(6), dp(6), dp(8));
                poster.addView(duration, durationParams);
                card.addView(poster, new LinearLayout.LayoutParams(-1, dp(126)));
                TextView source = text("", 9, Color.rgb(154, 134, 255));
                source.setTypeface(Typeface.DEFAULT_BOLD);
                card.addView(source, new LinearLayout.LayoutParams(-1, dp(22)));
                TextView title = text("", 16, Color.WHITE);
                title.setMaxLines(2);
                card.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
                holder = new Holder(image, progress, duration, source, title);
                card.setTag(holder);
                recycled = card;
            } else holder = (Holder) recycled.getTag();
            VideoItem item = getItem(position);
            String quality = cardQualityLabel(item);
            holder.source.setText(item.source + (quality.isEmpty() ? "" : " (" + quality + ")"));
            holder.title.setText(item.title);
            String durationLabel = formatDuration(item.durationMs);
            holder.duration.setText(durationLabel);
            holder.duration.setVisibility(durationLabel.isEmpty() ? View.GONE : View.VISIBLE);
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

    private String cardQualityLabel(VideoItem item) {
        if (historyMode) {
            WatchHistoryStore.Entry history = historyByKey.get(item.stableKey());
            if (history != null && history.audioOnly) return "звук";
            if (history != null && history.trafficMode && history.targetHeight > 0) {
                return String.valueOf(history.targetHeight);
            }
            String quality = item.qualityLabel();
            return quality.endsWith("p") ? quality.substring(0, quality.length() - 1) : quality;
        }
        if (trafficMode) {
            int targetHeight = TRAFFIC_FILTER_HEIGHTS[selectedFilter];
            if (targetHeight == 0) return "звук";
            if (item.maxWidth == 0 || item.maxWidth < TRAFFIC_FILTER_WIDTHS[selectedFilter]) return "";
            return String.valueOf(targetHeight);
        }
        String quality = item.qualityLabel();
        return quality.endsWith("p") ? quality.substring(0, quality.length() - 1) : quality;
    }

    private static final class Holder {
        final ImageView image; final ProgressBar progress;
        final TextView duration; final TextView source; final TextView title;
        Holder(ImageView image, ProgressBar progress, TextView duration,
               TextView source, TextView title) {
            this.image = image; this.progress = progress; this.duration = duration;
            this.source = source; this.title = title;
        }
    }

    private static final class GridState {
        String selectedKey;
        int firstVisible;
        int firstTop;
        boolean hadFocus;
    }

    private static String formatDuration(long durationMs) {
        long seconds = durationMs / 1000L;
        if (seconds <= 0) return "";
        long hours = seconds / 3600L;
        long minutes = seconds % 3600L / 60L;
        long rest = seconds % 60L;
        return hours > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(java.util.Locale.ROOT, "%d:%02d", minutes, rest);
    }
}
