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
		final int finalMinBuf = minBuf;
        recordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[finalMinBuf];
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
