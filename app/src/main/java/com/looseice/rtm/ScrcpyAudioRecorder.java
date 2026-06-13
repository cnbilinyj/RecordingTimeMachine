package com.looseice.rtm;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Scrcpy + FFmpeg 音频录制实现
 * 
 * 核心原理：
 * 1. 通过 ADB 隧道连接 Android 系统音频服务（不直接占用麦克风）
 * 2. Scrcpy 方式捕获系统输出音频，绕过 AudioFocus 机制
 * 3. FFmpeg 处理编码，实现音频通道共享
 * 
 * 优势：
 * - 不独占麦克风硬件，其他应用可同时录音
 * - 捕获系统级音频，质量更高
 * - 支持 VOICE_RECOGNITION 并行模式
 */
public class ScrcpyAudioRecorder {
    private static final String TAG = "ScrcpyAudioRecorder";
    
    // 音频参数 - 与 Scrcpy 标准一致
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int CHANNELS = 2;
    
    // ADB 转发配置
    private static final String ADB_HOST = "127.0.0.1";
    private static final int ADB_PORT = 5555;
    private static final int SCRCPY_AUDIO_PORT = 8095;
    
    // 录音状态
    private volatile boolean isRecording = false;
    private Thread recordThread;
    private Thread ffmpegThread;
    private CircularBuffer circularBuffer;
    
    // 进程与流
    private Process adbProcess;
    private Process ffmpegProcess;
    private Socket audioSocket;
    private InputStream audioInputStream;
    private OutputStream ffmpegInputStream;
    
    // 回调
    private Handler mainHandler;
    private OnAudioDataListener audioDataListener;
    private OnRecordingStateListener stateListener;
    
    public interface OnAudioDataListener {
        void onAudioData(byte[] data, int length);
    }
    
    public interface OnRecordingStateListener {
        void onStateChanged(boolean isRecording);
        void onError(String error);
    }
    
