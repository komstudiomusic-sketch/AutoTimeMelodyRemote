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
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
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

import java.io.ByteArrayOutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQ_CODE = 1001;

    // โดเมนหลักของระบบ Cloud Relay Hub
    private static final String CLOUD_DOMAIN = "https://atm-v2.duckdns.org";

    // ตั้งค่า 44,100 Hz (44.1 kHz - คุณภาพเสียงระดับ CD Audio)
    private static final int SAMPLE_RATE = 44100;
    // ก้อนข้อมูลบัฟเฟอร์การอ่าน
    private static final int CHUNK_SIZE = 8820;
    // จำกัดเวลาบันทึกสูงสุด 60 วินาที
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
    private ByteArrayOutputStream pcmAudioStream;
    private boolean currentAutoChimePref = true;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopRecordingRunnable = () -> {
        if (isRecording) {
            Toast.makeText(MainActivity.this, "บันทึกเสียงครบ 60 วินาทีแล้ว กำลังส่งออกอากาศ...", Toast.LENGTH_SHORT).show();
            stopNativeAudio(currentAutoChimePref);
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
            cancelNativeAudio();
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
        txtOr.setText("— หรือพิมพ์รหัสโรงเรียน 6 หลัก / ลิงก์ —");
        txtOr.setTextColor(Color.parseColor("#777777"));
        txtOr.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams paramsOr = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsOr.setMargins(0, 32, 0, 16);
        connectLayout.addView(txtOr, paramsOr);

        edtIpUrl = new EditText(this);
        edtIpUrl.setHint("เช่น 8FA42B หรือ https://...");
        edtIpUrl.setHintTextColor(Color.parseColor("#666666"));
        edtIpUrl.setTextColor(Color.WHITE);
        edtIpUrl.setBackgroundColor(Color.parseColor("#222222"));
        edtIpUrl.setPadding(30, 25, 30, 25);
        edtIpUrl.setSingleLine(true);
        LinearLayout.LayoutParams paramsEdt = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        connectLayout.addView(edtIpUrl, paramsEdt);

        btnConnectManual = new Button(this);
        btnConnectManual.setText("เชื่อมต่อระบบ");
        btnConnectManual.setTextColor(Color.WHITE);
        btnConnectManual.setBackgroundColor(Color.parseColor("#2E7D32"));
        LinearLayout.LayoutParams paramsBtn = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsBtn.setMargins(0, 16, 0, 0);
        connectLayout.addView(btnConnectManual, paramsBtn);

        btnConnectManual.setOnClickListener(v -> {
            String input = edtIpUrl.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, "กรุณากรอกรหัสโรงเรียนหรือ URL", Toast.LENGTH_SHORT).show();
                return;
            }
            saveAndConnect(formatUrl(input));
        });
    }

    /**
     * แปลงข้อความที่พิมพ์ให้เป็น URL คลาวด์ที่ถูกต้อง
     * - ถ้าพิมพ์รหัส 6 หลัก (เช่น 8FA42B) -> https://atm-v2.duckdns.org/?code=8FA42B
     * - ถ้าพิมพ์ URL ตัวเต็ม -> ใช้ค่านั้นทันที
     */
    private String formatUrl(String input) {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input;
        }

        // ปรับเป็น ?code= ให้สอดคล้องกับ main.js และ index.html ล่าสุด
        if (input.matches("^[a-zA-Z0-9]{6}$")) {
            return CLOUD_DOMAIN + "/?code=" + input.toUpperCase();
        }

        if (input.contains("duckdns.org") || input.contains(".")) {
            return "https://" + input;
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
        settings.setMediaPlaybackRequiresUserGesture(false);

        // รองรับการขอสิทธิ์ไมโครโฟนบนหน้าเว็บผ่าน WebRTC/WebAudio อัตโนมัติ
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.addJavascriptInterface(new AndroidAudioBridge(), "AndroidAudio");
        
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
                cancelNativeAudio();
                webView.setVisibility(View.GONE);
                if (btnRescan != null) btnRescan.setVisibility(View.GONE);
                if (connectLayout != null) connectLayout.setVisibility(View.VISIBLE);
                
                Toast.makeText(MainActivity.this, "ไม่สามารถเชื่อมต่อเซิร์ฟเวอร์ได้ กรุณาตรวจสอบอินเทอร์เน็ต", Toast.LENGTH_LONG).show();
            }
        });
    }

    public class AndroidAudioBridge {
        @JavascriptInterface
        public void startRecording() {
            startNativeAudio();
        }

        // กรณีเรียกแบบเดิมไม่ระบุพารามิเตอร์ (Default ให้เปิด Auto Chime)
        @JavascriptInterface
        public void stopRecording() {
            stopNativeAudio(true);
        }

        // รองรับรับค่า isAutoChime โดยตรงจากหน้าเว็บ
        @JavascriptInterface
        public void stopRecording(boolean isAutoChime) {
            stopNativeAudio(isAutoChime);
        }

        @JavascriptInterface
        public void cancelRecording() {
            cancelNativeAudio();
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

            pcmAudioStream = new ByteArrayOutputStream();

            webView.post(() -> {
                webView.evaluateJavascript("if(window.socket){window.socket.emit('remote-command', {action:'mic-status', active:true});}", null);
            });

            audioRecord.startRecording();
            isRecording = true;

            timeoutHandler.removeCallbacks(stopRecordingRunnable);
            timeoutHandler.postDelayed(stopRecordingRunnable, MAX_RECORD_DURATION_MS);

            recordingThread = new Thread(() -> {
                byte[] audioBuffer = new byte[CHUNK_SIZE];
                while (isRecording) {
                    int readBytes = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                    if (readBytes > 0 && pcmAudioStream != null) {
                        synchronized (pcmAudioStream) {
                            pcmAudioStream.write(audioBuffer, 0, readBytes);
                        }
                    }
                }
            });

            recordingThread.setPriority(Thread.NORM_PRIORITY + 2);
            recordingThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void stopNativeAudio(boolean isAutoChime) {
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

        if (pcmAudioStream != null) {
            byte[] rawPcmData = pcmAudioStream.toByteArray();
            pcmAudioStream = null;

            if (rawPcmData.length > 0) {
                // แปลงข้อมูล PCM 16-bit เป็นไฟล์ WAV พร้อม Header เพื่อให้ Web Audio ถอดรหัสได้ 100%
                byte[] wavData = addWavHeader(rawPcmData, SAMPLE_RATE, 1, 16);
                String base64Wav = Base64.encodeToString(wavData, Base64.NO_WRAP);

                // ส่งข้อมูลเสียงเข้าสู่ WebSocket ของหน้าเว็บ ส่งตรงเข้า Cloud Relay Server ทันที
                webView.post(() -> {
                    String script = String.format(
                        "(function() {" +
                        "   const b64 = '%s';" +
                        "   const binary = atob(b64);" +
                        "   const bytes = new Uint8Array(binary.length);" +
                        "   for (let i = 0; i < binary.length; i++) { bytes[i] = binary.charCodeAt(i); }" +
                        "   if (window.socket) {" +
                        "       window.socket.emit('voice-file-complete', {" +
                        "           audio: bytes.buffer," +
                        "           autoChime: %b" +
                        "       });" +
                        "   }" +
                        "   if (typeof resetSpeakingUI === 'function') resetSpeakingUI();" +
                        "})();",
                        base64Wav, isAutoChime
                    );
                    webView.evaluateJavascript(script, null);
                });
            }
        }
    }

    private synchronized void cancelNativeAudio() {
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

        pcmAudioStream = null;

        webView.post(() -> {
            webView.evaluateJavascript(
                "if (window.socket) { window.socket.emit('remote-command', {action:'mic-status', active:false}); }" +
                "if (typeof resetSpeakingUI === 'function') resetSpeakingUI();",
                null
            );
        });
    }

    // ฟังก์ชันสร้าง Header ไฟล์ WAV (RIFF standard 44.1kHz Mono 16-bit)
    private byte[] addWavHeader(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int totalDataLen = pcmData.length + 36;
        int totalAudioLen = pcmData.length;

        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * (bitsPerSample / 8)); header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        byte[] wavOutput = new byte[44 + pcmData.length];
        System.arraycopy(header, 0, wavOutput, 0, 44);
        System.arraycopy(pcmData, 0, wavOutput, 44, pcmData.length);
        return wavOutput;
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
        cancelNativeAudio();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE) {
            cancelNativeAudio();
            webView.setVisibility(View.GONE);
            btnRescan.setVisibility(View.GONE);
            connectLayout.setVisibility(View.VISIBLE);
        } else {
            super.onBackPressed();
        }
    }
}
