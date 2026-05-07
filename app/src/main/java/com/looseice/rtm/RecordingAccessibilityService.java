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
