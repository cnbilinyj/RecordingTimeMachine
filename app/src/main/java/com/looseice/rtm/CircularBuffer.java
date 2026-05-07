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
