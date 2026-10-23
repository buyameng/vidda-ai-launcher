package com.gathin.tvhome;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 全局统一配置管理器 (TvConfig)
 *
 * 集中管理大模型网关、智能灯具、系统提示词以及对话超时等核心选项。
 * 敏感信息提取至独立的 tvhome_config.json 文件，与代码逻辑完全解耦并排除于 Git 仓库之外，
 * 彻底防止 API Key、私有 IP 及 Webhook 地址泄露。
 *
 * 配置文件查找优先级：
 * 1. 应用私有存储目录: context.getFilesDir()/tvhome_config.json (支持通过 deploy.sh 推送)
 * 2. 外部共享存储目录: /sdcard/tvhome_config.json (便于开发者免重装通过 adb push 覆盖)
 * 3. 本地临时目录: /data/local/tmp/tvhome_config.json
 * 4. APK 内部打包 Assets: assets/tvhome_config.json
 * 5. Android 系统属性 / Settings.Global 设置项 (如 persist.tvhome.*)
 * 6. 代码内置安全默认值
 */
public final class TvConfig {
    private static final String TAG = "TvConfig";
    public static final String CONFIG_FILE_NAME = "tvhome_config.json";

    // 默认内置兜底值（仅在完全无配置文件时生效）
    private static final String DEFAULT_LAN_GATEWAY = "http://127.0.0.1:3425/v1";
    private static final String DEFAULT_LOCAL_GATEWAY = "http://127.0.0.1:3425/v1";
    private static final String DEFAULT_API_KEY = "";
    private static final String DEFAULT_MODEL = "google/models/gemini-3.8-flash";

    private static final String DEFAULT_LIGHT_BASE = "http://127.0.0.1:8123/api/webhook";
    private static final String DEFAULT_LIGHT_TOGGLE = "http://127.0.0.1:8123/api/webhook/tv_light_toggle";
    private static final String DEFAULT_LIGHT_STATUS = "http://127.0.0.1:8123/api/webhook/tv_light_status";
    private static final String DEFAULT_LIGHT_MOVIE  = "http://127.0.0.1:8123/api/webhook/tv_light_movie";
    private static final String DEFAULT_LIGHT_GAME   = "http://127.0.0.1:8123/api/webhook/tv_light_game";

    private static final String DEFAULT_SYSTEM_PROMPT =
            "请严格遵守以下交互与格式规则：\n" +
            "1. 当附带图片时，该图片即为用户正在观看的电视屏幕画面，请自然直接地回答关于画面的问题，用“现在屏幕上…”、“画面里…”等自然口吻，不要生硬提及“根据你提供的截图/图片”；\n" +
            "2. 回答内容将由电视 TTS 引擎直接语音朗读，绝对不要输出任何 Markdown 标记（如 *、#、代码块、列表符号等）或 Emoji 表情符；\n" +
            "3. 语音回答需精炼自然，控制在 2~4 句话（100字以内），直奔主题；\n" +
            "4. 支持多轮对话，结合上下文精准理解追问；\n" +
            "5. 若用户询问你的身份或模型，如实回答你的大模型名称与研发机构即可，不要自称其他名字。";

    private static final long DEFAULT_SESSION_EXPIRE_SECONDS = 600L; // 10分钟
    private static final long DEFAULT_LISTEN_TIMEOUT_SECONDS = 25L;  // 25秒

    private static volatile Context sContext;
    private static volatile boolean sInitialized = false;

    // 内存配置缓存
    private static String sLanUrl = DEFAULT_LAN_GATEWAY;
    private static String sLocalUrl = DEFAULT_LOCAL_GATEWAY;
    private static String sApiKey = DEFAULT_API_KEY;
    private static String sModel = DEFAULT_MODEL;

