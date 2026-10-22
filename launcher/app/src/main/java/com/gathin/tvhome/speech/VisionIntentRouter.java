package com.gathin.tvhome.speech;

import android.util.Log;

import java.util.regex.Pattern;

/**
 * 毫秒级视觉意图分流器
 * 通过纯本地无延迟关键词匹配与正则分析，区分：
 * 1. TEXT_ONLY: 纯文本问答（不附带图片，最快响应）
 * 2. VISION_CACHE_FRAME: 使用唤醒瞬间预抓拍的画面帧
 * 3. VISION_FRESH_FRAME: 实时静默补帧（多轮对话中途切换画面，或预抓帧已超过 5 秒）
 */
public class VisionIntentRouter {
    private static final String TAG = "VisionIntentRouter";

    public enum RouteMode {
        TEXT_ONLY,          // 纯文本问答
        VISION_CACHE_FRAME, // 使用唤醒瞬间预抓拍的画面帧
        VISION_FRESH_FRAME  // 实时静默补拍当前画面帧
    }

    // 1. 显式屏幕/画面指代词
    private static final String[] SCREEN_KEYWORDS = {
            "屏幕", "画面", "电视上", "电视里", "图里", "图上", "图中", "图片", "截图", "界面", "当前页",
            "左上角", "右上角", "左下角", "右下角", "中间这", "中间那", "看电视", "看屏幕", "看画面"
    };

    // 2. 场景动作与 OCR 识别词
    private static final String[] OCR_ACTION_KEYWORDS = {
            "这行字", "写的什么", "写的是什么", "写了什么", "写的啥", "写了啥", "上面这行", "下面这行",
            "翻译一下", "翻译这", "翻译屏幕", "翻译字幕", "报错", "错误码", "二维码", "字幕", "这道题", "比分是多少",
            "这几个字", "什么意思", "这是什么字", "这行英文", "怎么读", "怎么念"
    };

    // 3. 场景疑问词
    private static final String[] SCENE_QUESTIONS = {
            "画面里", "演的是谁", "这是哪一集", "这是什么电影", "他在干嘛", "她是谁", "他是谁",
            "这是什么动物", "这是什么植物", "这是什么鸟", "这是什么车", "这是什么花",
            "这是什么东西", "这是什么狗", "这是什么猫", "这是什么鱼", "这是什么建筑",
            "这是什么景点", "这是哪个国家", "这是哪座城市", "屏幕上这是", "看看这个", "帮我看看", "看下这个",
            "这是啥", "这是个啥", "这是什么", "这是谁", "这人是谁", "演的谁"
    };

    // 4. 近指代疑问句正则
    private static final Pattern DEMONSTRATIVE_PATTERN = Pattern.compile(
            ".*(这是啥|这是什么|这是谁|这人是谁|是谁啊|是什么啊|在干嘛|在干什么|演的谁|哪部电影|哪部剧|什么电影|什么电视剧|什么车|什么花|什么动物|什么植物|什么地方|哪里|哪座城市|什么标志|什么logo).*"
    );

    /**
     * 判定提问路由意图
     *
     * @param query 用户提问文本
     * @param lastCaptureTimeMs 上次预抓帧的时间戳（毫秒）
     * @return 路由模式
     */
    public static RouteMode route(String query, long lastCaptureTimeMs) {
        if (query == null || query.trim().isEmpty()) {
            return RouteMode.TEXT_ONLY;
        }

        String clean = query.trim();
        boolean hasVisionIntent = false;

        // 1. 显式屏幕词判定
        for (String kw : SCREEN_KEYWORDS) {
            if (clean.contains(kw)) {
                hasVisionIntent = true;
                break;
            }
        }

        // 2. OCR 及动作词判定
        if (!hasVisionIntent) {
            for (String kw : OCR_ACTION_KEYWORDS) {
                if (clean.contains(kw)) {
                    hasVisionIntent = true;
                    break;
                }
            }
        }

        // 3. 场景疑问词判定
        if (!hasVisionIntent) {
            for (String kw : SCENE_QUESTIONS) {
                if (clean.contains(kw)) {
                    hasVisionIntent = true;
                    break;
                }
            }
        }

        // 4. 正则近指代匹配
        if (!hasVisionIntent && DEMONSTRATIVE_PATTERN.matcher(clean).find()) {
            hasVisionIntent = true;
        }

        if (!hasVisionIntent) {
            return RouteMode.TEXT_ONLY;
        }

        // 多轮追问更新策略：若缓存不存在或超过 5 秒，触发实时静默补帧
        long now = System.currentTimeMillis();
        if (lastCaptureTimeMs <= 0 || (now - lastCaptureTimeMs > 5000)) {
            Log.i(TAG, "命中视觉意图: [" + clean + "]，距离上次抓帧 " + (now - lastCaptureTimeMs) + "ms，采用实时补帧 (FRESH)");
            return RouteMode.VISION_FRESH_FRAME;
        }

        Log.i(TAG, "命中视觉意图: [" + clean + "]，复用预抓帧缓存 (CACHE)");
        return RouteMode.VISION_CACHE_FRAME;
    }
}
