package com.gathin.tvhome.speech;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 系统级静默无感截屏无障碍服务
 * 针对 Android 11 (API 30) 海信/Vidda 电视定制：
 * 采用 AccessibilityService.takeScreenshot 异步请求 SurfaceFlinger 硬件 Buffer，
 * 免弹窗授权、无快门音、无录屏图标。
 */
public class ScreenCaptureAccessibilityService extends AccessibilityService {
    private static final String TAG = "ScreenCaptureAccSvc";

    private static volatile ScreenCaptureAccessibilityService sInstance;
    private static volatile String sForegroundPackage = "";
    private static final ExecutorService sCaptureExecutor = Executors.newSingleThreadExecutor();

    public static String getForegroundPackage() {
        if (sForegroundPackage != null && !sForegroundPackage.isEmpty()) {
            return sForegroundPackage;
        }
        if (sInstance != null) {
            try {
                android.view.accessibility.AccessibilityNodeInfo root = sInstance.getRootInActiveWindow();
                if (root != null) {
                    CharSequence pkg = root.getPackageName();
                    if (pkg != null && !pkg.toString().isEmpty()) {
                        sForegroundPackage = pkg.toString();
                    }
                    root.recycle();
                }
            } catch (Throwable ignored) {}
        }
        return sForegroundPackage != null ? sForegroundPackage : "";
    }

    public interface RawCaptureCallback {
        void onBufferCaptured(HardwareBuffer buffer, ColorSpace colorSpace);
        void onError(int errorCode);
    }

    public static boolean isAvailable() {
        return sInstance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static void captureDisplay(final RawCaptureCallback callback) {
        if (!isAvailable()) {
            Log.w(TAG, "ScreenCaptureAccessibilityService 尚未就绪或系统版本低于 Android 11");
            if (callback != null) {
                callback.onError(-1);
            }
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                sInstance.takeScreenshot(Display.DEFAULT_DISPLAY, sCaptureExecutor, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                        HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
                        ColorSpace colorSpace = screenshotResult.getColorSpace();
                        if (callback != null) {
                            callback.onBufferCaptured(buffer, colorSpace);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(TAG, "takeScreenshot 底层回调失败，错误码: " + errorCode);
                        if (callback != null) {
                            callback.onError(errorCode);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "调用 takeScreenshot 抛出异常", t);
                if (callback != null) {
                    callback.onError(-2);
                }
            }
        } else {
            if (callback != null) {
                callback.onError(-1);
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "【就绪】ScreenCaptureAccessibilityService 已成功连接系统无障碍通道！");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        Log.i(TAG, "ScreenCaptureAccessibilityService 已断开连接 (onUnbind)");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        Log.i(TAG, "ScreenCaptureAccessibilityService onDestroy");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && !pkg.toString().isEmpty()) {
            sForegroundPackage = pkg.toString();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "ScreenCaptureAccessibilityService onInterrupt");
    }
}
