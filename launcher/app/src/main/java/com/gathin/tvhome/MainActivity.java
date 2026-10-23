package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.gathin.tvhome.weather.WeatherModule;

import java.util.Calendar;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private static final java.util.regex.Pattern SSID_PATTERN = java.util.regex.Pattern.compile("SSID:\\s*\"([^\"]+)\"");

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private WeatherModule weather;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private boolean isFirstResume = true;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            weather.refreshIfStale();
            TvLightController.refreshStatusAsync();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TvConfig.init(this);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));

        // Optional wallpaper with dark scrim
        boolean enableWallpaper = getResources().getBoolean(R.bool.enable_wallpaper);
        View wallpaper = findViewById(R.id.wallpaper);
        View wallpaperScrim = findViewById(R.id.wallpaperScrim);
        if (wallpaper != null && wallpaperScrim != null) {
            wallpaper.setVisibility(enableWallpaper ? View.VISIBLE : View.GONE);
            wallpaperScrim.setVisibility(enableWallpaper ? View.VISIBLE : View.GONE);
        }

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroName = findViewById(R.id.heroName);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        weather = new WeatherModule(this, findViewById(R.id.weatherCard));
        weather.start();

        grid = findViewById(R.id.grid);
        adapter = new AppAdapter(this, this);
        GridLayoutManager glm = new GridLayoutManager(this, COLUMNS);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                int dockCount = adapter.getDockCount();
                if (position < dockCount) {
                    if (position == dockCount - 1 && dockCount < COLUMNS) {
                        return COLUMNS - (dockCount - 1);
                    }
                    return 1;
                }
                return 1;
            }
        });
        grid.setLayoutManager(glm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(adapter, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(400f).setDampingRatio(0.78f));
        }

        View btnLightMovie = findViewById(R.id.btnLightMovie);
        View btnLightGame = findViewById(R.id.btnLightGame);
        View btnLightToggle = findViewById(R.id.btnLightToggle);
        View btnSettings = findViewById(R.id.btnSettings);

        if (btnLightMovie != null) {
            btnLightMovie.setOnClickListener(v -> {
                Log.i("TvHome", "btnLightMovie clicked");
                TvLightController.setMovieMode(this);
            });
        }
        if (btnLightGame != null) {
            btnLightGame.setOnClickListener(v -> {
                Log.i("TvHome", "btnLightGame clicked");
                TvLightController.setGameMode(this);
            });
        }
        if (btnLightToggle != null) {
            btnLightToggle.setOnClickListener(v -> {
                Log.i("TvHome", "btnLightToggle clicked");
                TvLightController.toggle(this);
            });
        }

        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));

        // Keep focus on the top bar at its ends and at the top; reliably fire clicks on DPAD_CENTER / ENTER.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    v.performClick();
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnLightMovie) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        if (btnLightMovie != null) btnLightMovie.setOnKeyListener(pillKeys);
        if (btnLightGame != null) btnLightGame.setOnKeyListener(pillKeys);
        if (btnLightToggle != null) btnLightToggle.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);

        // Start AI Voice Bridge Service
        com.gathin.tvhome.speech.AiVoiceBridgeService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        ambient.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        // 若进程被杀重启导致静态变量被清空，从 SharedPreferences 持久化读取，避免丢失上次停留焦点
        String returnKey = lastFocusedKey;
        int returnSlot = lastFocused;
        if (returnKey == null) {
            android.content.SharedPreferences sp = getSharedPreferences("tvhome_prefs", Context.MODE_PRIVATE);
            returnKey = sp.getString("last_focused_key", null);
            returnSlot = sp.getInt("last_focused_slot", 0);
            lastFocusedKey = returnKey;
            lastFocused = returnSlot;
        }
        final String effectiveKey = returnKey;
        final int effectiveSlot = returnSlot;
        adapter.reload();
        if (isFirstResume) {
            grid.scheduleLayoutAnimation();
            isFirstResume = false;
        }
        updateGreeting();
        updateWifiLabel();
        weather.refreshIfStale();
        TvLightController.refreshStatusAsync();
        handler.postDelayed(minuteTick, MINUTE_MS);
        resetIdleTimer();

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(effectiveKey);
            focusTile(pos >= 0 ? pos : effectiveSlot);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        idleHandler.removeCallbacks(idleScreensaverRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        idleHandler.removeCallbacks(idleScreensaverRunnable);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        if (weather != null) weather.stop();
    }

    // ---- Idle screensaver timer -----------------------------------------------------------------
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 15 * 60 * 1000L; // 15 minutes
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleScreensaverRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i("MainActivity", "桌面空闲超时 (" + getIdleTimeoutMs() + "ms)，启动防烧屏夜光漂移时钟屏保");
            ClockScreensaverActivity.start(MainActivity.this);
        }
    };

    private long getIdleTimeoutMs() {
        long timeout = DEFAULT_IDLE_TIMEOUT_MS;
        try {
            int sleepTimeout = Settings.Secure.getInt(getContentResolver(), "sleep_timeout", -1);
            if (sleepTimeout > 0 && sleepTimeout <= 24 * 3600 * 1000) {
                timeout = sleepTimeout;
            }
        } catch (Throwable ignored) {
        }
        return timeout;
    }

    private void resetIdleTimer() {
        idleHandler.removeCallbacks(idleScreensaverRunnable);
        idleHandler.postDelayed(idleScreensaverRunnable, getIdleTimeoutMs());
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetIdleTimer();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        resetIdleTimer();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        resetIdleTimer();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        sendBroadcast(new Intent(com.gathin.tvhome.speech.AiVoiceBridgeService.ACTION_EXIT_AI));
        View current = getCurrentFocus();
        if (current != null && current.getId() == R.id.card) {
            int pos = adapter.positionOf(lastFocusedKey);
            if (pos == 0) return;
        }
        grid.stopScroll();
        grid.smoothScrollToPosition(0);
        focusTile(0);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        if (lastFocusedKey != null) {
            getSharedPreferences("tvhome_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_focused_key", lastFocusedKey)
                    .putInt("last_focused_slot", position)
                    .apply();
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < adapter.getDockCount()) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-8 * density).setDuration(80).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(8 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(140).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(140).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(350).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int dockCount = adapter.getDockCount();
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < dockCount) return false;          // dock row: let focus move to the top bar
            if (position < dockCount + COLUMNS) {
                int offset = (position - dockCount) % COLUMNS;
                int target = Math.min(offset, dockCount - 1);
                focusTile(Math.max(0, target));
                return true;
            }
            focusTile(position - COLUMNS);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (position < dockCount) {
                if (dockCount < count) {
                    int target = Math.min(dockCount + position, count - 1);
                    focusTile(target);
                    return true;
                }
                return true;
            }
            int gridPos = position - dockCount;
            int totalGrid = count - dockCount;
            int lastRowStart = ((totalGrid - 1) / COLUMNS) * COLUMNS;
            if (gridPos >= lastRowStart) return true;     // already on the last row: stay put
            int target = Math.min(position + COLUMNS, count - 1);
            focusTile(target);
            return true;
        }
        return false;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        updateClockLayout();
    }

    private void updateClockLayout() {
        boolean is24 = DateFormat.is24HourFormat(this);
        View ampm = findViewById(R.id.ampm);
        if (ampm != null) {
            ampm.setVisibility(is24 ? View.GONE : View.VISIBLE);
        }
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                String ssid = null;

                // 1. Android 10+ (API 29+) 标准 TransportInfo
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        TransportInfo transportInfo = caps.getTransportInfo();
                        if (transportInfo instanceof WifiInfo) {
                            ssid = ((WifiInfo) transportInfo).getSSID();
                        }
                    } catch (Throwable ignored) {}
                }

                // 2. 传统 WifiManager.getConnectionInfo()
                if (ssid == null || ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x")) {
                    try {
                        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                        if (info != null) {
                            ssid = info.getSSID();
                        }
                    } catch (Throwable ignored) {}
                }

                // 3. 从 NetworkCapabilities 内部结构文本正则直接提取 (即使位置服务未开或权限受限也能准确获取)
                // 格式形如: SSID: "DZ1115-5G"
                if (ssid == null || ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x")) {
                    try {
                        String capsStr = caps.toString();
                        java.util.regex.Matcher m = SSID_PATTERN.matcher(capsStr);
                        if (m.find()) {
                            ssid = m.group(1);
                        }
                    } catch (Throwable ignored) {}
                }

                // 4. 反射读取 NetworkCapabilities 内部存储字段 mSSID
                if (ssid == null || ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x")) {
                    try {
                        java.lang.reflect.Field f = caps.getClass().getDeclaredField("mSSID");
                        f.setAccessible(true);
                        Object val = f.get(caps);
                        if (val instanceof String) {
                            ssid = (String) val;
                        }
                    } catch (Throwable ignored) {}
                }

                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) {
                        text = ssid;
                    }
                }
                Log.i("MainActivity", "updateWifiLabel: caps=" + caps + " => detected ssid=" + ssid + ", final text=" + text);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "updateWifiLabel error", e);
        }
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
