package io.github.nurlyan.ritm;

import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RitmPlugin.class);
        super.onCreate(savedInstanceState);
        // Let the theme-aware window background show through until the page paints (no white flash in dark mode).
        if (getBridge() != null) getBridge().getWebView().setBackgroundColor(android.graphics.Color.TRANSPARENT);
        // Different devices draw the page differently: Android 15+ always goes under the status and navigation bars,
        // older ones usually stop at them. The page gets the real overlap (in CSS px), so padding is never doubled or missing.
        if (getBridge() != null) {
            View web = getBridge().getWebView();
            web.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> pushInsets());
            ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> { v.post(this::pushInsets); return ViewCompat.onApplyWindowInsets(v, insets); });
        }
        // System Back asks the page first (close a dialog, return to the main screen) and leaves the app only
        // when there is nothing to close. Web history is not used: Chromium skips entries created without a tap.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView web = getBridge() == null ? null : getBridge().getWebView();
                if (web == null) { leave(); return; }
                web.evaluateJavascript("window.ritmBack ? window.ritmBack() : false", handled -> {
                    if (!"true".equals(handled)) leave();
                });
            }

            private void leave() {
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    private String lastInsets = "";

    /** How far the system bars and the display cutout overlap the page, in CSS pixels: {top, bottom, left, right}. */
    float[] overlap() {
        WebView web = getBridge() == null ? null : getBridge().getWebView();
        View root = getWindow().getDecorView();
        WindowInsetsCompat all = ViewCompat.getRootWindowInsets(root);
        if (web == null || all == null || web.getHeight() == 0) return new float[]{0, 0, 0, 0};
        Insets bars = all.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        int[] at = new int[2];
        web.getLocationInWindow(at);
        float d = getResources().getDisplayMetrics().density;
        return new float[]{
            Math.max(0, bars.top - at[1]) / d,
            Math.max(0, at[1] + web.getHeight() - (root.getHeight() - bars.bottom)) / d,
            Math.max(0, bars.left - at[0]) / d,
            Math.max(0, at[0] + web.getWidth() - (root.getWidth() - bars.right)) / d};
    }

    /** Hands the overlap to the page as CSS variables; repeated only when it changes (rotation, split screen, cutout). */
    void pushInsets() {
        WebView web = getBridge() == null ? null : getBridge().getWebView();
        if (web == null) return;
        float[] o = overlap();
        String js = String.format(java.util.Locale.ROOT, "(function(s){s.setProperty('--app-top','%.1fpx');s.setProperty('--app-bottom','%.1fpx');s.setProperty('--app-left','%.1fpx');s.setProperty('--app-right','%.1fpx')})(document.documentElement.style)", o[0], o[1], o[2], o[3]);
        if (js.equals(lastInsets)) return;
        lastInsets = js;
        web.evaluateJavascript(js, null);
    }

    @Override
    public void onResume() {
        super.onResume();
        lastInsets = "";
        View web = getBridge() == null ? null : getBridge().getWebView();
        if (web != null) web.post(this::pushInsets);
    }
}
