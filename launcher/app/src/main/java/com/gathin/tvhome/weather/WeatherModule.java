package com.gathin.tvhome.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.gathin.tvhome.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Top-bar weather card for a fixed city (config.xml: weather_city_code).
 *
 * <p>Data comes from China Weather (weather.com.cn, 中国气象局), which answers in tens of milliseconds from
 * inside the mainland and needs no API key. Before 18:00 the card shows today, from 18:00 on it shows
 * tomorrow, so the evening glance answers "what do I wear tomorrow". The card is decorative: when the
 * network is down it keeps the last forecast for a while and then hides itself.
 */
public final class WeatherModule {

    private static final String PREF = "weather";
    private static final String K_JSON = "json";
    private static final String K_AT = "at";
    private static final String K_CODE = "code";

    private static final long TTL_MS = 30 * 60 * 1000L;
    /** After a failed request, wait this long before trying again (offline TV: no request every minute). */
    private static final long RETRY_MS = 5 * 60 * 1000L;
    /** A cached forecast older than this is no longer worth showing. */
    private static final long MAX_STALE_MS = 12 * 60 * 60 * 1000L;
    /** From this hour on, show tomorrow instead of today. */
    private static final int EVENING_HOUR = 18;

    private final Context ctx;
    private final View card;
    private final WeatherIconView icon;
    private final TextView label;
    private final TextView temp;
    private final TextView advice;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final boolean enabled;
    private final String cityCode;
    private boolean busy;
    private boolean stopped;
    private long nextAttemptAt;
    /** What is currently on screen (city + day), so a minute tick does not redraw. */
    private String renderedKey;

    public WeatherModule(Context context, View card) {
        this.ctx = context.getApplicationContext();
        this.card = card;
        this.icon = card.findViewById(R.id.weatherIcon);
        this.label = card.findViewById(R.id.weatherLabel);
        this.temp = card.findViewById(R.id.weatherTemp);
        this.advice = card.findViewById(R.id.weatherAdvice);
        this.prefs = this.ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.enabled = this.ctx.getResources().getBoolean(R.bool.enable_weather);
        this.cityCode = this.ctx.getString(R.string.weather_city_code);
        // INVISIBLE (not GONE) while enabled: the card keeps its slot so the pills below never jump
        // when the forecast arrives or the network is down. Disabled means the slot goes away.
        card.setVisibility(enabled ? View.INVISIBLE : View.GONE);
    }

    /** Call from onCreate. */
    public void start() {
        if (enabled) refreshIfStale();
    }

    /** Call from onResume and from the minute tick; network access only happens when the cache is old. */
    public void refreshIfStale() {
        if (!enabled || busy || stopped) return;
        final boolean tomorrow = isEvening();
        final String key = targetDate() + (tomorrow ? "+1" : "") + "@" + cityCode;

        String json = prefs.getString(K_JSON, null);
        long age = System.currentTimeMillis() - prefs.getLong(K_AT, 0);
        boolean sameCity = cityCode.equals(prefs.getString(K_CODE, null));
        if (!sameCity) json = null;
        if (json != null && age > MAX_STALE_MS) json = null;
        final String cached = json;
        boolean fresh = cached != null && age < TTL_MS;

        if (fresh) {
            if (!key.equals(renderedKey)) {
                render(cached, tomorrow);
                renderedKey = key;
            }
            return;
        }
        if (System.currentTimeMillis() < nextAttemptAt) {
            // Backing off after a failure: keep showing whatever we already have.
            if (cached != null && !key.equals(renderedKey)) {
                render(cached, tomorrow);
                renderedKey = key;
            }
            return;
        }

        busy = true;
        io.execute(() -> {
            String fetched = null;
            try {
                fetched = fetch(cityCode);
            } catch (Exception ignored) {
            }
            final String result = fetched;
            main.post(() -> {
                busy = false;
                if (stopped) return;
                if (result != null) {
                    prefs.edit()
                            .putString(K_JSON, result)
                            .putLong(K_AT, System.currentTimeMillis())
                            .putString(K_CODE, cityCode)
                            .apply();
                    render(result, tomorrow);
                    renderedKey = key;
                    nextAttemptAt = 0;
                } else if (cached != null) {
                    // Offline: an older forecast is still better than an empty corner.
                    render(cached, tomorrow);
                    renderedKey = key;
                } else {
                    nextAttemptAt = System.currentTimeMillis() + RETRY_MS;
                }
            });
        });
    }

