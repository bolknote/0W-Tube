package ru.tubetv.codecprobe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CodecProbeActivity extends Activity {
    private TextView status;
    private TextView output;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(createContent());
        new Thread(this::scan, "audio-codec-probe").start();
    }

    private android.view.View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(22), dp(28), dp(22));
        root.setBackgroundColor(Color.rgb(16, 18, 24));

        TextView title = text("0W CODEC PROBE · AUDIO DECODERS", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

        status = text("Сканирование и пробное открытие кодеков…", 15, Color.rgb(154, 134, 255));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(32)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        output = text("", 16, Color.rgb(228, 231, 239));
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(false);
        scroll.addView(output, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int step = Math.max(dp(220), scroll.getHeight() * 3 / 4);
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                scroll.smoothScrollBy(0, step);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                scroll.smoothScrollBy(0, -step);
                return true;
            }
            return false;
        });
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        scroll.post(scroll::requestFocus);
        return root;
    }

    private void scan() {
        StringBuilder report = new StringBuilder(8192);
        report.append("DEVICE: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("ANDROID: ").append(Build.VERSION.RELEASE).append(" · API ")
                .append(Build.VERSION.SDK_INT).append('\n');
        report.append("ABI: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n\n");
        report.append("SYSTEM = official Android flags\n");
        report.append("GUESS  = classification by codec name\n");
        report.append("OPEN   = component can be instantiated\n");
        report.append("Use DPAD ↑/↓ to scroll by page.\n\n");

        List<MediaCodecInfo> codecs = new ArrayList<>();
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!codec.isEncoder() && hasAudio(codec)) codecs.add(codec);
            }
        } catch (Throwable error) {
            report.append("SCAN FAILED: ").append(error).append('\n');
        }
        Collections.sort(codecs, (left, right) -> String.CASE_INSENSITIVE_ORDER
                .compare(left.getName(), right.getName()));
        int index = 0;
        for (MediaCodecInfo codec : codecs) {
            index++;
            report.append(String.format(Locale.ROOT, "[%02d] %s\n", index, codec.getName()));
            if (Build.VERSION.SDK_INT >= 29) {
                report.append(" SYSTEM: HW=").append(yesNo(codec.isHardwareAccelerated()))
                        .append(" SW=").append(yesNo(codec.isSoftwareOnly()))
                        .append(" VENDOR=").append(yesNo(codec.isVendor()))
                        .append(" ALIAS=").append(yesNo(codec.isAlias())).append('\n');
            } else {
                report.append(" SYSTEM: flags unavailable before API 29\n");
            }
            report.append(" GUESS:  ").append(guess(codec.getName())).append('\n');
            report.append(" OPEN:   ").append(canOpen(codec.getName())).append('\n');
            for (String type : codec.getSupportedTypes()) {
                if (!type.toLowerCase(Locale.ROOT).startsWith("audio/")) continue;
                report.append(" MIME:   ").append(type).append('\n');
                appendCapabilities(report, codec, type);
            }
            report.append('\n');
            final int done = index;
            runOnUiThread(() -> status.setText("Проверено: " + done + " / " + codecs.size()));
        }
        report.append("TOTAL AUDIO DECODERS: ").append(codecs.size()).append('\n');
        report.append("END OF REPORT");
        runOnUiThread(() -> {
            output.setText(report.toString());
            status.setText("Готово · аудиодекодеров: " + codecs.size());
        });
    }

    private static boolean hasAudio(MediaCodecInfo codec) {
        for (String type : codec.getSupportedTypes()) {
            if (type != null && type.toLowerCase(Locale.ROOT).startsWith("audio/")) return true;
        }
        return false;
    }

    private static void appendCapabilities(StringBuilder report, MediaCodecInfo codec, String type) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCapabilitiesForType(type);
            MediaCodecInfo.AudioCapabilities audio = caps.getAudioCapabilities();
            report.append("         channels<=").append(audio.getMaxInputChannelCount());
            int[] rates = audio.getSupportedSampleRates();
            if (rates != null && rates.length > 0) report.append(" rates=").append(Arrays.toString(rates));
            report.append(" profiles=").append(caps.profileLevels == null ? 0 : caps.profileLevels.length);
            if (Build.VERSION.SDK_INT >= 23) {
                report.append(" instances<=").append(caps.getMaxSupportedInstances());
            }
            report.append('\n');
        } catch (Throwable error) {
            report.append("         capabilities failed: ").append(error.getClass().getSimpleName()).append('\n');
        }
    }

    private static String canOpen(String codecName) {
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createByCodecName(codecName);
            return "OK";
        } catch (Throwable error) {
            return "FAILED (" + error.getClass().getSimpleName() + ')';
        } finally {
            if (codec != null) try { codec.release(); } catch (Throwable ignored) { }
        }
    }

    private static String guess(String codecName) {
        String name = codecName.toLowerCase(Locale.ROOT);
        if (name.startsWith("omx.google.") || name.startsWith("c2.android.")
                || name.startsWith("c2.google.") || name.contains("ffmpeg")
                || name.contains("software") || name.contains(".sw.")) {
            return "SOFTWARE BY NAME";
        }
        return "VENDOR / HARDWARE CANDIDATE";
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private static String yesNo(boolean value) { return value ? "YES" : "NO"; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
