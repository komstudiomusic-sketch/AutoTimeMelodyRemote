package com.example.autotimemelodyremote;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private LinearLayout connectLayout;
    private Button btnScan;
    private Button btnRescan;
    private TextView txtLastUrl;
    private SharedPreferences prefs;

    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> barcodeLauncher =
        registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) {
                String scannedUrl = result.getContents().trim();
                prefs.edit().putString("saved_url", scannedUrl).apply();
                loadWebPage(scannedUrl);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = findViewById(R.id.webView);
        connectLayout = findViewById(R.id.connectLayout);
        btnScan = findViewById(R.id.btnScan);
        btnRescan = findViewById(R.id.btnRescan);
        txtLastUrl = findViewById(R.id.txtLastUrl);
        prefs = getSharedPreferences("melody_remote_prefs", MODE_PRIVATE);

        checkSystemPermissions();
        setupWebView();

        btnScan.setOnClickListener(v -> startScanner());
        btnRescan.setOnClickListener(v -> {
            webView.setVisibility(View.GONE);
            btnRescan.setVisibility(View.GONE);
            connectLayout.setVisibility(View.VISIBLE);
            startScanner();
        });

        String savedUrl = prefs.getString("saved_url", null);
        if (savedUrl != null && !savedUrl.isEmpty()) {
            txtLastUrl.setText("URL ล่าสุด: " + savedUrl);
            loadWebPage(savedUrl);
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                MainActivity.this.runOnUiThread(() -> {
                    request.grant(request.getResources());
                });
            }
        });
    }

    private void loadWebPage(String url) {
        connectLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        btnRescan.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void startScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("หันกล้องไปที่ QR Code บนหน้าจอคอมพิวเตอร์");
        options.setBeepEnabled(true);
        options.setOrientationLocked(true);
        barcodeLauncher.launch(options);
    }

    private void checkSystemPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA
            }, 101);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE) {
            webView.setVisibility(View.GONE);
            btnRescan.setVisibility(View.GONE);
            connectLayout.setVisibility(View.VISIBLE);
        } else {
            super.onBackPressed();
        }
    }
}