    public void stop() {
        stopped = true;
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    // ---- data ------------------------------------------------------------------------------------

    /**
     * weather_index is the payload behind weather.com.cn's own forecast page: current conditions, the
     * five-day list and the life indices (including 穿衣指数). It is plain JS assignments, so it is
     * pulled apart with a small regex instead of a JSON parser.
     */
    private static String fetch(String code) throws Exception {
        URL url = new URL("https://d1.weather.com.cn/weather_index/" + code + ".html");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            // The endpoint rejects requests without a browser-like agent and a weather.com.cn referer.
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
            conn.setRequestProperty("Referer", "https://www.weather.com.cn/weather/" + code + ".shtml");
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            String body = out.toString(StandardCharsets.UTF_8.name());
            return body.contains("dataSK") && body.contains("fc") ? body : null;
        } finally {
            conn.disconnect();
        }
    }

    /** Reads one `var name = {...}` assignment; the last block in the payload may lack its semicolon. */
    private static JSONObject block(String body, String name) {
        int start = body.indexOf("var " + name);
        if (start < 0) return null;
        int eq = body.indexOf('=', start);
        if (eq < 0) return null;
        int end = body.indexOf(";var ", eq);
        if (end < 0) end = body.length();
        String js = body.substring(eq + 1, end).trim();
        if (js.endsWith(";")) js = js.substring(0, js.length() - 1);
        try {
            return new JSONObject(js);
        } catch (Exception e) {
            return null;
        }
    }

