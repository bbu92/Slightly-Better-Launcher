package com.bbu.sbl;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.LruCache;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Called when OK is held on a tile. */
        void onTileLongPress(int position);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        /** Whether the app ships a banner; the artwork itself is decoded only when the tile is shown. */
        final boolean hasBanner;
        private final Supplier<Drawable> bannerSrc, iconSrc;
        private final String key;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;

        Entry(String label, ComponentName component, boolean hasBanner, Supplier<Drawable> bannerSrc, Supplier<Drawable> iconSrc) {
            this(APP, label, component, hasBanner, bannerSrc, iconSrc, null, 0);
        }

        Entry(int kind, String label, ComponentName component, boolean hasBanner, Supplier<Drawable> bannerSrc,
              Supplier<Drawable> iconSrc, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.hasBanner = hasBanner;
            this.bannerSrc = bannerSrc;
            this.iconSrc = iconSrc;
            this.key = component.flattenToShortString();
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }

        /** Decodes on first use (blocking) and keeps the result in the shared artwork cache. */
        Drawable banner() {
            return hasBanner ? art(key + "#b", bannerSrc, true) : null;
        }

        Drawable icon() {
            return art(key + "#i", iconSrc, true);
        }

        Drawable cachedBanner() {
            return hasBanner ? art(key + "#b", bannerSrc, false) : null;
        }

        Drawable cachedIcon() {
            return art(key + "#i", iconSrc, false);
        }

        private static Drawable art(String k, Supplier<Drawable> src, boolean load) {
            Drawable d = ART.get(k);
            if (d == null && load && src != null) {
                d = src.get();
                if (d != null) ART.put(k, d);
            }
            return d;
        }
    }

    /** Decoded tile artwork, bounded: only what is on screen or just off it stays in memory. */
    private static final LruCache<String, Drawable> ART = new LruCache<String, Drawable>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Drawable d) {
            return d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap().getByteCount() : 256 * 1024;
        }
    };
    private static final ExecutorService ART_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tile-art");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Forgets all decoded artwork and cached labels (an app was installed, updated or removed). */
    static void clearArt() {
        ART.evictAll();
        LABELS.clear();
    }

    private static final Map<String, String> LABELS = new HashMap<>();

    private static String labelOf(PackageManager pm, ResolveInfo ri, ComponentName component) {
        String key = component.flattenToShortString();
        String l = LABELS.get(key);
        if (l == null) {
            l = String.valueOf(ri.loadLabel(pm));
            LABELS.put(key, l);
        }
        return l;
    }

    /** Lets go of the artwork the visible tiles hold; they decode again when the launcher comes back. */
    void releaseArt(RecyclerView grid) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(grid.getChildAt(i));
            if (vh instanceof Holder) {
                ((Holder) vh).banner.setImageDrawable(null);
                ((Holder) vh).icon.setImageDrawable(null);
                ((Holder) vh).boundKey = null;
            }
        }
        ART.evictAll();
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Labels under the grid tiles are always shown, softer until the tile has focus. */
    static final float RESTING_LABEL_ALPHA = 0.72f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 150f, DESIGN_H_DP = 84f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean showHomeSettings;
    private String sortMode;
    private List<String> manualOrder;
    private final List<Entry> items = new ArrayList<>();
    /** How many leading tiles are favourites (the pinned part of the dock row). */
    private int favCount;

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.sortMode = cfg.sortMode;
        this.manualOrder = cfg.manualOrder;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            final android.content.pm.ApplicationInfo app = ri.activityInfo.applicationInfo;
            final int bannerRes = ri.activityInfo.getBannerResource() != 0 ? ri.activityInfo.getBannerResource() : app.banner;
            final int iconRes = ri.activityInfo.getIconResource() != 0 ? ri.activityInfo.getIconResource() : app.icon;
            final android.content.pm.ActivityInfo ai = ri.activityInfo;
            Supplier<Drawable> bannerSrc = () -> {
                Drawable b = sharp(pm, app, bannerRes);
                if (b == null) b = ai.loadBanner(pm);
                if (b == null) b = app.loadBanner(pm);
                return b;
            };
            Supplier<Drawable> iconSrc = () -> {
                Drawable i = sharp(pm, app, iconRes);
                return i != null ? i : ai.loadIcon(pm);
            };
            boolean hasBanner = bannerRes != 0;
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, hasBanner, bannerSrc, iconSrc, card.caption, card.accent));
            } else {
                fresh.add(new Entry(labelOf(pm, ri, component), component, hasBanner, bannerSrc, iconSrc));
            }
        }
        final Map<String, ?> usage = "recent".equals(sortMode) || "most".equals(sortMode)
                ? UsageTracker.snapshot(ctx) : Collections.<String, Object>emptyMap();
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            if ("manual".equals(sortMode)) {
                int ma = manualOrder.indexOf(a.pkg), mb = manualOrder.indexOf(b.pkg);
                if (ma >= 0 || mb >= 0) {
                    if (ma < 0) return 1;
                    if (mb < 0) return -1;
                    return Integer.compare(ma, mb);
                }
            }
            // Cards come right after the configured dock apps, before the rest of the grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            if ("recent".equals(sortMode)) {
                int c = Long.compare(UsageTracker.lastUsed(usage, b.pkg), UsageTracker.lastUsed(usage, a.pkg));
                if (c != 0) return c;
            } else if ("most".equals(sortMode)) {
                int c = Integer.compare(UsageTracker.count(usage, b.pkg), UsageTracker.count(usage, a.pkg));
                if (c != 0) return c;
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        favCount = 0;
        while (favCount < fresh.size() && favourites.contains(fresh.get(favCount).pkg)) favCount++;
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            final Drawable homeIcon = ctx.getDrawable(R.drawable.ic_home_settings);
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), false, null,
                    () -> homeIcon, null, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /**
     * An app's banner or icon at a density above the screen's (at least xxhdpi, up to xxxhdpi), so the
     * tile shows the sharpest artwork the app ships and the GPU only ever scales it down. Null when the
     * resource can't be loaded; the caller then falls back to the system's own lookup.
     */
    private Drawable sharp(PackageManager pm, android.content.pm.ApplicationInfo app, int resId) {
        if (resId == 0) return null;
        try {
            int dpi = Math.min(DisplayMetrics.DENSITY_XXXHIGH,
                    Math.max(DisplayMetrics.DENSITY_XXHIGH, ctx.getResources().getDisplayMetrics().densityDpi));
            return pm.getResourcesForApplication(app).getDrawableForDensity(resId, dpi, null);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener);
    }

    /** Whether the rows under the dock are shown; closed, they are fully transparent until focus goes down. */
    private boolean revealed = true;

    boolean revealed() {
        return revealed;
    }

    void setRevealed(boolean revealed) {
        this.revealed = revealed;
    }

    /** Views that come back from the recycler's cache are not bound again, so their opacity is set here. */
    @Override
    public void onViewAttachedToWindow(@NonNull Holder h) {
        super.onViewAttachedToWindow(h);
        int pos = h.getBindingAdapterPosition();
        if (pos >= dockSize && revealed && h.itemView.getAlpha() < 1f) h.itemView.setAlpha(1f);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        h.itemView.animate().cancel();
        h.itemView.setTranslationY(0f);
        h.itemView.setAlpha(position >= dockSize && !revealed ? 0f : 1f);
        Entry e = items.get(position);
        h.boundKey = e.component.flattenToShortString();
        h.name.setText(e.name);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : RESTING_LABEL_ALPHA);
        h.name.setTranslationY(0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.hasBanner) {
            Drawable cached = e.cachedBanner();
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
            if (cached != null) {
                h.banner.setAlpha(1f);
                h.banner.setImageDrawable(cached);
            } else {
                h.banner.setImageDrawable(null);
                loadAsync(h, e, true);
            }
        } else {
            showIcon(h, e);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileLongPress(pos);
            return true;
        });
    }

    /** The icon and name for an app with no banner (or one that would not decode). */
    private void showIcon(Holder h, Entry e) {
        h.banner.setImageDrawable(null);
        h.banner.setVisibility(View.GONE);
        h.label.setText(e.label);
        h.label.setVisibility(View.VISIBLE);
        h.icon.setVisibility(View.VISIBLE);
        Drawable cached = e.cachedIcon();
        if (cached != null) {
            h.icon.setImageDrawable(cached);
        } else {
            h.icon.setImageDrawable(null);
            loadAsync(h, e, false);
        }
    }

    /** Decodes artwork off the main thread, then shows it if the tile still belongs to the same app. */
    private void loadAsync(Holder h, Entry e, boolean banner) {
        final String key = e.component.flattenToShortString();
        h.boundKey = key;
        ART_IO.execute(() -> {
            final Drawable d = banner ? e.banner() : e.icon();
            MAIN.post(() -> {
                if (!key.equals(h.boundKey)) return;
                if (d == null) {
                    if (banner) showIcon(h, e);
                    return;
                }
                ImageView target = banner ? h.banner : h.icon;
                target.setAlpha(0f);
                target.setImageDrawable(d);
                target.animate().alpha(1f).setDuration(140).start();
            });
        });
    }

    /** The system's App info page for a package. */
    void openAppInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            UsageTracker.record(ctx, e.pkg);
        } catch (Exception ex) {
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

    String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : "";
    }

    /** Whether a tile belongs to the pinned favourites (the dock apps from the config), not just to the dock row. */
    int favouriteCount() {
        return favCount;
    }

    boolean isFavourite(int position) {
        return position >= 0 && position < favCount;
    }

    /**
     * Moves a tile anywhere, also into or out of the dock row, and saves the result: the first dock-size
     * tiles become the favourites, the rest the manual order (which switches the sort mode to manual).
     * Returns the new position, or the old one when the move is not possible.
     */
    int move(int from, int to) {
        if (from < 0 || from >= items.size() || items.get(from).kind == Entry.HOME_SETTINGS) return from;
        int last = items.size() - 1;
        while (last > 0 && items.get(last).kind == Entry.HOME_SETTINGS) last--;
        if (to < 0 || to > last || to == from) return from;
        Entry e = items.remove(from);
        items.add(to, e);
        List<String> dock = new ArrayList<>();
        for (int i = 0; i < Math.min(dockSize, items.size()); i++) {
            if (items.get(i).kind != Entry.HOME_SETTINGS) dock.add(items.get(i).pkg);
        }
        List<String> order = new ArrayList<>();
        for (int i = dockSize; i < items.size(); i++) {
            if (items.get(i).kind != Entry.HOME_SETTINGS) order.add(items.get(i).pkg);
        }
        favourites = dock;
        favCount = dock.size();
        manualOrder = order;
        sortMode = "manual";
        Prefs.putList(ctx, Prefs.DOCK_APPS, dock);
        Prefs.putList(ctx, Prefs.MANUAL_ORDER, order);
        Prefs.putString(ctx, Prefs.SORT_MODE, "manual");
        notifyDataSetChanged();
        return to;
    }

    /** Sort mode this adapter was built with. */
    String sortMode() {
        return sortMode;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) {
            Drawable d = e.banner();
            e.ambient = vividColor(d != null ? d : e.icon());
        }
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
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
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
        }
        bmp.recycle();
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

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        /** The app whose artwork this tile is waiting for or showing; a late decode for another app is dropped. */
        String boundKey;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 140 : 110)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                        .start();
                name.animate().alpha(focused ? 1f : RESTING_LABEL_ALPHA)
                        .translationY(focused ? density * 4f : 0f)
                        .setDuration(140).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
