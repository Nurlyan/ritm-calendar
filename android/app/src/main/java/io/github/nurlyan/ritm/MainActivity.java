package io.github.nurlyan.ritm;

import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RitmPlugin.class);
        super.onCreate(savedInstanceState);
        // Let the theme-aware window background show through until the page paints (no white flash in dark mode).
        if (getBridge() != null) getBridge().getWebView().setBackgroundColor(android.graphics.Color.TRANSPARENT);
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
}
