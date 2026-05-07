#!/system/bin/sh
BASE="/sdcard/AppProjects/录音时光机"
JAVA_DIR="$BASE/app/src/main/java/com/looseice/rtm"
RES_LAYOUT="$BASE/app/src/main/res/layout"
RES_VALUES="$BASE/app/src/main/res/values"
RES_XML="$BASE/app/src/main/res/xml"

mkdir -p "$JAVA_DIR" "$RES_LAYOUT" "$RES_VALUES" "$RES_XML"

# 清空旧文件
rm -f "$JAVA_DIR"/*.java "$RES_LAYOUT/activity_main.xml" "$RES_VALUES/strings.xml" "$BASE/app/src/main/AndroidManifest.xml" "$RES_XML/accessibility_service_config.xml"

# 1. app/build.gradle (使用最小 SDK，无 AndroidX 依赖)
cat > "$BASE/app/build.gradle" <<'EOF'
apply plugin: 'com.android.application'

android {
    compileSdkVersion 28
    buildToolsVersion "28.0.3"

    defaultConfig {
        applicationId "com.looseice.rtm"
        minSdkVersion 21
        targetSdkVersion 28
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_7
        targetCompatibility JavaVersion.VERSION_1_7
    }
}

dependencies {
    implementation 'com.android.support:appcompat-v7:28.0.0'
}
EOF

# 2. AndroidManifest.xml
cat > "$BASE/app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.looseice.rtm">
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.AppCompat.Light">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <service
            android:name=".RecordingAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService"/>
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config"/>
        </service>
    </application>
</manifest>
EOF

# 3. strings.xml
cat > "$RES_VALUES/strings.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">录音时光机</string>
    <string name="accessibility_service_description">录音时光机无障碍服务</string>
</resources>
EOF

# 4. accessibility_service_config.xml
cat > "$RES_XML/accessibility_service_config.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100" />
EOF

# 5. activity_main.xml
cat > "$RES_LAYOUT/activity_main.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">
    <TextView
        android:id="@+id/tv_status"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="未录音"
        android:gravity="center"
        android:textSize="18sp"
        android:background="#E0E0E0"
        android:padding="8dp"
        android:layout_marginBottom="16dp"/>
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginBottom="24dp">
        <Button
            android:id="@+id/btn_start"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="开始录音"
            android:layout_marginEnd="8dp"/>
        <Button
            android:id="@+id/btn_stop"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="停止录音"
            android:enabled="false"/>
    </LinearLayout>
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="设置"
        android:textSize="18sp"
        android:textStyle="bold"
        android:layout_marginBottom="12dp"/>
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginBottom="12dp">
        <TextView
            android:layout_width="0dp"
            android:layout_weight="1"
            android:text="缓存时长(分钟):"
            android:layout_height="wrap_content"/>
        <EditText
            android:id="@+id/et_cache_min"
            android:layout_width="100dp"
            android:layout_height="wrap_content"
            android:inputType="number"
            android:text="5"/>
    </LinearLayout>
    <Button
        android:id="@+id/btn_export"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="导出缓存"/>
    <TextView
        android:id="@+id/tv_cache"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:padding="8dp"
        android:background="#E0E0E0"
        android:layout_marginTop="24dp"
        android:text="缓存: 0 KB / 0 秒"/>
</LinearLayout>
EOF

# 6. CircularBuffer.java
cat > "$JAVA_DIR/CircularBuffer.java" <<'EOF'
package com.looseice.rtm;
public class CircularBuffer {
    private byte[] buf;
    private int writePos;
    private int avail;
    private final int max;
    public CircularBuffer(int maxBytes) {
        this.max = maxBytes;
        this.buf = new byte[maxBytes];
        this.writePos = 0;
        this.avail = 0;
    }
    public synchronized void write(byte[] data) {
        if (data.length >= max) {
            System.arraycopy(data, data.length - max, buf, 0, max);
            avail = max;
            writePos = 0;
            return;
        }
        int first = Math.min(data.length, max - writePos);
        System.arraycopy(data, 0, buf, writePos, first);
        if (first < data.length) {
            int second = data.length - first;
            System.arraycopy(data, first, buf, 0, second);
            writePos = second;
        } else {
            writePos += first;
        }
        avail += data.length;
        if (avail > max) avail = max;
        if (writePos >= max) writePos = 0;
    }
    public synchronized byte[] readAll() {
        if (avail <= 0) return null;
        byte[] result = new byte[avail];
        if (writePos >= avail) {
            System.arraycopy(buf, writePos - avail, result, 0, avail);
        } else {
            int first = avail - writePos;
            System.arraycopy(buf, writePos, result, 0, first);
            System.arraycopy(buf, 0, result, first, writePos);
        }
        return result;
    }
    public synchronized int available() { return avail; }
}
EOF

# 7. RecordingAccessibilityService.java (最小实现)
cat > "$JAVA_DIR/RecordingAccessibilityService.java" <<'EOF'
package com.looseice.rtm;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
public class RecordingAccessibilityService extends AccessibilityService {
    public static RecordingAccessibilityService instance;
    @Override
    public void onCreate() { super.onCreate(); instance = this; }
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override
    public void onInterrupt() {}
    @Override
    public void onDestroy() { instance = null; super.onDestroy(); }
}
EOF

# 8. MainActivity.java (完整功能，不依赖 AndroidX)
cat > "$JAVA_DIR/MainActivity.java" <<'EOF'
package com.looseice.rtm;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1;
    private Button btnStart, btnStop, btnExport;
    private TextView tvStatus, tvCache;
    private EditText etCacheMin;
    private AudioRecord recorder;
    private CircularBuffer buffer;
    private Thread recordThread;
    private volatile boolean running = false;
    private SharedPreferences prefs;
    private Handler handler;
    private int cacheSec = 300;
    private final int sampleRate = 44100;
    private final int bytesPerSec = sampleRate * 2;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("rtm", MODE_PRIVATE);
        handler = new Handler();
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnExport = findViewById(R.id.btn_export);
        tvStatus = findViewById(R.id.tv_status);
        tvCache = findViewById(R.id.tv_cache);
        etCacheMin = findViewById(R.id.et_cache_min);
        int savedMin = prefs.getInt("cache_min", 5);
        etCacheMin.setText(String.valueOf(savedMin));
        cacheSec = savedMin * 60;
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startRecording(); }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { stopRecording(); }
        });
        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { exportCache(); }
        });
        checkPermissions();
    }
    private void checkPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        } else {
            checkAccessibility();
        }
    }
    private void checkAccessibility() {
        String service = getPackageName() + "/" + RecordingAccessibilityService.class.getCanonicalName();
        boolean enabled = false;
        try {
            String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled = enabledServices != null && enabledServices.contains(service);
        } catch (Exception e) {}
        if (enabled) {
            startRecordingWithSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        } else {
            new AlertDialog.Builder(this)
                .setTitle("启用无障碍服务")
                .setMessage("开启后可与其它录音App并行工作，是否前往设置开启？\n否则将使用普通录音模式（可能冲突）。")
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 2);
                    }
                })
                .setNegativeButton("普通模式", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startRecordingWithSource(MediaRecorder.AudioSource.MIC);
                    }
                })
                .show();
        }
    }
    private void startRecordingWithSource(int audioSource) {
        try {
            int min = Integer.parseInt(etCacheMin.getText().toString());
            if (min < 1) min = 1;
            if (min > 60) min = 60;
            cacheSec = min * 60;
            prefs.edit().putInt("cache_min", min).apply();
        } catch (Exception e) {}
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBuf < bytesPerSec) minBuf = bytesPerSec;
        recorder = new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, minBuf);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "录音初始化失败", Toast.LENGTH_SHORT).show();
            return;
        }
        buffer = new CircularBuffer(cacheSec * bytesPerSec);
        recorder.startRecording();
        running = true;
        recordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[minBuf];
                while (running) {
                    int len = recorder.read(buf, 0, buf.length);
                    if (len > 0) {
                        byte[] data = new byte[len];
                        System.arraycopy(buf, 0, data, 0, len);
                        buffer.write(data);
                    }
                }
            }
        });
        recordThread.start();
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        tvStatus.setText("录音中");
        startUpdater();
    }
    private void startRecording() {
        checkAccessibility();
    }
    private void stopRecording() {
        running = false;
        if (recordThread != null) {
            try { recordThread.join(100); } catch (InterruptedException e) {}
            recordThread = null;
        }
        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;
        }
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        tvStatus.setText("已停止");
        updateCacheDisplay();
    }
    private void exportCache() {
        if (buffer == null || buffer.available() == 0) {
            Toast.makeText(this, "无缓存数据", Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] pcm = buffer.readAll();
        if (pcm == null || pcm.length == 0) return;
        String fileName = "sc_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date())
                + "_" + (pcm.length / bytesPerSec) + "s.wav";
        File dir = new File(Environment.getExternalStorageDirectory(), "录音时光机");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, fileName);
        try {
            FileOutputStream fos = new FileOutputStream(file);
            byte[] wav = pcmToWav(pcm, sampleRate, 16, 1);
            fos.write(wav);
            fos.close();
            Toast.makeText(this, "导出成功: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private byte[] pcmToWav(byte[] pcm, int sampleRate, int bits, int channels) {
        int byteRate = sampleRate * channels * (bits / 8);
        int blockAlign = channels * (bits / 8);
        int dataSize = pcm.length;
        int totalSize = 36 + dataSize;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write("RIFF".getBytes());
            out.write(intToLE(totalSize));
            out.write("WAVE".getBytes());
            out.write("fmt ".getBytes());
            out.write(intToLE(16));
            out.write(shortToLE((short) 1));
            out.write(shortToLE((short) channels));
            out.write(intToLE(sampleRate));
            out.write(intToLE(byteRate));
            out.write(shortToLE((short) blockAlign));
            out.write(shortToLE((short) bits));
            out.write("data".getBytes());
            out.write(intToLE(dataSize));
            out.write(pcm);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
    private byte[] intToLE(int val) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4);
        bb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putInt(val);
        return bb.array();
    }
    private byte[] shortToLE(short val) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(2);
        bb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putShort(val);
        return bb.array();
    }
    private void startUpdater() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    updateCacheDisplay();
                    handler.postDelayed(this, 1000);
                }
            }
        });
    }
    private void updateCacheDisplay() {
        if (buffer != null) {
            int kb = buffer.available() / 1024;
            int sec = buffer.available() / bytesPerSec;
            tvCache.setText(String.format(Locale.US, "缓存: %d KB / %d 秒", kb, sec));
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2) checkAccessibility();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkAccessibility();
        } else {
            Toast.makeText(this, "需要录音权限", Toast.LENGTH_LONG).show();
            finish();
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
    }
}
EOF

echo "所有文件已生成。现在请在 AIDE Pro 中执行："
echo "1. Build -> Clean Project"
echo "2. Build -> Rebuild Project"
echo "如果编译成功，安装后请授予录音和存储权限，并根据提示启用无障碍服务即可实现并行录音。"