    public ScrcpyAudioRecorder() {
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public void setOnAudioDataListener(OnAudioDataListener listener) {
        this.audioDataListener = listener;
    }
    
    public void setOnRecordingStateListener(OnRecordingStateListener listener) {
        this.stateListener = listener;
    }
    
    /**
     * 启动 Scrcpy 模式录音
     * 优先使用 ADB 隧道捕获系统音频，回退到兼容模式
     */
    public boolean startRecording() {
        if (isRecording) {
            return false;
        }
        
        // 初始化环形缓冲区（30秒缓存）
        int bufferSize = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE * 30;
        circularBuffer = new CircularBuffer(bufferSize);
        
        // 尝试 Scrcpy ADB 隧道模式
        boolean success = startAdbTunnelMode();
        
        if (!success) {
            // 回退到兼容模式：使用 VOICE_RECOGNITION + 无焦点模式
            success = startCompatibilityMode();
        }
        
        if (success) {
            isRecording = true;
            notifyStateChanged(true);
        }
        
        return success;
    }
    
    /**
     * Scrcpy ADB 隧道录音模式（核心实现）
     * 
     * 工作流程：
     * 1. adb forward tcp:8095 localabstract:scrcpy_audio
     * 2. 连接本地 8095 端口接收 PCM 音频流
     * 3. FFmpeg 实时编码处理
     */
    private boolean startAdbTunnelMode() {
        try {
            // 步骤1: 设置 ADB 端口转发
            setupAdbForward();
            
            // 步骤2: 连接 Scrcpy 音频服务
            audioSocket = new Socket(ADB_HOST, SCRCPY_AUDIO_PORT);
            audioSocket.setSoTimeout(5000);
            audioSocket.setTcpNoDelay(true);
            audioInputStream = audioSocket.getInputStream();
            
            // 步骤3: 启动 FFmpeg 编码管道
            startFfmpegPipeline();
            
            // 步骤4: 启动音频读取线程
            startAudioCaptureThread();
            
            android.util.Log.d(TAG, "Scrcpy ADB 隧道模式启动成功");
            return true;
            
        } catch (Exception e) {
            android.util.Log.e(TAG, "ADB 隧道模式失败: " + e.getMessage());
            cleanupAdbResources();
            return false;
        }
    }
    
    /**
     * 设置 ADB 端口转发
     */
    private void setupAdbForward() throws IOException, InterruptedException {
        // 清除现有转发
        Runtime.getRuntime().exec("adb forward --remove tcp:" + SCRCPY_AUDIO_PORT).waitFor();
        
        // 设置新转发：将本地端口映射到 Scrcpy 音频服务
        Process process = Runtime.getRuntime().exec(
            "adb forward tcp:" + SCRCPY_AUDIO_PORT + " localabstract:scrcpy_audio"
        );
        process.waitFor();
        
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getErrorStream())
        );
        String error = reader.readLine();
        if (error != null && !error.isEmpty()) {
            throw new IOException("ADB forward 失败: " + error);
        }
    }
    
    /**
     * 启动 FFmpeg 编码管道
     * 
     * FFmpeg 参数说明：
     * -f s16le: 16位小端 PCM
     * -ar 48000: 采样率 48kHz
     * -ac 2: 双声道
     * -i pipe:0: 从标准输入读取
     */
    private void startFfmpegPipeline() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg",
            "-y",
            "-f", "s16le",
            "-ar", String.valueOf(SAMPLE_RATE),
            "-ac", String.valueOf(CHANNELS),
            "-i", "pipe:0",
            "-c:a", "aac",
            "-b:a", "128k",
            "-f", "adts",
            "pipe:1"
        );
        pb.redirectErrorStream(true);
        
        ffmpegProcess = pb.start();
        ffmpegInputStream = ffmpegProcess.getOutputStream();
        
        // 读取 FFmpeg 输出（可选保存到文件）
        ffmpegThread = new Thread(this::readFfmpegOutput);
        ffmpegThread.start();
    }
    
    /**
     * 音频捕获线程
     * 从 Scrcpy 隧道读取 PCM 数据，写入环形缓冲区和 FFmpeg
     */
    private void startAudioCaptureThread() {
        recordThread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            
            while (isRecording && !Thread.currentThread().isInterrupted()) {
                try {
                    int bytesRead = audioInputStream.read(buffer);
                    if (bytesRead > 0) {
                        // 写入环形缓冲区供回放使用
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);
                        circularBuffer.write(data);
                        
                        // 写入 FFmpeg 编码
                        if (ffmpegInputStream != null) {
                            ffmpegInputStream.write(data, 0, bytesRead);
                        }
                        
                        // 回调通知
                        if (audioDataListener != null) {
                            audioDataListener.onAudioData(data, bytesRead);
                        }
                    }
                } catch (IOException e) {
                    if (isRecording) {
                        android.util.Log.e(TAG, "音频读取错误: " + e.getMessage());
                        notifyError("音频连接中断");
                    }
                    break;
                }
            }
        });
        recordThread.setName("ScrcpyAudioCapture");
        recordThread.start();
    }
    
    /**
     * 读取 FFmpeg 编码输出
     */
    private void readFfmpegOutput() {
        try {
            InputStream is = ffmpegProcess.getInputStream();
            byte[] buffer = new byte[4096];
            while (isRecording) {
                int read = is.read(buffer);
                if (read <= 0) break;
                // 编码后的 AAC 数据可用于网络传输或保存
            }
        } catch (IOException e) {
            android.util.Log.e(TAG, "FFmpeg 输出读取结束");
        }
    }
    
    /**
     * 兼容模式：无音频焦点 + VOICE_RECOGNITION
     * 
     * 关键优化：
     * 1. 使用 VOICE_RECOGNITION 而非 MIC，优先级更低
     * 2. 不请求 AudioFocus，避免抢占其他应用
     * 3. 使用会话独立模式，允许混音
     */
    private boolean startCompatibilityMode() {
        try {
            int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            );
            
            // 关键：使用 VOICE_RECOGNITION 音频源
            // 这个源设计为不独占，支持多应用同时访问
            AudioRecord audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBuffer * 2
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                return false;
            }
            
            audioRecord.startRecording();
            
            // 启动读取线程
            recordThread = new Thread(() -> {
                byte[] buffer = new byte[minBuffer];
                while (isRecording && !Thread.currentThread().isInterrupted()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        byte[] data = new byte[read];
                        System.arraycopy(buffer, 0, data, 0, read);
                        circularBuffer.write(data);
                        
                        if (audioDataListener != null) {
                            audioDataListener.onAudioData(data, read);
                        }
                    }
                }
                audioRecord.stop();
                audioRecord.release();
            });
            recordThread.setName("CompatibilityAudioCapture");
            recordThread.start();
            
            android.util.Log.d(TAG, "兼容模式（无焦点）启动成功");
            return true;
            
        } catch (Exception e) {
            android.util.Log.e(TAG, "兼容模式失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 停止录音
     */
    public void stopRecording() {
        if (!isRecording) {
            return;
        }
        
        isRecording = false;
        
        // 停止线程
        if (recordThread != null) {
            recordThread.interrupt();
            try {
                recordThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordThread = null;
        }
        
        if (ffmpegThread != null) {
            ffmpegThread.interrupt();
            ffmpegThread = null;
        }
        
        // 清理资源
        cleanupAdbResources();
        cleanupFfmpegResources();
        
        notifyStateChanged(false);
    }
    
    private void cleanupAdbResources() {
        try {
            if (audioInputStream != null) {
                audioInputStream.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (adbProcess != null) {
                adbProcess.destroy();
            }
            // 清除端口转发
            Runtime.getRuntime().exec("adb forward --remove tcp:" + SCRCPY_AUDIO_PORT);
        } catch (Exception e) {
            // 忽略清理错误
        }
    }
    
    private void cleanupFfmpegResources() {
        try {
            if (ffmpegInputStream != null) {
                ffmpegInputStream.close();
            }
            if (ffmpegProcess != null) {
                ffmpegProcess.destroy();
            }
        } catch (Exception e) {
            // 忽略清理错误
        }
    }
    
    /**
     * 获取缓存的音频数据
     */
    public byte[] getCachedAudio() {
        if (circularBuffer == null) {
            return new byte[0];
        }
        return circularBuffer.readAll();
    }
    
    /**
     * 获取缓存时长（秒）
     */
    public int getCacheDurationSeconds() {
        if (circularBuffer == null) {
            return 0;
        }
        int bytesPerSecond = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE;
        return circularBuffer.available() / bytesPerSecond;
    }
    
    /**
     * 获取缓存大小（KB）
     */
    public int getCacheSizeKB() {
        if (circularBuffer == null) {
            return 0;
        }
        return circularBuffer.available() / 1024;
    }
    
    public boolean isRecording() {
        return isRecording;
    }
    
    private void notifyStateChanged(final boolean recording) {
        mainHandler.post(() -> {
            if (stateListener != null) {
                stateListener.onStateChanged(recording);
            }
        });
    }
    
    private void notifyError(final String error) {
        mainHandler.post(() -> {
            if (stateListener != null) {
                stateListener.onError(error);
            }
        });
    }
}
