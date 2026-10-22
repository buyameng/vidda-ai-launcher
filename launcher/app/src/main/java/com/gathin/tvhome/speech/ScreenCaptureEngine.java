package com.gathin.tvhome.speech;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无感静默截屏引擎管理器
 * 负责：
 * 1. 唤醒瞬间单槽异步预抓帧
 * 2. 实时静默补帧与超时安全回退
 * 3. 单槽内存生命周期（60 秒未使用自动置空，onTrimMemory 瞬间回收）
 * 4. 系统无障碍服务离线自愈机制（利用 WRITE_SECURE_SETTINGS 自动恢复服务状态）
 */
public class ScreenCaptureEngine {
    private static final String TAG = "ScreenCaptureEngine";

    private static final AtomicReference<FrameMemoryEncoder.CapturedFrame> sLatestFrame = new AtomicReference<>(null);
    private static final AtomicBoolean sIsPreCapturing = new AtomicBoolean(false);
    private static final ExecutorService sAsyncExecutor = Executors.newSingleThreadExecutor();

    private static final long CACHE_EXPIRE_MS = 180_000L; // 180秒(3分钟)单帧保活期，适配用户从容追问与思考间隔
    private static volatile Context sContext;

    public static void init(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext();
        }
        ensureAccessibilityServiceBound(sContext);
        Log.i(TAG, "ScreenCaptureEngine 初始化完成");
    }

    /**
     * 自动检测并自愈系统无障碍服务绑定状态
     * 依赖系统已授予的 WRITE_SECURE_SETTINGS 权限，解决系统进程重启后沦为 Crashed Service 无法唤醒的问题
     */
    public static void ensureAccessibilityServiceBound(Context context) {
        if (ScreenCaptureAccessibilityService.isAvailable()) {
            return;
        }
        Context ctx = (context != null) ? context : sContext;
        if (ctx == null) return;

        sAsyncExecutor.execute(() -> {
            try {
                ContentResolver resolver = ctx.getContentResolver();
                String current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                String ourService = ctx.getPackageName() + "/" + ScreenCaptureAccessibilityService.class.getName();

                if (current != null && current.contains(ourService)) {
                    // 先临时从列表中移除 ourService，触发系统清空 mCrashedServices 假死黑名单
                    // 注意：保留系统和海信原有服务，且绝不关闭 ACCESSIBILITY_ENABLED 总开关
                    String base = current.replace(":" + ourService, "").replace(ourService + ":", "").replace(ourService, "").trim();
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, base);
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignored) {}
                    current = base;
                }

                String target = (current == null || current.isEmpty()) ? ourService : (current + ":" + ourService);
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, target);
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i(TAG, "已安全注册无障碍服务通道并激活: " + target);
            } catch (Throwable t) {
                Log.w(TAG, "ensureAccessibilityServiceBound 异常: " + t.getMessage());
            }
        });
    }

    /**
     * 唤醒瞬间触发异步静默预抓帧（在 UI 展开前抓取纯净无遮挡画面）
     */
    public static void triggerPreCapture() {
        if (!ScreenCaptureAccessibilityService.isAvailable()) {
            Log.d(TAG, "无障碍截屏服务尚未就绪，触发自愈并跳过预抓帧");
            ensureAccessibilityServiceBound(sContext);
            return;
        }

        if (!sIsPreCapturing.compareAndSet(false, true)) {
            Log.d(TAG, "已有正在执行的抓帧任务，忽略并发触发");
            return;
        }

        sAsyncExecutor.execute(() -> {
            try {
                ScreenCaptureAccessibilityService.captureDisplay(new ScreenCaptureAccessibilityService.RawCaptureCallback() {
                    @Override
                    public void onBufferCaptured(HardwareBuffer buffer, ColorSpace colorSpace) {
                        try {
                            FrameMemoryEncoder.CapturedFrame frame =
                                    FrameMemoryEncoder.encodeHardwareBuffer(buffer, colorSpace);
                            if (frame != null) {
                                sLatestFrame.set(frame);
                                Log.i(TAG, "【预抓帧就绪】已存入单槽缓存，大小: " + frame.jpegBytes.length + " bytes");
                            }
                        } finally {
                            sIsPreCapturing.set(false);
                        }
                    }

                    @Override
                    public void onError(int errorCode) {
                        sIsPreCapturing.set(false);
                        Log.w(TAG, "预抓帧回调失败，错误码: " + errorCode);
                    }
                });
            } catch (Throwable t) {
                sIsPreCapturing.set(false);
                Log.e(TAG, "triggerPreCapture 异常", t);
            }
        });
    }

    /**
     * 实时静默补帧（带硬件超时锁）
     * 超时或失败自动回退为已有缓存或 null，绝不阻塞主语音链路
     */
    public static FrameMemoryEncoder.CapturedFrame captureFreshFrame(long timeoutMs) {
        if (!ScreenCaptureAccessibilityService.isAvailable()) {
            Log.w(TAG, "无障碍截屏服务不可用，触发自愈并回退至现有缓存");
            ensureAccessibilityServiceBound(sContext);
            return getLatestFrame();
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<FrameMemoryEncoder.CapturedFrame> resultRef = new AtomicReference<>(null);

        try {
            ScreenCaptureAccessibilityService.captureDisplay(new ScreenCaptureAccessibilityService.RawCaptureCallback() {
                @Override
                public void onBufferCaptured(HardwareBuffer buffer, ColorSpace colorSpace) {
                    try {
                        FrameMemoryEncoder.CapturedFrame frame =
                                FrameMemoryEncoder.encodeHardwareBuffer(buffer, colorSpace);
                        if (frame != null) {
                            sLatestFrame.set(frame);
                            resultRef.set(frame);
                        }
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onError(int errorCode) {
                    Log.w(TAG, "实时补帧失败，错误码: " + errorCode);
                    latch.countDown();
                }
            });

            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                Log.w(TAG, "实时抓帧超时 (" + timeoutMs + "ms)，优雅回退");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "实时抓帧等待被中断");
        } catch (Throwable t) {
            Log.e(TAG, "captureFreshFrame 异常", t);
        }

        FrameMemoryEncoder.CapturedFrame fresh = resultRef.get();
        if (fresh != null) {
            return fresh;
        }
        return getLatestFrame();
    }

    /**
     * 获取单槽中的最新画面（带过期校验）
     */
    public static FrameMemoryEncoder.CapturedFrame getLatestFrame() {
        FrameMemoryEncoder.CapturedFrame frame = sLatestFrame.get();
        if (frame == null) return null;

        long age = System.currentTimeMillis() - frame.captureTimeMs;
        if (age > CACHE_EXPIRE_MS) {
            Log.i(TAG, "单槽缓存已超过 60 秒未被使用，自动释放回收");
            sLatestFrame.compareAndSet(frame, null);
            return null;
        }
        return frame;
    }

    /**
     * 获取最新抓帧时间戳
     */
    public static long getLastCaptureTimeMs() {
        FrameMemoryEncoder.CapturedFrame frame = sLatestFrame.get();
        return frame != null ? frame.captureTimeMs : 0;
    }

    /**
     * 清空单槽缓存
     */
    public static void clearCache() {
        sLatestFrame.set(null);
        Log.i(TAG, "单槽截屏缓存已主动置空");
    }

    /**
     * 响应系统内存收紧
     */
    public static void onTrimMemory(int level) {
        clearCache();
    }
}
