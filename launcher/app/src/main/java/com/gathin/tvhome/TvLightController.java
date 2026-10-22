package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 电视氛围灯 Home Assistant Webhook 控制器
 * 端点地址由 TvConfig (tvhome_config.json) 统一管理与热重载：
 * - 切换开关: /api/webhook/tv_light_toggle
 * - 状态查询: /api/webhook/tv_light_status
 * - 电影模式: /api/webhook/tv_light_movie (暖光 20%)
 * - 游戏模式: /api/webhook/tv_light_game (冷白光 80%)
 */
public class TvLightController {
    private static final String TAG = "TvLightController";

    // 默认兜底常量 (具体由 TvConfig 集中管理与热重载)
    public static final String URL_LIGHT_TOGGLE = "http://127.0.0.1:8123/api/webhook/tv_light_toggle";
    public static final String URL_LIGHT_STATUS = "http://127.0.0.1:8123/api/webhook/tv_light_status";
    public static final String URL_LIGHT_MOVIE  = "http://127.0.0.1:8123/api/webhook/tv_light_movie";
    public static final String URL_LIGHT_GAME   = "http://127.0.0.1:8123/api/webhook/tv_light_game";

    public static String getLightToggleUrl() { return TvConfig.getLightToggleUrl(); }
    public static String getLightStatusUrl() { return TvConfig.getLightStatusUrl(); }
    public static String getLightMovieUrl()  { return TvConfig.getLightMovieUrl(); }
    public static String getLightGameUrl()   { return TvConfig.getLightGameUrl(); }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    // 缓存最新已知状态 (null=未知, true=开, false=关)
    private static volatile Boolean sLastKnownIsOn = null;

    /**
     * 查询实时灯光状态
     * @return true=开, false=关, null=查询失败或未知
     */
    public static Boolean fetchStatusSync() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(getLightStatusUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream in = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                String resp = sb.toString().trim();
                Log.i(TAG, "fetchStatusSync response: " + resp);
                if (!resp.isEmpty()) {
                    JSONObject json = new JSONObject(resp);
                    String state = json.optString("state", "");
                    if ("on".equalsIgnoreCase(state)) {
                        sLastKnownIsOn = true;
                        return true;
                    } else if ("off".equalsIgnoreCase(state)) {
                        sLastKnownIsOn = false;
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchStatusSync failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    /**
     * 异步预热/刷新最新灯光状态
     */
    public static void refreshStatusAsync() {
        EXECUTOR.execute(TvLightController::fetchStatusSync);
    }

    /**
     * 执行开灯逻辑（带状态判断）：
     * 1. 优先查实时状态；若实时查询失败则回退到内存最新已知状态。
     * 2. 若当前已是开启状态，则直接跳过 toggle 请求，杜绝误关。
     * 3. 若当前处于关闭状态（或完全未知状态），则调用 toggle 开启。
     *    状态未知时直接盲发 toggle：底层有延迟容错，宁可执行也不打断语音体验。
     */
    public static void turnOnWithCheck(Context context) {
        EXECUTOR.execute(() -> {
            Boolean liveStatus = fetchStatusSync();
            Boolean current = (liveStatus != null) ? liveStatus : sLastKnownIsOn;
            Log.i(TAG, "turnOnWithCheck: liveStatus=" + liveStatus + ", sLastKnownIsOn=" + sLastKnownIsOn + ", current=" + current);
            if (Boolean.TRUE.equals(current)) {
                Log.i(TAG, "氛围灯当前已处于【开灯】状态，忽略 toggle 请求");
                return;
            }
            // 关灯状态或未知状态，触发 toggle 开启
            boolean success = triggerInternal(context, getLightToggleUrl());
            if (success) {
                sLastKnownIsOn = true;
            }
        });
    }

    /**
     * 执行关灯逻辑（带状态判断）：
     * 1. 优先查实时状态；若实时查询失败则回退到内存最新已知状态。
     * 2. 若当前已是关闭状态，则直接跳过 toggle 请求，杜绝误开。
     * 3. 若当前处于开启状态（或完全未知状态），则调用 toggle 关闭。
     *    状态未知时直接盲发 toggle：底层有延迟容错，宁可执行也不打断语音体验。
     */
    public static void turnOffWithCheck(Context context) {
        EXECUTOR.execute(() -> {
            Boolean liveStatus = fetchStatusSync();
            Boolean current = (liveStatus != null) ? liveStatus : sLastKnownIsOn;
            Log.i(TAG, "turnOffWithCheck: liveStatus=" + liveStatus + ", sLastKnownIsOn=" + sLastKnownIsOn + ", current=" + current);
            if (Boolean.FALSE.equals(current)) {
                Log.i(TAG, "氛围灯当前已处于【关灯】状态，忽略 toggle 请求");
                return;
            }
            // 开灯状态或未知状态，触发 toggle 关闭
            boolean success = triggerInternal(context, getLightToggleUrl());
            if (success) {
                sLastKnownIsOn = false;
            }
        });
    }

    public static void toggle(Context context) {
        EXECUTOR.execute(() -> {
            boolean success = triggerInternal(context, getLightToggleUrl());
            if (success && sLastKnownIsOn != null) {
                sLastKnownIsOn = !sLastKnownIsOn;
            }
        });
    }

    public static void setMovieMode(Context context) {
        EXECUTOR.execute(() -> {
            boolean success = triggerInternal(context, getLightMovieUrl());
            if (success) {
                sLastKnownIsOn = true;
            }
        });
    }

    public static void setGameMode(Context context) {
        EXECUTOR.execute(() -> {
            boolean success = triggerInternal(context, getLightGameUrl());
            if (success) {
                sLastKnownIsOn = true;
            }
        });
    }

    public static void trigger(Context context, String urlString) {
        EXECUTOR.execute(() -> triggerInternal(context, urlString));
    }

    private static boolean triggerInternal(Context context, String urlString) {
        Log.i(TAG, "Triggering light webhook: " + urlString);
        HttpURLConnection conn = null;
        boolean success = false;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            Log.i(TAG, "Light webhook GET " + urlString + " -> HTTP " + code);
            if (code >= 200 && code < 400) {
                success = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to call light webhook: " + urlString, e);
            success = false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // 失败不打扰用户：Home Assistant 离线属常见场景，仅记日志不弹 Toast。
        return success;
    }
}
