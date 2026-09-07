package com.example.autotimemelodyremote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Bundle;
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

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_SIZE = 3200;

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
    private AcousticEchoCanceler echoCanceler;
    private boolean isRecording = false;
    private Thread recordingThread;

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

        // สร้างช่องกรอก IP และปุ่มเชื่อมต่อแบบไดนามิก ป้องกัน Error จากไฟล์ XML
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
        webView.setWebViewClient(new WebViewClient());
    }

    private void loadWebPage(String url) {
        connectLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        btnRescan.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
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
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    internalBufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                return;
            }

            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
                    if (echoCanceler != null) {
                        echoCanceler.setEnabled(true);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            audioRecord.startRecording();
            isRecording = true;

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
        isRecording = false;
        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }

        if (echoCanceler != null) {
            try {
                echoCanceler.setEnabled(false);
                echoCanceler.release();
            } catch (Exception ignored) {}
            echoCanceler = null;
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
