package com.sharephone.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

public class MainActivity extends AppCompatActivity {

    private WebView web;
    private LinearLayout home;
    private TextView message;
    private Button retryButton;
    private SharedPreferences prefs;

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result != null && result.getContents() != null) {
                    handleScanned(result.getContents());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("sharephone", Context.MODE_PRIVATE);
        getWindow().setStatusBarColor(0xFF5B3DF5);

        buildUi();
        setupWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (home.getVisibility() != View.VISIBLE && web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        openSaved();
    }

    // ---------------------------------------------------------------- UI
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button makeButton(String text, boolean filled, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(filled ? 0xFF5B3DF5 : Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (filled) {
            bg.setColor(Color.WHITE);
        } else {
            bg.setColor(0x33FFFFFF);
        }
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(260), dp(54));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setGravity(Gravity.CENTER);
        home.setPadding(dp(28), dp(28), dp(28), dp(28));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF4F46E5, 0xFF9333EA, 0xFFEC4899});
        home.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("Share to Phone");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        home.addView(title);

        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(14), 0, dp(24));
        home.addView(message);

        home.addView(makeButton("Scan QR code", true, v -> startScan()));
        retryButton = makeButton("Try again", false, v -> openSaved());
        home.addView(retryButton);

        root.addView(home, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void showHome(String text, boolean canRetry) {
        message.setText(text);
        retryButton.setVisibility(canRetry ? View.VISIBLE : View.GONE);
        home.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- web view
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showHome("Can't reach your PC.\nMake sure the PC app is running and this phone is on the same WiFi / hotspot.", true);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() == 401) {
                    showHome("This phone needs access.\nScan the QR code shown on your PC.", false);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                Intent i = new Intent(this, DownloadService.class);
                i.putExtra("url", url);
                i.putExtra("name", URLUtil.guessFileName(url, contentDisposition, mimeType));
                i.putExtra("mime", mimeType);
                i.putExtra("cookie", CookieManager.getInstance().getCookie(url));
                i.putExtra("ua", userAgent);
                ContextCompat.startForegroundService(this, i);
            } catch (Exception e) {
                Toast.makeText(this, "Could not start the download: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void openSaved() {
        String url = prefs.getString("url", null);
        if (url == null) {
            showHome("Open Share to Phone on your PC, then scan the QR code it shows.", false);
            return;
        }
        home.setVisibility(View.GONE);
        web.loadUrl(url);
    }

    // ---------------------------------------------------------------- QR scanning
    private void startScan() {
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        o.setPrompt("Point the camera at the QR code on your PC");
        o.setBeepEnabled(false);
        o.setOrientationLocked(false);
        scanLauncher.launch(o);
    }

    private void handleScanned(String text) {
        if ((text.startsWith("http://") || text.startsWith("https://")) && text.contains("?k=")) {
            prefs.edit().putString("url", text).apply();
            openSaved();
        } else {
            Toast.makeText(this, "That QR code is not from Share to Phone.", Toast.LENGTH_LONG).show();
        }
    }
}