    /** From 18:00 the card answers "what is tomorrow like". */
    private static boolean isEvening() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) >= EVENING_HOUR;
    }

    /** Device-local date, used only as part of the cache key so the card re-renders after midnight. */
    private static String targetDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    // ---- rendering -------------------------------------------------------------------------------

    private void render(String body, boolean tomorrow) {
        try {
            JSONObject now = block(body, "dataSK");
            JSONObject today = block(body, "cityDZ");
            JSONObject forecast = block(body, "fc");
            JSONObject indices = block(body, "dataZS");
            if (now == null || forecast == null) throw new IllegalStateException("missing blocks");

            JSONArray days = forecast.getJSONArray("f");
            int idx = tomorrow ? 1 : 0;
            if (idx >= days.length()) idx = days.length() - 1;
            JSONObject day = days.getJSONObject(idx);

            // Today follows the live observation (the daily code describes the whole day, which can say
            // "overcast" while the sky is clear right now); tomorrow uses the daily forecast.
            int code;
            boolean night = false;
            String live = now.optString("weathercode");
            if (!tomorrow && live.length() >= 3) {
                night = live.charAt(0) == 'n';
                code = wmoFromWeatherCode(live.substring(live.length() - 2));
            } else {
                code = wmoFromWeatherCode(day.optString("fa"));
            }
            double tmax = day.optDouble("fc", 0);
            double tmin = day.optDouble("fd", 0);
            int wind = kmhFromLevel(day.optString("fg"));

            label.setText(ctx.getString(tomorrow ? R.string.weather_tomorrow : R.string.weather_today)
                    + " · " + (tomorrow ? weatherText(day) : currentWeatherText(now, today)));
            temp.setText(Math.round(tmin) + "° / " + Math.round(tmax) + "°");

            icon.setKind(weatherIcon(code, night));
            advice.setText(adviceText(code, tmin, tmax, wind, indices));

            if (card.getVisibility() != View.VISIBLE) {
                card.setAlpha(0f);
                card.setVisibility(View.VISIBLE);
                card.animate().alpha(1f).setDuration(400).start();
            }
        } catch (Exception e) {
            card.setVisibility(enabled ? View.INVISIBLE : View.GONE);
        }
    }

    /** Clothing and umbrella advice as one short phrase, e.g. 「薄衫 · 早晚添衣」. */
    private String adviceText(int code, double tmin, double tmax, int wind, JSONObject indices) {
        List<Integer> items = new ArrayList<>();

        // The clothing advice follows 中国气象局's 穿衣指数 hint when the payload carries one.
        String hint = comfortHintRaw(indices);
        if (hint.contains("炎热") || hint.contains("热")) {
            items.add(R.string.advice_tshirt);
        } else if (hint.contains("舒适") || hint.contains("温凉")) {
            items.add(R.string.advice_light);
        } else if (hint.contains("较冷") || hint.contains("凉")) {
            items.add(R.string.advice_jacket);
        } else if (hint.contains("冷") || hint.contains("寒冷")) {
            items.add(R.string.advice_down);
        } else if (tmax >= 28) {
            items.add(R.string.advice_tshirt);
        } else if (tmax >= 22) {
            items.add(R.string.advice_light);
        } else if (tmax >= 16) {
            items.add(R.string.advice_jacket);
        } else if (tmax >= 8) {
            items.add(R.string.advice_coat);
        } else {
            items.add(R.string.advice_down);
        }

        if (isSnow(code)) {
            items.add(R.string.advice_snow);
        } else if (isRain(code)) {
            items.add(R.string.advice_umbrella);
        } else if (tmax - tmin >= 10) {
            items.add(R.string.advice_swing);
        } else if (wind >= 30) {
            items.add(R.string.advice_wind);
        }
        while (items.size() > 2) items.remove(items.size() - 1);

        StringBuilder out = new StringBuilder();
        for (int res : items) {
            if (out.length() > 0) out.append(" · ");
            out.append(ctx.getString(res));
        }
        return out.toString();
    }

    // ---- mapping ---------------------------------------------------------------------------------

    private String currentWeatherText(JSONObject now, JSONObject today) {
        String w = now.optString("weather");
        if (w.isEmpty() && today != null && today.optJSONObject("weatherinfo") != null) {
            w = today.optJSONObject("weatherinfo").optString("weather");
        }
        return w.isEmpty() ? ctx.getString(R.string.wmo_cloudy) : w;
    }

    private String weatherText(JSONObject day) {
        String w = weatherName(day.optString("fa"));
        return w.isEmpty() ? ctx.getString(R.string.wmo_cloudy) : w;
    }

    /** weather.com.cn day codes: 00 clear, 01 cloudy, 02 overcast, 03/04/05 showers, 07-12 rain, 13-17 snow, 18+ fog. */
    private String weatherName(String fa) {
        switch (fa) {
            case "00": return ctx.getString(R.string.wmo_clear);
            case "01": return ctx.getString(R.string.wmo_partly_cloudy);
            case "02": return ctx.getString(R.string.wmo_overcast);
            case "03": case "04": case "05": return ctx.getString(R.string.wmo_showers);
            case "06": case "07": case "09": case "21": return ctx.getString(R.string.wmo_light_rain);
            case "08": return ctx.getString(R.string.wmo_rain);
            case "10": case "11": case "12": return ctx.getString(R.string.wmo_heavy_rain);
            case "13": case "14": case "15": return ctx.getString(R.string.wmo_snow);
            case "16": case "17": return ctx.getString(R.string.wmo_heavy_snow);
            case "18": case "19": case "20": case "29": case "30": case "53": return ctx.getString(R.string.wmo_fog);
            default: return "";
        }
    }

    private static int wmoFromWeatherCode(String fa) {
        switch (fa) {
            case "00": return 0;
            case "01": return 2;
            case "02": return 3;
            case "03": case "05": return 80;
            case "04": return 95;
            case "06": case "07": case "09": case "21": return 61;
            case "08": case "10": case "11": case "12": return 65;
            case "13": case "14": case "15": return 71;
            case "16": case "17": return 75;
            case "18": case "19": case "20": case "29": case "30": case "53": return 45;
            default: return 3;
        }
    }

    private static int weatherIcon(int code, boolean night) {
        if (code == 0) return night ? WeatherIconView.MOON : WeatherIconView.SUN;
        if (code == 1 || code == 2) return night ? WeatherIconView.NIGHT_PARTLY_CLOUDY : WeatherIconView.PARTLY_CLOUDY;
        if (code == 3) return WeatherIconView.CLOUD;
        if (code == 45) return WeatherIconView.FOG;
        if (code >= 95) return WeatherIconView.THUNDER;
        if (isSnow(code)) return WeatherIconView.SNOW;
        if (isRain(code)) return WeatherIconView.RAIN;
        return WeatherIconView.CLOUD;
    }

    private static boolean isRain(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
    }

    private static boolean isSnow(int code) {
        return (code >= 71 && code <= 77) || code == 85 || code == 86;
    }

    private static final Pattern LEVEL_DIGIT_PATTERN = Pattern.compile("(\\d+)");

    /** "3-4级" → a rough km/h figure, only used to decide whether to show a wind advice chip. */
    private static int kmhFromLevel(String level) {
        Matcher m = LEVEL_DIGIT_PATTERN.matcher(level);
        if (!m.find()) return 0;
        return Integer.parseInt(m.group(1)) * 6;
    }

    /** 穿衣指数 hint, e.g. 较冷 / 舒适 / 炎热. */
    private static String comfortHintRaw(JSONObject indices) {
        if (indices == null) return "";
        JSONObject zs = indices.optJSONObject("zs");
        return zs == null ? "" : zs.optString("ct_hint");
    }
}
