package com.looseice.rtm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;   // 导入 PowerManager
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements View.OnClickListener {
    private static final int REQUEST_RECORD_AUDIO = 1;
    private Button btnStart, btnStop, btnExport;
    private TextView tvStatus, tvCache;
    private EditText etCacheMin;
    private KeepAliveService recordingService;
    private boolean isBound = false;
    private SharedPreferences prefs;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            KeepAliveService.LocalBinder binder = (KeepAliveService.LocalBinder) service;
            recordingService = binder.getService();
            isBound = true;
            recordingService.setOnCacheUpdateListener((kb, seconds) -> {
                tvCache.setText(String.format("缓存: %d KB / %d 秒", kb, seconds));
                if (recordingService.isRecording()) {
                    tvStatus.setText("录音中");
                } else {
                    tvStatus.setText("已停止");
                }
            });
            if (recordingService.isRecording()) {
                btnStart.setEnabled(false);
                btnStop.setEnabled(true);
                tvStatus.setText("录音中");
                tvCache.setText(String.format("缓存: %d KB / %d 秒",
                        recordingService.getCacheKB(), recordingService.getCacheSeconds()));
            } else {
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
                tvStatus.setText("已停止");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recordingService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("rtm", MODE_PRIVATE);

        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnExport = findViewById(R.id.btn_export);
        tvStatus = findViewById(R.id.tv_status);
        tvCache = findViewById(R.id.tv_cache);
        etCacheMin = findViewById(R.id.et_cache_min);

        int savedMin = prefs.getInt("cache_min", 5);
        etCacheMin.setText(String.valueOf(savedMin));

        btnStart.setOnClickListener(this);
        btnStop.setOnClickListener(this);
        btnExport.setOnClickListener(this);

        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), 100);
        }

        // 引导关闭电池优化（可选）
        requestIgnoreBatteryOptimizations();

        checkPermissions();
    }

    // 引导用户关闭电池优化（API 23+）
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
            }
        }
    }

    private void checkPermissions() {
        // 删除 Android 13+ 通知权限处理，因为编译版本 28 不支持
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        } else {
            checkAccessibility();
        }
    }

    private void checkAccessibility() {
        String service = getPackageName() + "/" + RecordingAccessibilityService.class.getName();
        boolean enabled = false;
        try {
            String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled = enabledServices != null && enabledServices.contains(service);
        } catch (Exception e) {}
        if (enabled) {
            if (isBound && recordingService != null && !recordingService.isRecording()) {
                recordingService.startRecording(MediaRecorder.AudioSource.VOICE_RECOGNITION);
                btnStart.setEnabled(false);
                btnStop.setEnabled(true);
            }
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("启用无障碍服务");
            builder.setMessage("开启后可与其它录音App并行工作，是否前往设置开启？\n否则将使用普通录音模式（可能冲突）。");
            builder.setPositiveButton("去设置", (dialog, which) -> startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 2));
            builder.setNegativeButton("普通模式", (dialog, which) -> {
                if (isBound && recordingService != null && !recordingService.isRecording()) {
                    recordingService.startRecording(MediaRecorder.AudioSource.MIC);
                    btnStart.setEnabled(false);
                    btnStop.setEnabled(true);
                }
            });
            builder.show();
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_start) {
            checkAccessibility();
        } else if (id == R.id.btn_stop) {
            if (isBound && recordingService != null) {
                recordingService.stopRecording();
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
                tvStatus.setText("已停止");
            }
        } else if (id == R.id.btn_export) {
            if (isBound && recordingService != null) {
                recordingService.exportCache();
            } else {
                Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2) {
            checkAccessibility();
        } else if (requestCode == 100) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
            }
        }
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
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}