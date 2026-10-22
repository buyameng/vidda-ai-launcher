package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    private static final String PREF_KEY_DOCK = "custom_dock_apps";

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        final boolean isLeanback;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, boolean isLeanback) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.isLeanback = isLeanback;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    private final Context ctx;
    private final TileListener listener;
    private final SharedPreferences prefs;
    private int dockCount = 0;
    /** Dock order (res/values/config.xml, dock_apps or customized). */
    private final List<String> favourites = new ArrayList<>();
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    /** Phone-style apps to include anyway (res/values/config.xml, extra_apps). */
    private final Set<String> extras;
    private final List<Entry> items = new ArrayList<>();
    private static final Map<ComponentName, Integer> COLOR_CACHE = new HashMap<>();

    AppAdapter(Context ctx, TileListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.prefs = ctx.getSharedPreferences("tvhome_prefs", Context.MODE_PRIVATE);
        loadFavourites();
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        this.extras = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.extra_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    private void loadFavourites() {
        favourites.clear();
        String saved = prefs.getString(PREF_KEY_DOCK, null);
        if (saved != null && !saved.trim().isEmpty()) {
            String[] parts = saved.split(",");
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty() && !favourites.contains(trimmed)) {
                    favourites.add(trimmed);
                }
            }
        } else {
            String[] defaults = ctx.getResources().getStringArray(R.array.dock_apps);
            Collections.addAll(favourites, defaults);
        }
    }

    private void saveFavourites() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < favourites.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(favourites.get(i));
        }
        prefs.edit().putString(PREF_KEY_DOCK, sb.toString()).apply();
    }

    public boolean isDockApp(String pkg) {
        return favourites.contains(pkg);
    }

    public void pinToDock(String pkg) {
        if (!favourites.contains(pkg)) {
            if (favourites.size() >= MainActivity.COLUMNS) {
                Toast.makeText(ctx, R.string.dock_full, Toast.LENGTH_SHORT).show();
                return;
            }
            favourites.add(pkg);
            saveFavourites();
            reload();
        }
    }

    public void unpinFromDock(String pkg) {
        if (favourites.remove(pkg)) {
            saveFavourites();
            reload();
        }
    }

    public int getDockCount() {
        return dockCount;
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        List<Entry> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // TV apps first.
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(pm, probe, fresh, seen, false, true);

        // Then phone-style apps, but only the ones explicitly listed in dock_apps or extra_apps.
        // Without this a system app such as the media centre, which declares LAUNCHER only, could
        // never appear.
        Intent probePhone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        collect(pm, probePhone, fresh, seen, true, false);

        Collator collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.PRIMARY);

        // Precompute dock order to avoid linear list scans inside comparator
        final Map<String, Integer> favOrder = new HashMap<>(favourites.size());
        for (int i = 0; i < favourites.size(); i++) {
            favOrder.put(favourites.get(i), i);
        }

        Collections.sort(fresh, (a, b) -> {
            Integer fa = favOrder.get(a.pkg);
            Integer fb = favOrder.get(b.pkg);
            if (fa != null || fb != null) {
                if (fa == null) return 1;
                if (fb == null) return -1;
                return Integer.compare(fa, fb);
            }
            return collator.compare(a.label, b.label);
        });

        // Compute actual installed dock apps count
        int actualDock = 0;
        for (Entry e : fresh) {
            if (favourites.contains(e.pkg)) {
                actualDock++;
            } else {
                break;
            }
        }
        this.dockCount = actualDock;

        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    private void collect(PackageManager pm, Intent probe, List<Entry> out, Set<String> seen,
                         boolean pinnedOnly, boolean isLeanback) {
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            if (pinnedOnly && !favourites.contains(pkg) && !extras.contains(pkg)) continue;
            if (!seen.add(pkg)) continue;
            Drawable banner = null;
            int customRes = AppOverrides.bannerRes(pkg);
            if (customRes != 0) banner = ctx.getDrawable(customRes);
            if (banner == null) banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = AppOverrides.label(pkg, String.valueOf(ri.loadLabel(pm)));
            out.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon, isLeanback));
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, this, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockCount ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
            h.label.setAlpha(focused ? 0f : 1f);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            showAppMenu(e);
            return true;
        });
    }

    void showAppMenu(Entry e) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle(e.label);
        boolean isPinned = isDockApp(e.pkg);
        CharSequence[] options = new CharSequence[]{
                ctx.getString(isPinned ? R.string.unpin_from_dock : R.string.pin_to_dock),
                ctx.getString(R.string.app_details),
                ctx.getString(R.string.uninstall)
        };
        b.setItems(options, (dialog, which) -> {
            if (which == 0) {
                if (isPinned) {
                    unpinFromDock(e.pkg);
                    Toast.makeText(ctx, R.string.unpinned_toast, Toast.LENGTH_SHORT).show();
                } else {
                    pinToDock(e.pkg);
                    Toast.makeText(ctx, R.string.pinned_toast, Toast.LENGTH_SHORT).show();
                }
            } else if (which == 1) {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(i);
                } catch (ActivityNotFoundException ignored) {
                }
            } else if (which == 2) {
                Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + e.pkg));
                i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(i);
                } catch (Exception ex) {
                    Intent fallback = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg));
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        ctx.startActivity(fallback);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        b.show();
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(e.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (e.isLeanback) {
            i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        } else {
            i.addCategory(Intent.CATEGORY_LAUNCHER);
        }
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            try {
                Intent fallback = ctx.getPackageManager().getLaunchIntentForPackage(e.pkg);
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    ctx.startActivity(fallback);
                    return;
                }
            } catch (Exception ignored) {
            }
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient != 0) return e.ambient;
        Integer cached = COLOR_CACHE.get(e.component);
        if (cached != null) {
            e.ambient = cached;
            return cached;
        }
        e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        COLOR_CACHE.put(e.component, e.ambient);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        int[] pixels = new int[n * n];
        bmp.getPixels(pixels, 0, n, 0, 0, n, n);
        bmp.recycle();

        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : pixels) {
            r += Color.red(p);
            g += Color.green(p);
            b += Color.blue(p);
            Color.colorToHSV(p, hsv);
            float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, AppAdapter adapter, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 16f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && pos < adapter.items.size()) {
                        adapter.showAppMenu(adapter.items.get(pos));
                        return true;
                    }
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 180 : 150)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.15f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(180).start();
                if (label.getVisibility() == View.VISIBLE || label.getText().length() > 0) {
                    label.animate().alpha(focused ? 0f : 1f).setDuration(150).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
