package com.gathin.tvhome.speech;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * 高性能零落盘图像编码器
 * 严格遵循“四不原则”：不落盘、不保大图、不并发堆积、不跨轮驻留。
 * 流程：
 * 1. HardwareBuffer -> 包装为 Hardware Bitmap
 * 2. 硬件/内存矩阵缩放降采样至 1280x720 RGB_565 (仅 1.8MB 临时显存)
 * 3. 立即 recycle 原图与 close HardwareBuffer (< 15ms)
 * 4. 16 点网格像素采样快速判定纯黑（DRM 保护画面）
 * 5. 复用内存池压缩为 75% JPEG 字节流 (~40KB - 100KB)
 */
public class FrameMemoryEncoder {
    private static final String TAG = "FrameMemoryEncoder";

    public static final int TARGET_WIDTH = 1280;
    public static final int TARGET_HEIGHT = 720;
    public static final int JPEG_QUALITY = 75;

    public static class CapturedFrame {
        public final byte[] jpegBytes;
        public final long captureTimeMs;
        public final boolean isPureBlack;

        public CapturedFrame(byte[] jpegBytes, long captureTimeMs, boolean isPureBlack) {
            this.jpegBytes = jpegBytes;
            this.captureTimeMs = captureTimeMs;
            this.isPureBlack = isPureBlack;
        }
    }

    /**
     * 复用缓冲输出流，避免频繁触发 GC
     */
    public static class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        public ReusableByteArrayOutputStream(int initialCapacity) {
            super(initialCapacity);
        }

        public byte[] toTrimmedByteArray() {
            byte[] copy = new byte[count];
            System.arraycopy(buf, 0, copy, 0, count);
            return copy;
        }
    }

    private static final ThreadLocal<ReusableByteArrayOutputStream> sStreamPool =
            ThreadLocal.withInitial(() -> new ReusableByteArrayOutputStream(128 * 1024));

    /**
     * 将 HardwareBuffer 高效编码为 720P JPEG 字节流
     */
    public static CapturedFrame encodeHardwareBuffer(HardwareBuffer buffer, ColorSpace colorSpace) {
        if (buffer == null) return null;

        long startTime = System.currentTimeMillis();
        Bitmap hwBitmap = null;
        Bitmap swBitmap = null;
        Bitmap scaledBitmap = null;

        try {
            hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hwBitmap == null) {
                Log.w(TAG, "Bitmap.wrapHardwareBuffer 返回 null");
                return null;
            }

            // 优先转为轻量 RGB_565
            try {
                swBitmap = hwBitmap.copy(Bitmap.Config.RGB_565, false);
            } catch (Throwable ignored) {}

            if (swBitmap == null) {
                try {
                    swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
                } catch (Throwable t) {
                    Log.e(TAG, "copy to ARGB_8888 异常", t);
                }
            }

            if (swBitmap == null) {
                Log.w(TAG, "未能生成可渲染的软件 Bitmap");
                return null;
            }

            // 原图已复制，立即释放硬件原图与 Native HardwareBuffer
            hwBitmap.recycle();
            hwBitmap = null;
            try {
                buffer.close();
                buffer = null;
            } catch (Exception ignored) {}

            // 原生 Skia 硬件/C++ 高速缩放至 1280×720
            if (swBitmap.getWidth() == TARGET_WIDTH && swBitmap.getHeight() == TARGET_HEIGHT
                    && swBitmap.getConfig() == Bitmap.Config.RGB_565) {
                scaledBitmap = swBitmap;
            } else {
                scaledBitmap = Bitmap.createScaledBitmap(swBitmap, TARGET_WIDTH, TARGET_HEIGHT, true);
            }

            // 16 点网格快速采样检测全黑画面（耗时 < 0.2ms）
            boolean isPureBlack = checkPureBlack(scaledBitmap);

            // 复用内存流进行 JPEG 压缩
            ReusableByteArrayOutputStream bos = sStreamPool.get();
            bos.reset();
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            byte[] jpegBytes = bos.toTrimmedByteArray();

            long totalCost = System.currentTimeMillis() - startTime;
            Log.i(TAG, "【编码完成】720P JPEG 大小: " + jpegBytes.length + " B (~" + (jpegBytes.length / 1024)
                    + " KB), 总耗时: " + totalCost + "ms, 黑屏: " + isPureBlack);

            return new CapturedFrame(jpegBytes, System.currentTimeMillis(), isPureBlack);

        } catch (Throwable t) {
            Log.e(TAG, "encodeHardwareBuffer 处理异常", t);
            return null;
        } finally {
            if (hwBitmap != null && !hwBitmap.isRecycled()) {
                hwBitmap.recycle();
            }
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (Exception ignored) {}
            }
            if (swBitmap != null && swBitmap != scaledBitmap && !swBitmap.isRecycled()) {
                swBitmap.recycle();
            }
            if (scaledBitmap != null && !scaledBitmap.isRecycled()) {
                scaledBitmap.recycle();
            }
        }
    }

    /**
     * 对画面中心 16 个网格点进行快速像素采样，判断是否为纯黑屏（Widevine/DRM 版权保护）
     */
    public static boolean checkPureBlack(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int darkCount = 0;

        // 4x4 采样矩阵，分布在画面的 20%, 40%, 60%, 80% 区域
        for (int row = 1; row <= 4; row++) {
            int y = height * row / 5;
            for (int col = 1; col <= 4; col++) {
                int x = width * col / 5;
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                if (r < 20 && g < 20 && b < 20) {
                    darkCount++;
                }
            }
        }

        // 16 个采样点中若有 15 点以上为全黑，则判定为纯黑
        return darkCount >= 15;
    }
}
