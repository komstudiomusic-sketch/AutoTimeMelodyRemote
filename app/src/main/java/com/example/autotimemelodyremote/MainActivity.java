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

    // ตั้งค่า 44,100 Hz (44.1 kHz - คุณภาพเสียงระดับ CD Audio)[cite: 9]
    private static final int SAMPLE_RATE = 44100;
    // ก้อนข้อมูล 8820 ไบต์ = 100ms ส่ง 10 ครั้ง/วินาที[cite: 9]
    private static final int CHUNK_SIZE = 8820;
    // จำกัดเวลาบันทึกสูงสุด 60 วินาที[cite: 9]
    private static final long MAX_RECORD_DURATION_MS = 60000;

    private WebView webView;
    private LinearLayout connectLayout;
    private Button btnScan;
    private Button btnRescan;
    private EditText edtIpUrl;
    private Button btnConnectManual;
    private TextView txtLastUrl;
    private SharedPreferences prefs;

    // Native Audio Engine[cite: 9]
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopRecordingRunnable = () -> {
        if (isRecording) {
            Toast.makeText(MainActivity.this, "บันทึกเสียงครบ 60 วินาทีแล้ว กำลังส่งออกอากาศ...", Toast.LENGTH_SHORT).show();[cite: 9]
            stopNativeAudio();
        }
    };

    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> barcodeLauncher =
        registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) {
                String scannedUrl = result.getContents().trim();[cite: 9]
                saveAndConnect(scannedUrl);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);[cite: 9]

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);[cite: 9]

        webView = findViewById(R.id.webView);[cite: 9]
        connectLayout = findViewById(R.id.connectLayout);[cite: 9]
        btnScan = findViewById(R.id.btnScan);[cite: 9]
        btnRescan = findViewById(R.id.btnRescan);[cite: 9]
        txtLastUrl = findViewById(R.id.txtLastUrl);[cite: 9]
        prefs = getSharedPreferences("melody_remote_prefs", MODE_PRIVATE);[cite: 9]

        setupManualInputUI();[cite: 9]
        setupWebView();[cite: 9]

        btnScan.setOnClickListener(v -> {
            if (hasRequiredPermissions()) {
                startScanner();
            } else {
                requestSystemPermissions();
            }
        });

        btnRescan.setOnClickListener(v -> {
            stopNativeAudio();
            webView.setVisibility(View.GONE);[cite: 9]
            btnRescan.setVisibility(View.GONE);[cite: 9]
            connectLayout.setVisibility(View.VISIBLE);[cite: 9]
        });

        if (!hasRequiredPermissions()) {
            requestSystemPermissions();
        } else {
            checkSavedUrlAndLoad();
        }
    }

    private void setupManualInputUI() {
        TextView txtOr = new TextView(this);[cite: 9]
        txtOr.setText("— หรือกรอก IP / ลิงก์ภายนอก —");[cite: 9]
        txtOr.setTextColor(Color.parseColor("#777777"));[cite: 9]
        txtOr.setGravity(Gravity.CENTER);[cite: 9]
        LinearLayout.LayoutParams paramsOr = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);[cite: 9]
        paramsOr.setMargins(0, 32, 0, 16);[cite: 9]
        connectLayout.addView(txtOr, paramsOr);[cite: 9]

        edtIpUrl = new EditText(this);[cite: 9]
        edtIpUrl.setHint("เช่น 192.168.1.100 หรือ https://...");[cite: 9]
        edtIpUrl.setHintTextColor(Color.parseColor("#666666"));[cite: 9]
        edtIpUrl.setTextColor(Color.WHITE);[cite: 9]
        edtIpUrl.setBackgroundColor(Color.parseColor("#222222"));[cite: 9]
        edtIpUrl.setPadding(30, 25, 30, 25);[cite: 9]
        edtIpUrl.setSingleLine(true);[cite: 9]
        LinearLayout.LayoutParams paramsEdt = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);[cite: 9]
        connectLayout.addView(edtIpUrl, paramsEdt);[cite: 9]

        btnConnectManual = new Button(this);[cite: 9]
        btnConnectManual.setText("เชื่อมต่อ");[cite: 9]
        btnConnectManual.setTextColor(Color.WHITE);[cite: 9]
        btnConnectManual.setBackgroundColor(Color.parseColor("#2E7D32"));[cite: 9]
        LinearLayout.LayoutParams paramsBtn = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);[cite: 9]
        paramsBtn.setMargins(0, 16, 0, 0);[cite: 9]
        connectLayout.addView(btnConnectManual, paramsBtn);[cite: 9]

        btnConnectManual.setOnClickListener(v -> {
            String input = edtIpUrl.getText().toString().trim();[cite: 9]
            if (input.isEmpty()) {
                Toast.makeText(this, "กรุณากรอก IP หรือ URL", Toast.LENGTH_SHORT).show();[cite: 9]
                return;
            }
            saveAndConnect(formatUrl(input));
        });
    }

    private String formatUrl(String input) {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input;[cite: 9]
        }
        if (input.contains(":")) {
            return "http://" + input;[cite: 9]
        } else {
            return "http://" + input + ":3000";[cite: 9]
        }
    }

    private void saveAndConnect(String url) {
        prefs.edit().putString("saved_url", url).apply();[cite: 9]
        txtLastUrl.setText("URL ล่าสุด: " + url);[cite: 9]
        loadWebPage(url);[cite: 9]
    }

    private void checkSavedUrlAndLoad() {
        String savedUrl = prefs.getString("saved_url", null);[cite: 9]
        if (savedUrl != null && !savedUrl.isEmpty()) {
            txtLastUrl.setText("URL ล่าสุด: " + savedUrl);[cite: 9]
            if (edtIpUrl != null) edtIpUrl.setText(savedUrl);[cite: 9]
            loadWebPage(savedUrl);[cite: 9]
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        WebSettings settings = webView.getSettings();[cite: 9]
        settings.setJavaScriptEnabled(true);[cite: 9]
        settings.setDomStorageEnabled(true);[cite: 9]
        settings.setDatabaseEnabled(true);[cite: 9]
        settings.setAllowFileAccess(true);[cite: 9]
        settings.setAllowContentAccess(true);[cite: 9]
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);[cite: 9]
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);[cite: 9]

        webView.addJavascriptInterface(new AndroidAudioBridge(), "AndroidAudio");[cite: 9]
        webView.setWebViewClient(new WebViewClient());[cite: 9]
    }

    private void loadWebPage(String url) {
        connectLayout.setVisibility(View.GONE);[cite: 9]
        webView.setVisibility(View.VISIBLE);[cite: 9]
        btnRescan.setVisibility(View.VISIBLE);[cite: 9]
        webView.loadUrl(url);[cite: 9]
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
            );[cite: 9]

            int internalBufferSize = Math.max(minBufSize, CHUNK_SIZE * 8);[cite: 9]

            // ใช้ MediaRecorder.AudioSource.MIC เพื่อเสียงที่เป็นธรรมชาติ ไม่อั้น
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    internalBufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                return;[cite: 9]
            }

            webView.post(() -> {
                webView.evaluateJavascript("if(window.prepareAudioBuffer){window.prepareAudioBuffer();}", null);[cite: 9]
            });

            audioRecord.startRecording();[cite: 9]
            isRecording = true;[cite: 9]

            timeoutHandler.removeCallbacks(stopRecordingRunnable);[cite: 9]
            timeoutHandler.postDelayed(stopRecordingRunnable, MAX_RECORD_DURATION_MS);[cite: 9]

            recordingThread = new Thread(() -> {
                byte[] audioBuffer = new byte[CHUNK_SIZE];[cite: 9]
                while (isRecording) {
                    int readBytes = audioRecord.read(audioBuffer, 0, audioBuffer.length);[cite: 9]
                    if (readBytes > 0) {
                        String base64Chunk = Base64.encodeToString(audioBuffer, 0, readBytes, Base64.NO_WRAP);[cite: 9]
                        
                        webView.post(() -> {
                            webView.evaluateJavascript(
                                "if(window.sendAudioChunk){window.sendAudioChunk('" + base64Chunk + "');}", 
                                null
                            );[cite: 9]
                        });
                    }
                }
            });

            recordingThread.setPriority(Thread.NORM_PRIORITY + 2);[cite: 9]
            recordingThread.start();[cite: 9]

        } catch (Exception e) {
            e.printStackTrace();[cite: 9]
        }
    }

    private synchronized void stopNativeAudio() {
        if (!isRecording) return;
        isRecording = false;[cite: 9]
        timeoutHandler.removeCallbacks(stopRecordingRunnable);[cite: 9]

        if (recordingThread != null) {
            try {
                recordingThread.join(300);[cite: 9]
            } catch (InterruptedException ignored) {}
            recordingThread = null;[cite: 9]
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();[cite: 9]
                audioRecord.release();[cite: 9]
            } catch (Exception ignored) {}
            audioRecord = null;[cite: 9]
        }

        webView.postDelayed(() -> {
            webView.evaluateJavascript("if(window.finishAudioRecording){window.finishAudioRecording();}", null);[cite: 9]
        }, 100);
    }

    private void startScanner() {
        ScanOptions options = new ScanOptions();[cite: 9]
        options.setPrompt("หันกล้องไปที่ QR Code บนหน้าจอคอมพิวเตอร์");[cite: 9]
        options.setBeepEnabled(true);[cite: 9]
        options.setOrientationLocked(true);[cite: 9]
        barcodeLauncher.launch(options);[cite: 9]
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;[cite: 9]
    }

    private void requestSystemPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
        }, PERMISSION_REQ_CODE);[cite: 9]
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;[cite: 9]
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;[cite: 9]
                    break;
                }
            }
            if (allGranted) {
                checkSavedUrlAndLoad();
            } else {
                Toast.makeText(this, "กรุณากดอนุญาตการใช้ไมโครโฟนและกล้อง", Toast.LENGTH_LONG).show();[cite: 9]
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
            webView.setVisibility(View.GONE);[cite: 9]
            btnRescan.setVisibility(View.GONE);[cite: 9]
            connectLayout.setVisibility(View.VISIBLE);[cite: 9]
        } else {
            super.onBackPressed();
        }
    }
}
