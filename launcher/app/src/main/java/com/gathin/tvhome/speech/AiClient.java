package com.gathin.tvhome.speech;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.gathin.tvhome.TvConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 电视端专属 AI 视觉与语音大模型通信客户端
 * 核心特性：
 * 1. 直连本地/局域网 AI 网关 (OpenAI 协议兼容)，支持 Gemini 3.8 Flash 等多模态大模型
 * 2. 多重自愈与智能故障转移 (Failover)：
 *    - 优先尝试上次验证成功的网关地址
 *    - 候选池自动包含：用户配置、局域网宿主机 IP、127.0.0.1 (ADB Reverse)
 *    - 连接失败秒级自动切换备用地址，杜绝因断连或网络颠簸导致闪退
 * 3. “当轮带图、历史留文”——历史轮次自动剥离图片节点，历史体积保持 < 4KB
 * 4. 请求世代号（sRequestIdGenerator）机制，防止快速打断或唤醒串音
 * 5. 纯净输出过滤：过滤 Markdown / 表情符，确保适合电视 TTS 朗读与字幕显示
 */
public class AiClient {
    private static final String TAG = "AiClient";

    // 默认网关配置 (具体值由 TvConfig 统一管理)
    public static final String DEFAULT_LAN_URL = "http://127.0.0.1:3425/v1";
    public static final String DEFAULT_LOCAL_URL = "http://127.0.0.1:3425/v1";
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_MODEL = "google/models/gemini-3.8-flash";

    private static Context sContext;
    private static volatile String sLastWorkingUrl = null;

    // 连续对话上下文记忆（最多保留最近 20 条交互历史，即 10 轮完整上下文对话）
    private static final List<JSONObject> sChatHistory = new ArrayList<>();
    private static long sLastChatTime = 0;
    private static final int MAX_CHAT_HISTORY_SIZE = 20;

    // 请求世代号，用于在快速连续提问时废弃过期请求回调
    private static final AtomicInteger sRequestIdGenerator = new AtomicInteger(0);

    // 线程池管理网络 IO，避免频繁创建销毁线程
    private static final ExecutorService sNetworkExecutor = Executors.newFixedThreadPool(2);

    // 预编译 TTS 过滤正则，消除高频对话下的正则编译损耗与临时对象
    private static final Pattern PATTERN_MARKDOWN = Pattern.compile("[#*`~_>\\[\\]]");
    private static final Pattern PATTERN_NEWLINES = Pattern.compile("\\n+");
    private static final Pattern PATTERN_EMOJI_SYMBOLS = Pattern.compile("[\\p{So}\\p{Cn}]");

    public interface Callback {
        void onSuccess(String reply);
        void onError(String error);
    }

