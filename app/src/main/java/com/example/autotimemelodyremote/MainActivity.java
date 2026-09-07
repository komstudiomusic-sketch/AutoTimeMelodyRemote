package com.example.autotimemelodyremote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
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

    // ตั้งค่า 44,100 Hz (44.1 kHz - คุณภาพเสียงระดับ CD Audio)[cite: 2]
    private static final int SAMPLE_RATE = 44100;
    // ก้อนข้อมูล 8820 ไบต์ = 100ms ส่ง 10 ครั้ง/วินาที[cite: 2]
    private static final int CHUNK_SIZE = 8820;
    // จำกัดเวลาบันทึกสูงสุด 60 วินาที[cite: 2]
    private static final long MAX_RECORD_DURATION_MS = 60000;

    private WebView webView;
    private LinearLayout connectLayout;
    private Button btnScan;
    private Button btnRescan;
    private EditText edtIpUrl;
    private Button btnConnectManual;
    private TextView txtLastUrl;
    private SharedPreferences prefs;

    // Native Audio Engine
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopRecordingRunnable = () -> {
        if (isRecording) {
            Toast.makeText(MainActivity.this, "บันทึกเสียงครบ 60 วินาทีแล้ว กำลังส่งออกอากาศ...", Toast.LENGTH_SHORT).show();
            stopNativeAudio();
        }
    };

    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> barcodeLauncher =
        registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) {
                String scannedUrl = result.getContents().trim();
                saveAndConnect(scannedUrl);
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

        setupManualInputUI();
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
        });

        if (!hasRequiredPermissions()) {
            requestSystemPermissions();
        } else {
            checkSavedUrlAndLoad();
        }
    }

    private void setupManualInputUI() {
        TextView txtOr = new TextView(this);
        txtOr.setText("— หรือกรอก IP / ลิงก์ภายนอก —");
        txtOr.setTextColor(Color.parseColor("#777777"));
        txtOr.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams paramsOr = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsOr.setMargins(0, 32, 0, 16);
        connectLayout.addView(txtOr, paramsOr);

        edtIpUrl = new EditText(this);
        edtIpUrl.setHint("เช่น 192.168.1.100 หรือ https://...");
        edtIpUrl.setHintTextColor(Color.parseColor("#666666"));
        edtIpUrl.setTextColor(Color.WHITE);
        edtIpUrl.setBackgroundColor(Color.parseColor("#222222"));
        edtIpUrl.setPadding(30, 25, 30, 25);
        edtIpUrl.setSingleLine(true);
        LinearLayout.LayoutParams paramsEdt = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        connectLayout.addView(edtIpUrl, paramsEdt);

        btnConnectManual = new Button(this);
        btnConnectManual.setText("เชื่อมต่อ");
        btnConnectManual.setTextColor(Color.WHITE);
        btnConnectManual.setBackgroundColor(Color.parseColor("#2E7D32"));
        LinearLayout.LayoutParams paramsBtn = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsBtn.setMargins(0, 16, 0, 0);
        connectLayout.addView(btnConnectManual, paramsBtn);

        btnConnectManual.setOnClickListener(v -> {
            String input = edtIpUrl.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, "กรุณากรอก IP หรือ URL", Toast.LENGTH_SHORT).show();
                return;
            }
            saveAndConnect(formatUrl(input));
        });
    }

    private String formatUrl(String input) {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input;
        }
        if (input.contains(":")) {
            return "http://" + input;
        } else {
            return "http://" + input + ":3000";
        }
    }

    private void saveAndConnect(String url) {
        prefs.edit().putString("saved_url", url).apply();
        txtLastUrl.setText("URL ล่าสุด: " + url);
        loadWebPage(url);
    }

    private void checkSavedUrlAndLoad() {
        String savedUrl = prefs.getString("saved_url", null);
        if (savedUrl != null && !savedUrl.isEmpty()) {
            txtLastUrl.setText("URL ล่าสุด: " + savedUrl);
            if (edtIpUrl != null) edtIpUrl.setText(savedUrl);
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
        
        // ดักจับและระงับไม่ให้แสดงหน้าเว็บ Error ของเบราว์เซอร์[cite: 2]
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                view.stopLoading();
                view.loadUrl("about:blank");
                handleConnectionFailure();
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    view.stopLoading();
                    view.loadUrl("about:blank");
                    handleConnectionFailure();
                }
            }
        });
    }

    private void loadWebPage(String url) {
        connectLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        btnRescan.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void handleConnectionFailure() {
        runOnUiThread(() -> {
            if (webView.getVisibility() == View.VISIBLE) {
                stopNativeAudio();
                webView.setVisibility(View.GONE);
                if (btnRescan != null) btnRescan.setVisibility(View.GONE);
                if (connectLayout != null) connectLayout.setVisibility(View.VISIBLE);
                
                Toast.makeText(MainActivity.this, "ไม่พบเซิร์ฟเวอร์ กรุณาตรวจสอบการเชื่อมต่อ Wi-Fi หรือ IP", Toast.LENGTH_LONG).show();
            }
        });
    }

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

            int internalBufferSize = Math.max(minBufSize, CHUNK_SIZE * 8);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    internalBufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                return;
            }

            webView.post(() -> {
                webView.evaluateJavascript("if(window.prepareAudioBuffer){window.prepareAudioBuffer();}", null);
            });

            audioRecord.startRecording();
            isRecording = true;

            timeoutHandler.removeCallbacks(stopRecordingRunnable);
            timeoutHandler.postDelayed(stopRecordingRunnable, MAX_RECORD_DURATION_MS);

            recordingThread = new Thread(() -> {
                byte[] audioBuffer = new byte[CHUNK_SIZE];
                while (isRecording) {
                    int readBytes = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                    if (readBytes > 0) {
                        String base64Chunk = Base64.encodeToString(audioBuffer, 0, readBytes, Base64.NO_WRAP);
                        
                        webView.post(() -> {
                            webView.evaluateJavascript(
                                "if(window.sendAudioChunk){window.sendAudioChunk('" + base64Chunk + "');}", 
                                null
                            );
                        });
                    }
                }
            });

            recordingThread.setPriority(Thread.NORM_PRIORITY + 2);
            recordingThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void stopNativeAudio() {
        if (!isRecording) return;
        isRecording = false;
        timeoutHandler.removeCallbacks(stopRecordingRunnable);

        if (recordingThread != null) {
            try {
                recordingThread.join(300);
            } catch (InterruptedException ignored) {}
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        webView.postDelayed(() -> {
            webView.evaluateJavascript("if(window.finishAudioRecording){window.finishAudioRecording();}", null);
        }, 100);
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
