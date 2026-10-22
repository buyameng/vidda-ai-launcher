package com.gathin.tvhome;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
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

        // 1. 尝试从应用私有存储读取 (getFilesDir)
        if (sContext != null) {
            File appFile = new File(sContext.getFilesDir(), CONFIG_FILE_NAME);
            if (appFile.exists() && appFile.canRead()) {
                jsonContent = readFile(appFile);
                if (jsonContent != null) {
                    source = appFile.getAbsolutePath();
                    sLastLoadedTimestamp = appFile.lastModified();
                }
            }
        }

        // 2. 尝试从外部公共存储读取 (/sdcard/tvhome_config.json)
        if (jsonContent == null) {
            File sdFile = new File("/sdcard/" + CONFIG_FILE_NAME);
            if (sdFile.exists() && sdFile.canRead()) {
                jsonContent = readFile(sdFile);
                if (jsonContent != null) {
                    source = sdFile.getAbsolutePath();
                    sLastLoadedTimestamp = sdFile.lastModified();
                }
            }
        }

        // 3. 尝试从 /data/local/tmp 读取
        if (jsonContent == null) {
            File tmpFile = new File("/data/local/tmp/" + CONFIG_FILE_NAME);
            if (tmpFile.exists() && tmpFile.canRead()) {
                jsonContent = readFile(tmpFile);
                if (jsonContent != null) {
                    source = tmpFile.getAbsolutePath();
                    sLastLoadedTimestamp = tmpFile.lastModified();
                }
            }
        }

        // 4. 尝试从 Assets 读取
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
     * 获取 AI 候选网关地址列表 (含上次成功优先机制)
     */
    public static List<String> getCandidateUrls(String lastWorkingUrl) {
        checkReloadIfModified();
        Set<String> candidates = new LinkedHashSet<>();

        // 1. 上次成功地址置顶
        if (lastWorkingUrl != null && !lastWorkingUrl.trim().isEmpty()) {
            candidates.add(normalizeUrl(lastWorkingUrl));
        }

        // 2. Settings.Global tvhome_ai_url 动态配置
        if (sContext != null) {
            try {
                String globalUrl = Settings.Global.getString(sContext.getContentResolver(), "tvhome_ai_url");
                if (globalUrl != null && !globalUrl.trim().isEmpty()) {
                    candidates.add(normalizeUrl(globalUrl));
                }
            } catch (Throwable ignored) {}
        }

        // 3. 配置文件设定的主 LAN 网关
        if (sLanUrl != null && !sLanUrl.trim().isEmpty()) {
            candidates.add(normalizeUrl(sLanUrl));
        }

        // 4. 本地回环 Loopback
        if (sLocalUrl != null && !sLocalUrl.trim().isEmpty()) {
            candidates.add(normalizeUrl(sLocalUrl));
        }

        return new ArrayList<>(candidates);
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) return DEFAULT_LAN_GATEWAY + "/chat/completions";
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