    public static void init(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext();
            TvConfig.init(sContext);
        }
    }

    public static List<String> getCandidateUrls() {
        return TvConfig.getCandidateUrls(sLastWorkingUrl);
    }

    public static String getApiKey() {
        return TvConfig.getAiApiKey();
    }

    public static String getModel() {
        return TvConfig.getAiModel();
    }

    public static synchronized void resetConversation() {
        sChatHistory.clear();
        sLastChatTime = 0;
        sRequestIdGenerator.incrementAndGet(); // 废弃所有飞行中的旧请求
        Log.i(TAG, "已重置连续对话上下文与请求世代号");
    }

    public static void chat(String userMessage, Callback callback) {
        chat(userMessage, null, null, callback);
    }

    /**
     * @param userMessage 当轮实际发送给模型的用户消息（可能含 DRM 等一次性系统提示前缀）
     * @param historyText 写入多轮历史的用户原文（不含一次性前缀，避免污染后续对话）
     * @param jpegBytes   电视当前画面截图，为 null 时以纯文本提问
     */
    public static void chat(String userMessage, String historyText, byte[] jpegBytes, Callback callback) {
        final String histText = (historyText != null && !historyText.trim().isEmpty()) ? historyText : userMessage;
        final int currentRequestId = sRequestIdGenerator.incrementAndGet();

        final List<String> candidateUrls = getCandidateUrls();
        final String apiKey = getApiKey();
        final String modelName = getModel();

        sNetworkExecutor.execute(() -> {
            long now = System.currentTimeMillis();

            final long sessionExpireMs = TvConfig.getSessionExpireMs();

            // 检查是否超时重置会话
            synchronized (AiClient.class) {
                if (now - sLastChatTime > sessionExpireMs) {
                    sChatHistory.clear();
                    Log.i(TAG, "距离上次对话超过 " + (sessionExpireMs / 1000) + " 秒，已开启全新对话轮次");
                }
                sLastChatTime = now;
            }

            // 快捷重置指令
            String trimmed = userMessage.trim();
            if (trimmed.equals("清空对话") || trimmed.equals("重新开始") || trimmed.equals("退出对话")) {
                resetConversation();
                if (callback != null) {
                    callback.onSuccess("好的，已经清空之前的对话记录啦，随时叫我哦！");
                }
                return;
            }

            // 构建请求 JSON Payload
            byte[] bodyBytes;
            final boolean hasImage = (jpegBytes != null && jpegBytes.length > 0);
            try {
                JSONObject payload = new JSONObject();
                payload.put("model", modelName);
                payload.put("stream", false);
                payload.put("reasoning_effort", "low");
                payload.put("max_tokens", 160);

                JSONArray messages = new JSONArray();

                // 系统提示词：从配置中心 TvConfig 动态获取（支持热重载）
                JSONObject sysMsg = new JSONObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", TvConfig.getSystemPrompt());
                messages.put(sysMsg);

                // 添加多轮纯文本轻量历史
                synchronized (AiClient.class) {
                    for (JSONObject histMsg : sChatHistory) {
                        messages.put(histMsg);
                    }
                    Log.i(TAG, "【多轮记忆检索】本次请求携带历史条数: " + sChatHistory.size());
                }

                // 当轮用户消息
                JSONObject currentUsrMsg = new JSONObject();
                currentUsrMsg.put("role", "user");

                if (hasImage) {
                    JSONArray contentArray = new JSONArray();

                    JSONObject imgNode = new JSONObject();
                    imgNode.put("type", "image_url");
                    JSONObject urlObj = new JSONObject();
                    String b64Img = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
                    urlObj.put("url", "data:image/jpeg;base64," + b64Img);
                    imgNode.put("image_url", urlObj);
                    contentArray.put(imgNode);

                    JSONObject textNode = new JSONObject();
                    textNode.put("type", "text");
                    textNode.put("text", userMessage);
                    contentArray.put(textNode);

                    currentUsrMsg.put("content", contentArray);
                } else {
                    currentUsrMsg.put("content", userMessage);
                }
                messages.put(currentUsrMsg);

                payload.put("messages", messages);
                bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            } catch (Throwable t) {
                Log.e(TAG, "构建请求 JSON 异常", t);
                if (callback != null) callback.onError("数据格式异常");
                return;
            }

            // 循环尝试候选网关地址 (Failover 机制)
            Throwable lastException = null;
            for (int i = 0; i < candidateUrls.size(); i++) {
                String targetUrl = candidateUrls.get(i);
                if (currentRequestId != sRequestIdGenerator.get()) {
                    Log.i(TAG, "【丢弃过期请求】requestId: " + currentRequestId);
                    return;
                }

                HttpURLConnection conn = null;
                try {
                    Log.i(TAG, "正在请求 AI 网关 [" + (i + 1) + "/" + candidateUrls.size() + "]: " + targetUrl);
                    URL url = new URL(targetUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    if (apiKey != null && !apiKey.isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    }
                    // 连接超时设为 3 秒，若不可达迅速切换至下一个候选网关
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(25000);
                    conn.setDoOutput(true);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(bodyBytes);
                        os.flush();
                    }

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                    }

                    if (currentRequestId != sRequestIdGenerator.get()) {
                        Log.i(TAG, "【丢弃过期响应】requestId: " + currentRequestId + ", latest: " + sRequestIdGenerator.get());
                        return;
                    }

                    if (code == 200) {
                        JSONObject resJson = new JSONObject(response.toString());
                        JSONArray choices = resJson.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                            String content = (msg != null) ? msg.optString("content", "") : "";
                            String cleanReply = cleanForTTS(content);
                            Log.i(TAG, "AI 网关 (" + targetUrl + ") 回复: " + cleanReply);

                            // 缓存该成功连通的 URL 为下一次优先网关
                            sLastWorkingUrl = targetUrl;

                            // 记录历史
                            synchronized (AiClient.class) {
                                JSONObject histUserMsg = new JSONObject();
                                histUserMsg.put("role", "user");
                                if (hasImage) {
                                    histUserMsg.put("content", "[结合刚才的电视画面提问] " + histText);
                                } else {
                                    histUserMsg.put("content", histText);
                                }
                                sChatHistory.add(histUserMsg);

                                JSONObject assistantMsg = new JSONObject();
                                assistantMsg.put("role", "assistant");
                                assistantMsg.put("content", cleanReply);
                                sChatHistory.add(assistantMsg);

                                while (sChatHistory.size() > MAX_CHAT_HISTORY_SIZE) {
                                    sChatHistory.remove(0);
                                }
                                Log.i(TAG, "【多轮记忆更新】已写入历史，当前历史总条数: " + sChatHistory.size());
                            }

                            if (callback != null) callback.onSuccess(cleanReply);
                            return; // 成功即返回
                        }
                    }

                    Log.w(TAG, "网关响应非200: HTTP " + code + ", 响应: " + response);
                    lastException = new RuntimeException("HTTP " + code);

                } catch (Throwable t) {
                    lastException = t;
                    Log.w(TAG, "网关连接失败: " + targetUrl + " (" + t.getMessage() + ")，准备尝试下一个候选地址...");
                } finally {
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Throwable ignored) {}
                    }
                }
            }

            // 所有候选地址均失败
            if (currentRequestId == sRequestIdGenerator.get() && callback != null) {
                String errMsg = (lastException != null) ? lastException.getMessage() : "所有网关均不可达";
                Log.e(TAG, "AI 网关所有候选地址均请求失败: " + errMsg);
                callback.onError(errMsg);
            }
        });
    }

    private static String cleanForTTS(String text) {
        if (text == null) return "";
        String s = PATTERN_MARKDOWN.matcher(text).replaceAll("");
        s = PATTERN_NEWLINES.matcher(s).replaceAll(" ");
        s = PATTERN_EMOJI_SYMBOLS.matcher(s).replaceAll("");
        return s.trim();
    }
}