    // 降级保底网关 (DeepSeek Cloud)
    private static boolean sFallbackEnabled = false;
    private static String sFallbackName = "DeepSeek Cloud";
    private static String sFallbackUrl = "https://api.deepseek.com/chat/completions";
    private static String sFallbackApiKey = "";
    private static String sFallbackModel = "deepseek-flash";
    private static String sFallbackReasoningEffort = "high";
    private static boolean sFallbackThinking = true;
    private static int sFallbackMaxTokens = 512;

    // 熔断与自愈机制：主服务若掉线，在 60 秒内优先使用保底网关，避免重复阻塞等待超时
    private static volatile long sPrimaryFailTimestamp = 0L;
    private static final long PRIMARY_RETRY_INTERVAL_MS = 60_000L;

    private static String sLightToggleUrl = DEFAULT_LIGHT_TOGGLE;
    private static String sLightStatusUrl = DEFAULT_LIGHT_STATUS;
    private static String sLightMovieUrl  = DEFAULT_LIGHT_MOVIE;
    private static String sLightGameUrl   = DEFAULT_LIGHT_GAME;

    private static String sSystemPrompt = DEFAULT_SYSTEM_PROMPT;
    private static long sSessionExpireMs = DEFAULT_SESSION_EXPIRE_SECONDS * 1000L;
    private static long sListenTimeoutMs = DEFAULT_LISTEN_TIMEOUT_SECONDS * 1000L;

    // 跟踪配置文件路径与更新时间，支持零延迟热重载
    private static String sLoadedSourcePath = null;
    private static long sLastLoadedTimestamp = 0L;

    /**
     * 网关端点配置封装
     */
    public static class GatewayEndpoint {
        public final String name;
        public final String url;
        public final String apiKey;
        public final String model;
        public final String reasoningEffort;
        public final boolean thinkingEnabled;
        public final int maxTokens;
        public final boolean isFallback;

        public GatewayEndpoint(String name, String url, String apiKey, String model,
                               String reasoningEffort, boolean thinkingEnabled, int maxTokens, boolean isFallback) {
            this.name = name;
            this.url = url;
            this.apiKey = apiKey;
            this.model = model;
            this.reasoningEffort = reasoningEffort;
            this.thinkingEnabled = thinkingEnabled;
            this.maxTokens = maxTokens;
            this.isFallback = isFallback;
        }

        @Override
        public String toString() {
            return name + " (" + url + ", model=" + model + ")";
        }
    }

    private TvConfig() {}

    /**
     * 初始化配置管理器
     */
    public static synchronized void init(Context context) {
        if (context != null && sContext == null) {
            sContext = context.getApplicationContext();
        }
        loadConfig(false);
        sInitialized = true;
    }

    /**
     * 检查文件是否有更新并自动热重载
     */
    public static void checkReloadIfModified() {
        File sdFile = new File("/sdcard/" + CONFIG_FILE_NAME);
        File tmpFile = new File("/data/local/tmp/" + CONFIG_FILE_NAME);
        if ((sdFile.exists() && sdFile.lastModified() > sLastLoadedTimestamp) ||
            (tmpFile.exists() && tmpFile.lastModified() > sLastLoadedTimestamp)) {
            synchronized (TvConfig.class) {
                if ((sdFile.exists() && sdFile.lastModified() > sLastLoadedTimestamp) ||
                    (tmpFile.exists() && tmpFile.lastModified() > sLastLoadedTimestamp)) {
                    Log.i(TAG, "检测到外部新推送的配置文件，执行安全重载与迁入");
                    loadConfig(true);
                    return;
                }
            }
        }

        if (sLoadedSourcePath != null) {
            File f = new File(sLoadedSourcePath);
            if (f.exists() && f.lastModified() > sLastLoadedTimestamp) {
                synchronized (TvConfig.class) {
                    if (f.lastModified() > sLastLoadedTimestamp) {
                        Log.i(TAG, "检测到配置文件变动，执行热重载: " + sLoadedSourcePath);
                        loadConfig(true);
                    }
                }
            }
        }
    }

