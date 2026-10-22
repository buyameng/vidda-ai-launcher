package com.gathin.tvhome;

import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Overrides for a small set of apps whose own artwork does not read well on a TV.
 *
 * Keep this deliberately small: every entry is a hand-made 640x360 banner in
 * {@code res/drawable-nodpi} plus, optionally, a friendlier label. Apps not listed
 * here keep using the banner (or icon) they ship with.
 */
final class AppOverrides {

    private static final Map<String, Integer> BANNERS;
    private static final Map<String, String> LABELS;

    static {
        Map<String, Integer> banners = new HashMap<>();
        banners.put("com.google.android.youtube.tv", R.drawable.banner_youtube);
        banners.put("com.google.android.youtube", R.drawable.banner_youtube);
        banners.put("com.quark.yun.tv", R.drawable.banner_quark);
        BANNERS = Collections.unmodifiableMap(banners);

        Map<String, String> labels = new HashMap<>();
        // the stock name is "夸克网盘TV版", which is long and wraps badly under a tile
        labels.put("com.quark.yun.tv", "Quark TV");
        LABELS = Collections.unmodifiableMap(labels);
    }

    private AppOverrides() {
    }

    /** Custom banner drawable resource for {@code pkg}, or 0 to use the app's own. */
    static int bannerRes(String pkg) {
        Integer res = BANNERS.get(pkg);
        return res == null ? 0 : res;
    }

    /** Replacement display name for {@code pkg}, or {@code fallback} to keep the app's own. */
    static String label(String pkg, String fallback) {
        String custom = LABELS.get(pkg);
        return custom == null ? fallback : custom;
    }
}
