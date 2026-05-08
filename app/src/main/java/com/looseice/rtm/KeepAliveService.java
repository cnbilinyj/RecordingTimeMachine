package com.looseice.rtm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KeepAliveService extends Service {
    // ---------- 录音相关 ----------
    private AudioRecord recorder;
    private CircularBuffer buffer;
    private Thread recordThread;
    private volatile boolean running = false;
    private int cacheSec = 300;           // 默认5分钟
    private final int sampleRate = 44100;
    private final int bytesPerSec = sampleRate * 2;  // 16bit PCM

    // 音频焦点 & 唤醒锁
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private AudioManager.OnAudioFocusChangeListener focusChangeListener;

    // UI 更新 Handler (用于通知 Activity)
    private Handler mainHandler;
    private OnCacheUpdateListener updateListener;

    // Binder 供 Activity 绑定
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public KeepAliveService getService() {
            return KeepAliveService.this;
        }
    }

    // 回调接口，用于向 Activity 传递缓存大小变化
    public interface OnCacheUpdateListener {
        void onCacheUpdated(int kb, int seconds);
    }

    public void setOnCacheUpdateListener(OnCacheUpdateListener listener) {
        this.updateListener = listener;
    }

    // ---------- 保活相关：悬浮窗 ----------
    private WindowManager wm;
    private View overlay;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());

        // 1. 启动前台服务（必须5秒内调用）
        startForeground(1001, buildForegroundNotification());

        // 2. 申请唤醒锁（防止 CPU 休眠）
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp:RecordingLock");
        wakeLock.acquire();

        // 3. 初始化音频焦点管理
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        focusChangeListener = focusChange -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    pauseRecording();
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    resumeRecording();
                    break;
            }
        };

        // 4. 读取用户设置的缓存时长
        SharedPreferences prefs = getSharedPreferences("rtm", MODE_PRIVATE);
        int min = prefs.getInt("cache_min", 5);
        cacheSec = min * 60;

        // 5. 悬浮窗保活
        addOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("start_recording".equals(action)) {
                int audioSource = intent.getIntExtra("audio_source", MediaRecorder.AudioSource.MIC);
                startRecording(audioSource);
            } else if ("stop_recording".equals(action)) {
                stopRecording();
            } else if ("export_cache".equals(action)) {
                exportCache();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ---------- 录音控制方法（供 Activity 调用）----------
    public void startRecording(int audioSource) {
        if (running) return;
        // 请求音频焦点
        int focusResult = audioManager.requestAudioFocus(focusChangeListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 没有焦点也可以继续录音，但建议给用户提示
        }

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
        recordThread = new Thread(() -> {
            byte[] buf = new byte[finalMinBuf];
            while (running) {
                int len = recorder.read(buf, 0, buf.length);
                if (len > 0) {
                    byte[] data = new byte[len];
                    System.arraycopy(buf, 0, data, 0, len);
                    buffer.write(data);
                    // 通知 UI 更新缓存显示
                    notifyCacheUpdate();
                }
            }
        });
        recordThread.start();

        // 更新通知栏内容（可选）
        updateNotification("录音中...");
    }

    public void stopRecording() {
        if (!running) return;
        running = false;
        if (recordThread != null) {
            try { recordThread.join(100); } catch (InterruptedException e) { }
            recordThread = null;
        }
        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;
        }
        // 放弃音频焦点
        audioManager.abandonAudioFocus(focusChangeListener);
        updateNotification("已停止");
        notifyCacheUpdate();
    }

    public boolean isRecording() {
        return running;
    }

    public int getCacheSeconds() {
        return buffer == null ? 0 : buffer.available() / bytesPerSec;
    }

    public int getCacheKB() {
        return buffer == null ? 0 : buffer.available() / 1024;
    }

    // 导出当前缓存到 WAV 文件
    public void exportCache() {
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
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] wav = pcmToWav(pcm, sampleRate, 16, 1);
            fos.write(wav);
            Toast.makeText(this, "导出成功: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void pauseRecording() {
        if (running) {
            // 暂停录音：释放 AudioRecord，保留 buffer
            running = false;
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
            updateNotification("录音暂停（焦点丢失）");
        }
    }

    private void resumeRecording() {
        if (!running && buffer != null) {
            // 重新启动录音（使用相同音频源，需要保存上次的 audioSource，简化起见重新从 MIC 开始）
            // 更完善的方案需要保存 audioSource，这里默认使用 VOICE_RECOGNITION 或 MIC
            startRecording(MediaRecorder.AudioSource.MIC);
        }
    }

    // ---------- 辅助方法 ----------
    private void notifyCacheUpdate() {
        if (updateListener != null) {
            mainHandler.post(() -> updateListener.onCacheUpdated(getCacheKB(), getCacheSeconds()));
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

    // ---------- 前台通知 ----------
    private Notification buildForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "recording_channel", "录音保活", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, "recording_channel");
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("录音时光机")
                .setContentText("后台录音中")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentIntent(pi)
                .setOngoing(true);
        return builder.build();
    }

    private void updateNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, "recording_channel");
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("录音时光机")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setOngoing(true);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(1001, builder.build());
        }
    }

    // ---------- 悬浮窗（保活辅助）----------
    private void addOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        overlay = new View(this);
        overlay.setBackgroundColor(0x00000000);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = -1;
        params.y = -1;
        try {
            wm.addView(overlay, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void removeOverlay() {
        if (overlay != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDestroy() {
        stopRecording();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        removeOverlay();
        super.onDestroy();
    }
}