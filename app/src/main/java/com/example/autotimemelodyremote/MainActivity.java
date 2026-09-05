package com.example.autotimemelodyremote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 1001;
    // ปรับความละเอียดเสียงไมค์เป็น 44.1 kHz (CD Quality) คมชัดระดับโปร
    private static final int SAMPLE_RATE = 44100;

    private WebView webView;
    private LinearLayout connectLayout;
    private Button btnScan;
    private Button btnRescan;
    private TextView txtLastUrl;
    private SharedPreferences prefs;

    // Native Audio Engine
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;

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

        setupWebView();

        btnScan.setOnClickListener(v -> {
            if (hasRequiredPermissions()) {
                startScanner();
            } else {
                requestSystemPermissions();
            }
        });

        btnRescan.setOnClickListener(v -> {
            stopNativeAudio();
            webView.setVisibility(View.GONE);
            btnRescan.setVisibility(View.GONE);
            connectLayout.setVisibility(View.VISIBLE);
            if (hasRequiredPermissions()) {
                startScanner();
            } else {
                requestSystemPermissions();
            }
        });

        if (!hasRequiredPermissions()) {
            requestSystemPermissions();
        } else {
            checkSavedUrlAndLoad();
        }
    }

    private void checkSavedUrlAndLoad() {
        String savedUrl = prefs.getString("saved_url", null);
        if (savedUrl != null && !savedUrl.isEmpty()) {
            txtLastUrl.setText("URL ล่าสุด: " + savedUrl);
            loadWebPage(savedUrl);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new AndroidAudioBridge(), "AndroidAudio");
        webView.setWebViewClient(new WebViewClient());
    }

    private void loadWebPage(String url) {
        connectLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        btnRescan.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    // ================= Native Audio Logic =================
    public class AndroidAudioBridge {
        @JavascriptInterface
        public void startRecording() {
            startNativeAudio();
        }

        @JavascriptInterface
        public void stopRecording() {
            stopNativeAudio();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void startNativeAudio() {
        if (isRecording) return;
        if (!hasRequiredPermissions()) return;

        try {
            int minBufSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBufSize, 4096)
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                return;
            }

            audioRecord.startRecording();
            isRecording = true;

            recordingThread = new Thread(() -> {
                // ก้อนข้อมูล 2048 bytes ที่ 44.1kHz จะให้ latency ต่ำมากเพียง ~23ms
                byte[] audioBuffer = new byte[2048];
                while (isRecording) {
                    int readBytes = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                    if (readBytes > 0) {
                        String base64Chunk = Base64.encodeToString(audioBuffer, 0, readBytes, Base64.NO_WRAP);
                        runOnUiThread(() -> {
                            webView.evaluateJavascript("if(window.sendAudioChunk){window.sendAudioChunk('" + base64Chunk + "');}", null);
                        });
                    }
                }
            });
            recordingThread.setPriority(Thread.MAX_PRIORITY);
            recordingThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void stopNativeAudio() {
        isRecording = false;
        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
    }

    private void startScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("หันกล้องไปที่ QR Code บนหน้าจอคอมพิวเตอร์");
        options.setBeepEnabled(true);
        options.setOrientationLocked(true);
        barcodeLauncher.launch(options);
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestSystemPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
        }, PERMISSION_REQ_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                checkSavedUrlAndLoad();
            } else {
                Toast.makeText(this, "กรุณากดอนุญาตการใช้ไมโครโฟนและกล้อง", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopNativeAudio();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE) {
            stopNativeAudio();
            webView.setVisibility(View.GONE);
            btnRescan.setVisibility(View.GONE);
            connectLayout.setVisibility(View.VISIBLE);
        } else {
            super.onBackPressed();
        }
    }
}