    /**
     * 加载配置文件
     */
    private static synchronized void loadConfig(boolean force) {
        String jsonContent = null;
        String source = null;

        // 1. 优先检测外部公共存储新推送的文件 (/sdcard/ 或 /data/local/tmp/)，及时安全迁入并清理
        File sdFile = new File("/sdcard/" + CONFIG_FILE_NAME);
        File tmpFile = new File("/data/local/tmp/" + CONFIG_FILE_NAME);
        File incomingFile = null;

        if (sdFile.exists() && sdFile.canRead() && (force || sdFile.lastModified() > sLastLoadedTimestamp)) {
            incomingFile = sdFile;
        } else if (tmpFile.exists() && tmpFile.canRead() && (force || tmpFile.lastModified() > sLastLoadedTimestamp)) {
            incomingFile = tmpFile;
        }

        if (incomingFile != null) {
            String incomingContent = readFile(incomingFile);
            if (incomingContent != null && !incomingContent.trim().isEmpty()) {
                jsonContent = incomingContent;
                source = incomingFile.getAbsolutePath();
                sLastLoadedTimestamp = incomingFile.lastModified();
                // 立即转存到应用私有沙盒存储 (/data/data/.../files/)
                saveToInternalStorage(incomingContent);
                // 若来源于外置公共存储，擦除以防止其他第三方应用窃取私有 Key
                if (incomingFile.equals(sdFile)) {
                    try {
                        if (sdFile.delete()) {
                            Log.i(TAG, "已成功将公共外置目录配置迁移至应用私有安全沙盒，并清理 /sdcard 明文以保护 Key 安全");
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        // 2. 尝试从应用私有存储读取 (getFilesDir)
        if (jsonContent == null && sContext != null) {
            File appFile = new File(sContext.getFilesDir(), CONFIG_FILE_NAME);
            if (appFile.exists() && appFile.canRead()) {
                jsonContent = readFile(appFile);
                if (jsonContent != null) {
                    source = appFile.getAbsolutePath();
                    sLastLoadedTimestamp = appFile.lastModified();
                }
            }
        }

        // 3. 尝试从 Assets 读取
        if (jsonContent == null && sContext != null) {
            try (InputStream in = sContext.getAssets().open(CONFIG_FILE_NAME)) {
                jsonContent = readStream(in);
                source = "assets/" + CONFIG_FILE_NAME;
                sLastLoadedTimestamp = 0L;
            } catch (Throwable ignored) {}
        }

        sLoadedSourcePath = source;

        if (jsonContent != null && !jsonContent.trim().isEmpty()) {
            parseJsonConfig(jsonContent, source);
        } else {
            Log.i(TAG, "未发现配置文件，使用系统属性/默认内置配置");
            loadFallbacksFromSystem();
        }
    }

    private static void saveToInternalStorage(String content) {
        if (sContext == null || content == null) return;
        try {
            File appFile = new File(sContext.getFilesDir(), CONFIG_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(appFile)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            appFile.setReadable(true, true);
            appFile.setWritable(true, true);
        } catch (Throwable t) {
            Log.w(TAG, "写入内部私有存储失败: " + t.getMessage());
        }
    }

    private static void parseJsonConfig(String jsonStr, String sourceName) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            Log.i(TAG, "【成功解析配置文件】来源: " + sourceName);

            // 1. 网关配置
            JSONObject gw = root.optJSONObject("gateway");
            if (gw != null) {
                String lan = gw.optString("lan_url", "").trim();
                if (!lan.isEmpty()) sLanUrl = lan;
                String local = gw.optString("local_url", "").trim();
                if (!local.isEmpty()) sLocalUrl = local;
                String key = gw.optString("api_key", "").trim();
                if (!key.isEmpty() && !key.contains("your-ai-gateway-api-key")) sApiKey = key;
                String model = gw.optString("model", "").trim();
                if (!model.isEmpty()) sModel = model;
            }

            // 1.1 降级保底配置 (DeepSeek Cloud)
            JSONObject fb = null;
            if (gw != null && gw.has("fallback")) {
                fb = gw.optJSONObject("fallback");
            } else if (root.has("fallback")) {
                fb = root.optJSONObject("fallback");
            }

            if (fb != null) {
                sFallbackEnabled = fb.optBoolean("enabled", true);
                sFallbackName = fb.optString("name", "DeepSeek Cloud");
                String fbUrl = fb.optString("url", "").trim();
                if (!fbUrl.isEmpty()) sFallbackUrl = fbUrl;
                String fbKey = fb.optString("api_key", "").trim();
                if (!fbKey.isEmpty() && !fbKey.contains("your-deepseek-api-key")) {
                    sFallbackApiKey = fbKey;
                }
                String fbModel = fb.optString("model", "").trim();
                if (!fbModel.isEmpty()) sFallbackModel = fbModel;
                String fbReasoning = fb.optString("reasoning_effort", "").trim();
                if (!fbReasoning.isEmpty()) sFallbackReasoningEffort = fbReasoning;
                sFallbackThinking = fb.optBoolean("thinking", true);
                int maxTok = fb.optInt("max_tokens", 0);
                if (maxTok > 0) sFallbackMaxTokens = maxTok;
            }

            // 2. 智能灯具配置
            JSONObject lt = root.optJSONObject("light");
            if (lt != null) {
                String base = lt.optString("base_url", "").trim();
                if (!base.isEmpty()) {
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    sLightToggleUrl = base + "/tv_light_toggle";
                    sLightStatusUrl = base + "/tv_light_status";
                    sLightMovieUrl  = base + "/tv_light_movie";
                    sLightGameUrl   = base + "/tv_light_game";
                }
                String toggle = lt.optString("toggle_url", "").trim();
                if (!toggle.isEmpty()) sLightToggleUrl = toggle;
                String status = lt.optString("status_url", "").trim();
                if (!status.isEmpty()) sLightStatusUrl = status;
                String movie = lt.optString("movie_url", "").trim();
                if (!movie.isEmpty()) sLightMovieUrl = movie;
                String game = lt.optString("game_url", "").trim();
                if (!game.isEmpty()) sLightGameUrl = game;
            }

            // 3. 提示词配置
            JSONObject pt = root.optJSONObject("prompt");
            if (pt != null) {
                String sys = pt.optString("system", "").trim();
                if (!sys.isEmpty()) {
                    sSystemPrompt = sys;
                }
            }

            // 4. 对话与拾音等待超时配置
            JSONObject cv = root.optJSONObject("conversation");
            if (cv != null) {
                long expireSec = cv.optLong("session_expire_seconds", 0);
                if (expireSec > 0) {
                    sSessionExpireMs = expireSec * 1000L;
                }
                long listenSec = cv.optLong("listen_timeout_seconds", 0);
                if (listenSec > 0) {
                    sListenTimeoutMs = listenSec * 1000L;
                }
            }

            Log.i(TAG, String.format("已载入配置 -> 网关: %s | 模型: %s | 灯具开关: %s | 等待: %d秒",
                    sLanUrl, sModel, sLightToggleUrl, sListenTimeoutMs / 1000));

        } catch (Throwable t) {
            Log.e(TAG, "解析配置文件异常，回退至安全默认值: " + t.getMessage(), t);
            loadFallbacksFromSystem();
        }
    }

    private static void loadFallbacksFromSystem() {
        sLanUrl = getSystemProperty("persist.tvhome.ai_url", DEFAULT_LAN_GATEWAY);
        sApiKey = getSystemProperty("persist.tvhome.ai_key", DEFAULT_API_KEY);
        sModel = getSystemProperty("persist.tvhome.ai_model", DEFAULT_MODEL);
        sSystemPrompt = DEFAULT_SYSTEM_PROMPT;
        sSessionExpireMs = DEFAULT_SESSION_EXPIRE_SECONDS * 1000L;
        sListenTimeoutMs = DEFAULT_LISTEN_TIMEOUT_SECONDS * 1000L;
    }

    private static String readFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return readStream(fis);
        } catch (Throwable t) {
            Log.w(TAG, "读取文件失败: " + file.getAbsolutePath() + " (" + t.getMessage() + ")");
            return null;
        }
    }

    private static String readStream(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    // =========================================================================
    // 公开 Getters
    // =========================================================================

    public static String getAiLanUrl() {
        checkReloadIfModified();
        return sLanUrl;
    }

    public static String getAiLocalUrl() {
        checkReloadIfModified();
        return sLocalUrl;
    }

    public static String getAiApiKey() {
        checkReloadIfModified();
        if (sApiKey != null && !sApiKey.trim().isEmpty()) {
            return sApiKey.trim();
        }
        if (sContext != null) {
            try {
                String key = Settings.Global.getString(sContext.getContentResolver(), "tvhome_ai_key");
                if (key != null && !key.trim().isEmpty()) {
                    return key.trim();
                }
            } catch (Throwable ignored) {}
        }
        return getSystemProperty("persist.tvhome.ai_key", DEFAULT_API_KEY);
    }

    public static String getAiModel() {
        checkReloadIfModified();
        if (sModel != null && !sModel.trim().isEmpty()) {
            return sModel.trim();
        }
        if (sContext != null) {
            try {
                String m = Settings.Global.getString(sContext.getContentResolver(), "tvhome_ai_model");
                if (m != null && !m.trim().isEmpty()) {
                    return m.trim();
                }
            } catch (Throwable ignored) {}
        }
        return getSystemProperty("persist.tvhome.ai_model", DEFAULT_MODEL);
    }

    public static String getSystemPrompt() {
        checkReloadIfModified();
        return sSystemPrompt;
    }

    public static String getLightToggleUrl() {
        checkReloadIfModified();
        return sLightToggleUrl;
    }

    public static String getLightStatusUrl() {
        checkReloadIfModified();
        return sLightStatusUrl;
    }

    public static String getLightMovieUrl() {
        checkReloadIfModified();
        return sLightMovieUrl;
    }

    public static String getLightGameUrl() {
        checkReloadIfModified();
        return sLightGameUrl;
    }

    public static long getSessionExpireMs() {
        checkReloadIfModified();
        return sSessionExpireMs;
    }

    public static long getListenTimeoutMs() {
        checkReloadIfModified();
        return sListenTimeoutMs;
    }

    /**
     * 网关成功响应回调：若为主网关成功，重置主服务熔断状态
     */
    public static void onEndpointSuccess(GatewayEndpoint endpoint) {
        if (endpoint != null && !endpoint.isFallback) {
            if (sPrimaryFailTimestamp > 0) {
                Log.i(TAG, "主 AI 网关 (" + endpoint.url + ") 已恢复连通，熔断器复位");
            }
            sPrimaryFailTimestamp = 0L;
        }
    }

    /**
     * 网关访问失败回调：若主服务失败，记录熔断时间戳开启 60 秒冷却
     */
    public static void onEndpointFailure(GatewayEndpoint endpoint) {
        if (endpoint != null && !endpoint.isFallback) {
            sPrimaryFailTimestamp = System.currentTimeMillis();
            Log.w(TAG, "主 AI 网关 (" + endpoint.url + ") 访问异常，开启 60 秒故障转移冷却（优先调度保底网关）");
        }
    }

    /**
     * 获取支持降级保底与智能熔断的网关候选列表
     */
    public static List<GatewayEndpoint> getCandidateEndpoints() {
        checkReloadIfModified();
        List<GatewayEndpoint> primaries = new ArrayList<>();
        List<GatewayEndpoint> fallbacks = new ArrayList<>();

        // 1. 系统设置动态配置 (Settings.Global tvhome_ai_url) 或主 LAN 网关
        String activePrimaryUrl = null;
        if (sContext != null) {
            try {
                String globalUrl = Settings.Global.getString(sContext.getContentResolver(), "tvhome_ai_url");
                if (globalUrl != null && !globalUrl.trim().isEmpty()) {
                    activePrimaryUrl = normalizeUrl(globalUrl);
                }
            } catch (Throwable ignored) {}
        }
        if (activePrimaryUrl == null && sLanUrl != null && !sLanUrl.trim().isEmpty()) {
            activePrimaryUrl = normalizeUrl(sLanUrl);
        }

        if (activePrimaryUrl != null) {
            primaries.add(new GatewayEndpoint(
                "Primary Gateway",
                activePrimaryUrl,
                getAiApiKey(),
                getAiModel(),
                "low",
                false,
                256,
                false
            ));
        }

        // 2. 本地回环 Loopback (若不同于 activePrimaryUrl)
        if (sLocalUrl != null && !sLocalUrl.trim().isEmpty()) {
            String normLocal = normalizeUrl(sLocalUrl);
            if (activePrimaryUrl == null || !activePrimaryUrl.equals(normLocal)) {
                primaries.add(new GatewayEndpoint(
                    "Local Loopback",
                    normLocal,
                    getAiApiKey(),
                    getAiModel(),
                    "low",
                    false,
                    256,
                    false
                ));
            }
        }

        // 3. 降级保底网关 (DeepSeek Cloud)
        if (sFallbackEnabled && sFallbackApiKey != null && !sFallbackApiKey.isEmpty()) {
            fallbacks.add(new GatewayEndpoint(
                (sFallbackName != null && !sFallbackName.isEmpty()) ? sFallbackName : "DeepSeek Cloud",
                normalizeUrl(sFallbackUrl),
                sFallbackApiKey,
                (sFallbackModel != null && !sFallbackModel.isEmpty()) ? sFallbackModel : "deepseek-flash",
                sFallbackReasoningEffort,
                sFallbackThinking,
                sFallbackMaxTokens > 0 ? sFallbackMaxTokens : 512,
                true
            ));
        }

        // 4. 熔断与自愈排序
        List<GatewayEndpoint> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        boolean primaryCircuitOpen = (sPrimaryFailTimestamp > 0 && (now - sPrimaryFailTimestamp < PRIMARY_RETRY_INTERVAL_MS));

        if (primaryCircuitOpen && !fallbacks.isEmpty()) {
            // 主服务此前失联且处于 60s 冷却期内：优先使用保底网关，避免每轮对话阻塞等待主服务超时
            result.addAll(fallbacks);
            result.addAll(primaries);
        } else {
            // 正常状态或冷却期已过：优先尝试主服务（探测自愈），失败再走保底网关
            result.addAll(primaries);
            result.addAll(fallbacks);
        }

        if (result.isEmpty()) {
            result.add(new GatewayEndpoint(
                "Default Loopback",
                DEFAULT_LOCAL_GATEWAY + "/chat/completions",
                "",
                DEFAULT_MODEL,
                "low",
                false,
                256,
                false
            ));
        }

        return result;
    }

    /**
     * 获取 AI 候选网关地址列表 (兼容旧版调用)
     */
    public static List<String> getCandidateUrls(String lastWorkingUrl) {
        List<String> list = new ArrayList<>();
        for (GatewayEndpoint ep : getCandidateEndpoints()) {
            if (!list.contains(ep.url)) {
                list.add(ep.url);
            }
        }
        return list;
    }

    private static String normalizeUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_LAN_GATEWAY + "/chat/completions";
        String trimmed = raw.trim();
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/chat/completions";
    }
}
