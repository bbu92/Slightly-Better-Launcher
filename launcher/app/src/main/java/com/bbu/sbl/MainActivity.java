package com.bbu.sbl;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

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
    private TextView weatherView;
    private View btnInputs;
    private TextView btnAirplay;
    private LinearLayout topButtons;
    /** Full text of each top-bar pill; the pills show only their icon until focused. */
    private final java.util.Map<TextView, CharSequence> pillText = new java.util.HashMap<>();
    private TextView btnWifi;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Config cfg;
    /** Prefs.version() this activity was built from; a change means the settings menu was used. */
    private int loadedVersion;
    private DockDecoration dockDeco;
    /** Row of connected inputs under the dock. */
    private LinearLayout inputsRow;
    private LinearLayout inputsPills;
    private View inputsGlow;
    private boolean showInputsRow;
    private int inputPills;
    /** Last dock tile that had focus, where the inputs row and the shelf send focus back to. */
    private int lastDockFocused;
    private float lastDockTop;
    private boolean moveMode;
    // The hero: continue watching / featured items of the focused dock app.
    private FrameLayout featuredBox;
    private View featuredArt;
    private ImageView featuredImage;
    private android.view.TextureView featuredVideo;
    private View featuredInfo;
    private TextView featuredApp, featuredTitleView, featuredMeta, featuredDesc, featuredDots, featuredButton;
    private android.widget.ProgressBar featuredProgress;
    private List<FeaturedLoader.Item> featuredItems = Collections.emptyList();
    private int featuredIndex;
    private String featuredAppLabel = "";
    private android.media.MediaPlayer preview;
    private Runnable previewStart;
    private boolean showFeatured;
    private boolean featuredVisible;
    private String featuredPkg = "";
    private final ExecutorService queryIo = Executors.newSingleThreadExecutor();
    private final ExecutorService imageIo = Executors.newFixedThreadPool(3);
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
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputStateChanged(String inputId, int state) {
            updateInputsRow();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            AppAdapter.clearArt();
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            updateWeather();
            checkLook();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    /** The look (dark or light) in effect when this screen was built. */
    private boolean lightLoaded;
    private boolean locating;

    /**
     * With the automatic look, rebuilds the screen when the sun rises or sets. The position comes from the
     * weather's saved one; when there is none yet it is looked up once in the background.
     */
    private void checkLook() {
        if (!UiMode.AUTO.equals(UiMode.mode(this))) return;
        if (Weather.knownLocation(this) == null && !locating) {
            locating = true;
            final Context app = getApplicationContext();
            queryIo.execute(() -> {
                Weather.locate(app);
                locating = false;
            });
        }
        if (UiMode.light(this) != lightLoaded && openPopup == null && cardPrompt == null) recreate();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiMode.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a theme (see Config and ThemeManager).
        cfg = Config.load(this);
        loadedVersion = Prefs.version(this);
        lightLoaded = UiMode.light(this);
        COLUMNS = cfg.columns;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
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
        weatherView = findViewById(R.id.weather);
        btnWifi = findViewById(R.id.btnWifi);
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        showInputsRow = cfg.showInputsRow;
        showFeatured = cfg.showFeatured;
        inputsRow = findViewById(R.id.inputsRow);
        inputsGlow = findViewById(R.id.inputsGlow);
        featuredBox = findViewById(R.id.featured);
        featuredArt = findViewById(R.id.featuredArt);
        featuredImage = findViewById(R.id.featuredImage);
        featuredVideo = findViewById(R.id.featuredVideo);
        featuredInfo = findViewById(R.id.featuredInfo);
        featuredApp = findViewById(R.id.featuredApp);
        featuredTitleView = findViewById(R.id.featuredTitle);
        featuredMeta = findViewById(R.id.featuredMeta);
        featuredDesc = findViewById(R.id.featuredDesc);
        featuredDots = findViewById(R.id.featuredDots);
        featuredButton = findViewById(R.id.featuredButton);
        featuredProgress = findViewById(R.id.featuredProgress);
        featuredBox.setOnClickListener(v -> openFeatured());
        // Left and Right turn through the items, Up goes to the top bar, Down back to the dock.
        featuredBox.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    showFeaturedItem(featuredIndex - 1, true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    showFeaturedItem(featuredIndex + 1, true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return focusTopBar(lastDockFocused);
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focusTile(lastDockFocused);
                    return true;
                default:
                    return false;
            }
        });
        featuredBox.setOnFocusChangeListener((v, focused) -> scheduleRotation());
        FeaturedLoader.setDemo(this, getIntent().getStringExtra("demo_featured"));

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDeco = new DockDecoration(COLUMNS, density, cfg.dockColor, getColor(R.color.ink_1f));
        grid.addItemDecoration(dockDeco);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        adapter.setRevealed(!rowsHiddenBelowDock());
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
            int dockRow = itemH + (int) ((6 + 12) * density) + inputsExtraPx();   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            int want = Math.max(0, h - dockRow - gridRows - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            placeInputsRow();
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                placeInputsRow();
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(1100f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        topButtons = findViewById(R.id.topButtons);
        btnAirplay = findViewById(R.id.btnAirplay);
        for (TextView pill : new TextView[]{btnAirplay, (TextView) btnInputs, btnWifi, (TextView) btnSettings}) {
            initPill(pill);
        }
        btnAirplay.setOnClickListener(v -> {
            Intent i = AirPlayInfo.launchIntent(this);
            if (i != null) open(i);
        });
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> showSettingsMenu());
        btnSettings.setOnLongClickListener(v -> {
            if (!moduleCards) return false;   // off: a long press does nothing, as before the feature
            showModuleCards();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v == firstPill();
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnAirplay.setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) {
                hideGlass();
                // Focus on the top bar, the inputs or the shelf: the rows under the dock stay closed.
                if (newF != null) setGridRevealed(false);
            }
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;   // RGB_565 bands gradients and softens detail
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
        tilesReady = false;
        promptsPaused = false;
        if (Prefs.version(this) != loadedVersion) {
            // The settings menu changed something: rebuild the whole screen from the new values.
            recreate();
            return;
        }
        reloadApps(true);
        updateGreeting();
        updateWifiLabel();
        updateWeather();
        updateInputsButton();
        updateAirplay();
        checkLook();
        askTvListingsOnce();
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    // ---- Watch Next permission ---------------------------------------------------------------------

    private static final int REQ_TV_LISTINGS = 21;

    /**
     * Watch Next needs the READ_TV_LISTINGS permission, which Android asks for on screen. The first time the
     * home screen is up without it, ask once; the answer is remembered (Permissions in the SBL settings can
     * ask again).
     */
    private void askTvListingsOnce() {
        if (!showFeatured || checkSelfPermission(FeaturedLoader.READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED) return;
        final SharedPreferences flags = getSharedPreferences("sbl_flags", MODE_PRIVATE);
        if (flags.getBoolean("asked_tv_listings", false)) return;
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || !hasWindowFocus()) return;
            flags.edit().putBoolean("asked_tv_listings", true).apply();
            requestPermissions(new String[]{FeaturedLoader.READ_TV_LISTINGS}, REQ_TV_LISTINGS);
        }, 1500);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_TV_LISTINGS && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            hideFeatured(true);
            if (heroShown) loadFeatured(adapter.pkgAt(lastDockFocused));
        }
    }

    // ---- weather -----------------------------------------------------------------------------------

    private boolean weatherLoading;

    /** Shows the cached weather at once, and fetches a fresh reading in the background when it is old. */
    private void updateWeather() {
        if (!Prefs.getBool(this, Prefs.WEATHER, true)) {
            weatherView.setVisibility(View.GONE);
            return;
        }
        showWeather(Weather.cached(this));
        if (weatherLoading || (!Weather.stale(this) && Weather.cached(this) != null)) return;
        weatherLoading = true;
        final android.content.Context app = getApplicationContext();
        queryIo.execute(() -> {
            final Weather.Reading fresh = Weather.fetch(app);
            handler.post(() -> {
                weatherLoading = false;
                showWeather(fresh);
            });
        });
    }

    private void showWeather(Weather.Reading r) {
        if (r == null) {
            weatherView.setVisibility(View.GONE);
            return;
        }
        weatherView.setText(Weather.temperature(this, r.tempC) + "  " + getString(Weather.labelFor(r.code)));
        weatherView.setCompoundDrawablesRelativeWithIntrinsicBounds(Weather.iconFor(r.code, r.day), 0, 0, 0);
        weatherView.setVisibility(View.VISIBLE);
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            hideFeatured(true);
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        reloadTiles();
        if (animate) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        updateGlow();
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra("demo_featured")) {
            // Debug builds only (FeaturedLoader.setDemo ignores release builds): fake items to try the hero.
            FeaturedLoader.setDemo(this, intent.getStringExtra("demo_featured"));
            hideFeatured(true);
            if (heroShown) loadFeatured(adapter.pkgAt(lastDockFocused));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
        handler.removeCallbacks(rotate);
        unregisterReceiver(packageReceiver);
        handler.removeCallbacks(minuteTick);
        moveMode = false;
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    /**
     * Decoded pictures stay while the launcher is hidden, so coming back is instant. They go only when the
     * system is really short of memory (running low or critical, or our process is a background candidate);
     * TRIM_MEMORY_UI_HIDDEN, which fires on every trip into another app, is deliberately not one of them.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean pressure = (level >= TRIM_MEMORY_RUNNING_LOW && level < TRIM_MEMORY_UI_HIDDEN)
                || level >= TRIM_MEMORY_BACKGROUND;
        if (pressure) {
            AppAdapter.clearArt();
            FeaturedLoader.trim();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        queryIo.shutdownNow();
        imageIo.shutdownNow();
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        if (position < COLUMNS) lastDockFocused = position;
        setGridRevealed(position >= COLUMNS);
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
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * AppAdapter.FOCUS_SCALE + pad);
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
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero(true);
            followAmbient(adapter.ambientColorAt(position));
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        lastDockTop = dockTopInRoot;
        float y = heroY();
        boolean changed = !label.contentEquals(heroName.getText());

        // A favourite that may have Watch Next items shows its name only after a short wait: when the items
        // arrive the name never appears, instead of flashing up and being replaced.
        final String pkg = adapter.pkgAt(position);
        nameHeld = showFeatured && pkg != null && !pkg.isEmpty() && !pkg.equals(featuredPkg);
        handler.removeCallbacks(releaseName);
        if (nameHeld) handler.postDelayed(releaseName, NAME_HOLD_MS);

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(heroNameAlpha()).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                // The latest focused favourite, not the one this change started for: focus may have moved on.
                heroName.setText(adapter.labelAt(lastDockFocused));
                heroName.setY(heroY());
                heroName.setTranslationX(12 * density);
                // Decided now, not when the change started: the hero may have appeared in between.
                heroName.animate().alpha(heroNameAlpha()).translationX(0f).setDuration(200).start();
            }).start();
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
            heroName.animate().alpha(heroNameAlpha()).setDuration(120).start();
        }
        placeFeatured(y);
        loadFeatured(adapter.pkgAt(position));
    }

    /** The big app name is only shown while there is no hero over the same spot, and once the wait is over. */
    private float heroNameAlpha() {
        return featuredVisible || nameHeld ? 0f : 1f;
    }

    private static final long NAME_HOLD_MS = 350;
    /** True while the focused favourite's Watch Next items are still being looked for. */
    private boolean nameHeld;
    private final Runnable releaseName = this::releaseNameNow;

    /** Ends the wait: the app name fades in unless a hero is showing. */
    private void releaseNameNow() {
        handler.removeCallbacks(releaseName);
        if (!nameHeld) return;
        nameHeld = false;
        if (heroShown && !featuredVisible) heroName.animate().alpha(1f).setDuration(200).start();
    }

    /** Top of the focused dock app's name, above the dock band. */
    private float heroY() {
        heroName.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return lastDockTop - 14 * density - 26 * density - heroName.getMeasuredHeight();
    }

    /** Re-lays the app name and the hero after either changed. */
    private void relayoutTopShelf() {
        heroName.setY(heroY());
        placeFeatured(0f);
    }

    /** The hero fills the space between the top bar and the dock band. Returns false when that is too little. */
    private boolean placeFeatured(float unused) {
        if (!featuredVisible) return true;
        View leftBlock = findViewById(R.id.leftBlock);
        float top = offsetInRoot(leftBlock).bottom + 6 * density;
        float bottom = lastDockTop - 14 * density - 16 * density;
        int h = (int) (bottom - top);
        if (h < 150 * density) return false;
        ViewGroup.LayoutParams lp = featuredBox.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            featuredBox.setLayoutParams(lp);
        }
        ViewGroup.LayoutParams art = featuredArt.getLayoutParams();
        int artW = Math.min((int) (h * 2.1f), (int) (root.getWidth() * 0.55f));
        if (art.width != artW) {
            art.width = artW;
            featuredArt.setLayoutParams(art);
        }
        featuredBox.setY(top);
        // The details take the room the picture leaves, so the text never runs over it.
        ViewGroup.LayoutParams info = featuredInfo.getLayoutParams();
        int infoW = Math.max((int) (300 * density), root.getWidth() - (int) (68 * density) - artW);
        if (info.width != infoW) {
            info.width = infoW;
            featuredInfo.setLayoutParams(info);
        }
        return true;
    }

    // ---- continue watching / featured items ------------------------------------------------------

    /**
     * The hero: reads the focused favourite's Watch Next and featured programs and shows them one at a time
     * between the top bar and the dock. It turns by itself while the dock has focus; Up takes control.
     */
    private void loadFeatured(String pkg) {
        if (!showFeatured || pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(featuredPkg)) return;
        featuredPkg = pkg;
        final String appLabel = adapter.labelAt(lastDockFocused);
        hideFeatured(false);
        queryIo.execute(() -> {
            List<FeaturedLoader.Item> items = FeaturedLoader.load(getApplicationContext(), pkg);
            handler.post(() -> {
                if (!pkg.equals(featuredPkg) || !heroShown) return;
                if (items.isEmpty()) {
                    releaseNameNow();   // nothing to show: the app name takes the spot at once
                    return;
                }
                nameHeld = false;
                handler.removeCallbacks(releaseName);
                featuredItems = items;
                featuredIndex = 0;
                featuredAppLabel = appLabel;
                featuredVisible = true;
                featuredBox.setVisibility(View.VISIBLE);
                if (!placeFeatured(0f)) {
                    featuredVisible = false;
                    featuredBox.setVisibility(View.GONE);
                    return;
                }
                heroName.animate().cancel();
                heroName.animate().alpha(0f).setDuration(160).start();
                showFeaturedItem(0, false);
                featuredBox.animate().alpha(1f).setDuration(260).start();
                scheduleRotation();
            });
        });
    }

    /** Hides the hero; {@code forget} also drops which app it was showing, so it is loaded again next time. */
    private void hideFeatured(boolean forget) {
        if (forget) featuredPkg = "";
        stopPreview();
        handler.removeCallbacks(rotate);
        if (!featuredVisible) return;
        featuredVisible = false;
        featuredBox.animate().cancel();
        featuredBox.setAlpha(0f);
        featuredBox.setVisibility(View.GONE);
        if (heroShown) {
            // Cancelling may have cut off a name change that was still fading out: write the focused app's name now.
            heroName.animate().cancel();
            heroName.setText(adapter.labelAt(lastDockFocused));
            heroName.setTranslationX(0f);
            heroName.animate().alpha(heroNameAlpha()).setDuration(200).start();
            heroName.setY(heroY());
        }
    }

    /** Fills the hero with one item: its picture fades in, the details cross-fade, and a preview clip follows. */
    private void showFeaturedItem(int index, boolean animate) {
        if (featuredItems.isEmpty()) return;
        featuredIndex = (index % featuredItems.size() + featuredItems.size()) % featuredItems.size();
        final FeaturedLoader.Item item = featuredItems.get(featuredIndex);
        stopPreview();
        final Runnable fill = () -> {
            featuredApp.setText(featuredAppLabel + " \u00B7 " + getString(heroKind(item)));
            featuredTitleView.setText(item.title);
            featuredButton.setText(item.isContinue() ? R.string.continue_action
                    : FeaturedLoader.isMusic(item) ? R.string.listen_action
                    : item.type == 11 ? R.string.play_action   // TYPE_GAME, not in the public SDK
                    : R.string.watch_action);
            StringBuilder meta = new StringBuilder(item.subtitle);
            if (item.minutesLeft > 0) {
                if (meta.length() > 0) meta.append("  \u00B7  ");
                meta.append(getString(R.string.minutes_left, item.minutesLeft));
            }
            featuredMeta.setText(meta);
            featuredMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);
            featuredDesc.setText(item.description);
            featuredDesc.setVisibility(item.description.isEmpty() ? View.GONE : View.VISIBLE);
            featuredProgress.setVisibility(item.progress >= 0 ? View.VISIBLE : View.GONE);
            featuredProgress.setProgress(Math.max(0, item.progress));
            featuredDots.setText(dots(featuredIndex, featuredItems.size()));
            featuredInfo.animate().alpha(1f).setDuration(200).start();
        };
        if (animate) featuredInfo.animate().alpha(0f).setDuration(110).withEndAction(fill).start();
        else fill.run();

        // The picture: from memory at once, otherwise loaded in the background and faded in.
        Bitmap cached = FeaturedLoader.cached(item.image);
        featuredImage.animate().cancel();
        if (cached != null) {
            featuredImage.setImageBitmap(cached);
            featuredImage.setAlpha(animate ? 0f : 1f);
            featuredImage.animate().alpha(1f).setDuration(260).start();
        } else {
            featuredImage.setImageDrawable(null);
            if (item.image != null) {
                final int w = Math.max(1, featuredArt.getLayoutParams().width), h = Math.max(1, featuredBox.getLayoutParams().height);
                imageIo.execute(() -> {
                    final Bitmap bmp = FeaturedLoader.loadImage(getApplicationContext(), item.image, w, h);
                    if (bmp == null) return;
                    handler.post(() -> {
                        if (!featuredVisible || featuredItems.get(featuredIndex) != item) return;
                        featuredImage.setImageBitmap(bmp);
                        featuredImage.setAlpha(0f);
                        featuredImage.animate().alpha(1f).setDuration(320).start();
                    });
                });
            }
        }
        if (item.video != null && Prefs.featuredVideo(this)) {
            // Give the picture a moment first: a clip that starts at once looks like a glitch.
            final Runnable start = () -> startPreview(item);
            previewStart = start;
            handler.postDelayed(start, 1500);
        }
    }

    /** The small line above the title: what kind of Watch Next entry it is, or "Featured". */
    private static int heroKind(FeaturedLoader.Item item) {
        if (!item.watchNext) return R.string.hero_featured;
        switch (item.watchNextType) {
            case 1:   // WATCH_NEXT_TYPE_NEXT
                return R.string.hero_next;
            case 2:   // WATCH_NEXT_TYPE_NEW
                return R.string.hero_new;
            case 3:   // WATCH_NEXT_TYPE_WATCHLIST
                return R.string.hero_watchlist;
            default:
                return R.string.hero_continue;
        }
    }

    private CharSequence dots(int active, int count) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = 0; i < count; i++) {
            int start = sb.length();
            sb.append(i == 0 ? "\u25CF" : "  \u25CF");
            sb.setSpan(new android.text.style.ForegroundColorSpan(i == active ? getColor(R.color.ink_ff) : getColor(R.color.ink_55)),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (featuredVisible && !featuredBox.hasFocus() && hasWindowFocus() && featuredItems.size() > 1) {
                showFeaturedItem(featuredIndex + 1, true);
            }
            scheduleRotation();
        }
    };

    private void scheduleRotation() {
        handler.removeCallbacks(rotate);
        int seconds = Prefs.getInt(this, Prefs.FEATURED_INTERVAL, 6);
        if (seconds > 0 && featuredVisible && featuredItems.size() > 1) handler.postDelayed(rotate, seconds * 1000L);
    }

    // ---- muted preview clip ----------------------------------------------------------------------

    /** Debug builds only: this TV keeps no app logs, so the preview reports what it does on screen. */
    private void debugNote(String text) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }

    private void startPreview(final FeaturedLoader.Item item) {
        if (!featuredVisible || featuredItems.get(featuredIndex) != item || preview != null) return;
        final android.graphics.SurfaceTexture st = featuredVideo.getSurfaceTexture();
        if (st == null) {
            debugNote("video: no surface yet");
            // The texture is not ready yet: try again once it is.
            featuredVideo.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture s, int w, int h) {
                    featuredVideo.setSurfaceTextureListener(null);
                    startPreview(item);
                }

                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture s) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture s) {
                }
            });
            return;
        }
        try {
            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            preview = mp;
            mp.setSurface(new android.view.Surface(st));
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            mp.setDataSource(this, item.video);
            mp.setOnVideoSizeChangedListener((p, vw, vh) -> cropVideo(vw, vh));
            mp.setOnPreparedListener(p -> {
                if (preview != p) return;
                debugNote("video: playing");
                p.start();
                featuredVideo.animate().alpha(1f).setDuration(500).start();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                debugNote("video error " + what + "/" + extra);
                if (preview == p) stopPreview();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception | OutOfMemoryError e) {
            debugNote("video: " + e);
            stopPreview();
        }
    }

    /** Fills the clip's area the way centerCrop fills the picture's. */
    private void cropVideo(int vw, int vh) {
        float w = featuredVideo.getWidth(), h = featuredVideo.getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float scale = Math.max(w / vw, h / vh);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f);
        featuredVideo.setTransform(m);
    }

    private void stopPreview() {
        if (previewStart != null) {
            handler.removeCallbacks(previewStart);
            previewStart = null;
        }
        if (featuredVideo != null) {
            featuredVideo.animate().cancel();
            // Not fully transparent: a TextureView that is never drawn never gets its surface.
            featuredVideo.setAlpha(0.01f);
        }
        final android.media.MediaPlayer mp = preview;
        preview = null;
        if (mp != null) {
            try {
                mp.setOnPreparedListener(null);
                mp.setOnErrorListener(null);
                mp.stop();
            } catch (Exception ignored) {
            }
            mp.release();
        }
    }

    private boolean focusFeatured() {
        if (!featuredVisible || featuredItems.isEmpty()) return false;
        return featuredBox.requestFocus();
    }

    /** Opens the shown item in its app (always pinned to that app), or the app itself when that fails. */
    private void openFeatured() {
        if (!featuredVisible || featuredItems.isEmpty()) return;
        FeaturedLoader.Item item = featuredItems.get(featuredIndex);
        Intent i = FeaturedLoader.intentFor(item);
        try {
            if (i == null) throw new ActivityNotFoundException();
            startActivity(i);
        } catch (Exception e) {
            Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);
            if (app == null) {
                Toast.makeText(this, getString(R.string.cant_open, item.title), Toast.LENGTH_SHORT).show();
                return;
            }
            open(app);
        }
        UsageTracker.record(this, item.pkg);
    }

    /**
     * Focus the top-bar pill closest to a dock tile. Done by hand: the inputs row sits at a translated
     * position, and Android's focus search would otherwise pick one of its pills for an Up key.
     */
    private boolean focusTopBar(int dockPosition) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(dockPosition);
        View card = vh != null ? vh.itemView.findViewById(R.id.card) : null;
        float cx = card != null ? offsetInRoot(card).exactCenterX() : root.getWidth() / 2f;
        View best = null;
        float bestDist = Float.MAX_VALUE;
        for (int id : new int[]{R.id.btnAirplay, R.id.btnInputs, R.id.btnWifi, R.id.btnSettings}) {
            View pill = findViewById(id);
            if (pill == null || pill.getVisibility() != View.VISIBLE || !pill.isFocusable()) continue;
            float d = Math.abs(offsetInRoot(pill).exactCenterX() - cx);
            if (d < bestDist) {
                bestDist = d;
                best = pill;
            }
        }
        return best != null && best.requestFocus();
    }

    private Rect offsetInRoot(View v) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        root.offsetDescendantRectToMyCoords(v, r);
        return r;
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{
                getResources().getInteger(R.integer.ambient_a1) << 24 | c,
                getResources().getInteger(R.integer.ambient_a2) << 24 | c, c});
    }

    private void hideHero() {
        hideHero(false);
    }

    /** Hides the app name and the hero; {@code keepAmbient} leaves the coloured glow, as in the grid under the dock. */
    private void hideHero(boolean keepAmbient) {
        nameHeld = false;
        handler.removeCallbacks(releaseName);
        hideFeatured(true);
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        if (!keepAmbient) ambient.animate().alpha(0f).setDuration(300).start();
    }

    /** The glow follows the focused tile's colour in the grid just like it follows the dock apps. */
    private void followAmbient(final int color) {
        if (color != ambientColor || ambient.getAlpha() < 0.99f) {
            ambient.animate().alpha(0.35f).setDuration(160).withEndAction(() -> {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(400).start();
            }).start();
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        if (moveMode) return moveTile(position, keyCode);
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return position % COLUMNS == 0;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return position % COLUMNS == COLUMNS - 1 || position == count - 1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return focusFeatured() || focusTopBar(position);   // dock row: the shelf if there is one, else the top bar
            target = position - COLUMNS;
        } else {
            if (position < COLUMNS && inputPills > 0 && inputsRow.getVisibility() == View.VISIBLE) {
                // The connected inputs hang right under the dock; going down always lands on the first one.
                View pill = inputsPills.getChildAt(0);
                if (pill != null) {
                    pill.requestFocus();
                    return true;
                }
            }
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    // ---- moving and managing tiles -----------------------------------------------------------------

    /** Arrow keys while a tile is being moved: it takes the neighbour's place, also across the dock edge (into or out of favourites). */
    private boolean moveTile(int position, int keyCode) {
        int target = position;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) target = position % COLUMNS == 0 ? position : position - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) target = position % COLUMNS == COLUMNS - 1 ? position : position + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) target = position - COLUMNS;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) target = position + COLUMNS;
        int now = adapter.move(position, target);
        if (now != position) {
            loadedVersion = Prefs.version(this);   // already applied here, no rebuild needed on return
            lastFocused = now;
            lastFocusedKey = adapter.keyAt(now);
            grid.post(() -> focusTile(now));
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (moveMode) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || k == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) moveMode = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** Hold OK on a tile: move it, open App info, hide it, or pin it to / unpin it from the dock. */
    @Override
    public void onTileLongPress(int position) {
        final String pkg = adapter.pkgAt(position);
        final String label = adapter.labelAt(position);
        final boolean fav = adapter.isFavourite(position);
        final String[] items = {
                getString(R.string.tile_move),
                getString(R.string.tile_app_info),
                getString(R.string.tile_hide),
                getString(fav ? R.string.tile_unpin : R.string.tile_pin)};
        TvDialog dialog = new TvDialog(this).title(label);
        dialog.item(items[0], () -> {
            moveMode = true;
            Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show();
        });
        dialog.item(items[1], () -> adapter.openAppInfo(pkg));
        dialog.item(items[2], () -> {
            List<String> hidden = new ArrayList<>(cfg.hiddenApps);
            if (!hidden.contains(pkg)) hidden.add(pkg);
            Prefs.putList(this, Prefs.HIDDEN_APPS, hidden);
            Toast.makeText(this, getString(R.string.tile_hidden, label), Toast.LENGTH_LONG).show();
            recreate();
        });
        dialog.item(items[3], () -> {
            List<String> dock = new ArrayList<>(cfg.dockApps);
            if (fav) {
                dock.remove(pkg);
            } else if (adapter.favouriteCount() >= COLUMNS) {
                Toast.makeText(this, getString(R.string.dock_full, COLUMNS), Toast.LENGTH_LONG).show();
                return;
            } else {
                dock.add(pkg);
            }
            Prefs.putList(this, Prefs.DOCK_APPS, dock);
            recreate();
        });
        dialog.show();
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
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
    }

    // ---- top-bar pills: icon only, the text unfolds on focus ---------------------------------------

    private void initPill(TextView pill) {
        pillText.put(pill, pill.getText());
        applyPill(pill, alwaysOpen(pill));
        if (alwaysOpen(pill)) return;
        pill.setOnFocusChangeListener((v, focused) -> {
            android.transition.TransitionManager.beginDelayedTransition(topButtons,
                    new android.transition.AutoTransition().setDuration(170));
            applyPill(pill, focused);
        });
    }

    /** The AirPlay pill always shows its text: the TV's name is the point of it. */
    private boolean alwaysOpen(TextView pill) {
        return pill == btnAirplay;
    }

    /** The pill at the left end of the top bar, the first one showing. */
    private View firstPill() {
        for (View p : new View[]{btnAirplay, btnInputs, btnWifi, findViewById(R.id.btnSettings)}) {
            if (p != null && p.getVisibility() == View.VISIBLE && p.isFocusable()) return p;
        }
        return null;
    }

    /** Shows the TV's AirPlay name when the AirPlay receiver app is installed, and follows renames made in it. */
    private void updateAirplay() {
        final boolean app = AirPlayInfo.installed(this);
        // With the receiver app there is always something to show; without it, only a name that was really heard.
        String known = app ? AirPlayInfo.cachedName(this) : AirPlayInfo.heardName(this);
        setAirplayPill(known, app);
        AirPlayInfo.discoverName(this, name -> {
            if (isDestroyed()) return;
            boolean installed = AirPlayInfo.installed(this);
            if (name != null) setAirplayPill(name, installed);
            else if (!installed) setAirplayPill(null, false);
        });
    }

    /** The pill opens the AirPlay app when there is one; otherwise it only shows the name and takes no focus. */
    private void setAirplayPill(String name, boolean opensApp) {
        if (name == null) {
            btnAirplay.setVisibility(View.GONE);
            return;
        }
        setPillText(btnAirplay, name);
        btnAirplay.setFocusable(opensApp);
        btnAirplay.setClickable(opensApp);
        btnAirplay.setVisibility(View.VISIBLE);
    }

    private void applyPill(TextView pill, boolean expanded) {
        pill.setText(expanded ? pillText.get(pill) : "");
        pill.setCompoundDrawablePadding(expanded ? (int) (9 * density) : 0);
        if (expanded) pill.setPaddingRelative((int) (16 * density), 0, (int) (20 * density), 0);
        else pill.setPaddingRelative((int) (10 * density), 0, (int) (10 * density), 0);
    }

    /** Sets the full text of a pill; what shows depends on whether it has focus. */
    private void setPillText(TextView pill, CharSequence text) {
        pillText.put(pill, text);
        pill.setText(pill.isFocused() || alwaysOpen(pill) ? text : "");
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            setPillText(btnWifi, label);
        } else {
            setPillText(btnWifi, text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput()) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
        updateInputsRow();
    }

    // ---- connected inputs row ----------------------------------------------------------------------

    private static final class InputItem {
        final String id;
        final String label;
        final int icon;

        InputItem(String id, String label, int icon) {
            this.id = id;
            this.label = label;
            this.icon = icon;
        }
    }

    /** Extra room under the dock row for the inputs row, 0 when it is off or has nothing to show. */
    private int inputsExtraPx() {
        return showInputsRow && inputPills > 0 ? (int) (44 * density) : 0;
    }

    /** HDMI and AV inputs with something connected, named the way the TV names them (custom label or the device's own name). */
    private List<InputItem> connectedInputs() {
        List<InputItem> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> inputs = new ArrayList<>();
        for (TvInputInfo info : tim.getTvInputList()) if (info.isPassthroughInput()) inputs.add(info);
        // Some TVs (Sony) report every HDMI port as connected: then only ports with a named device count.
        int hdmiPorts = 0, hdmiConnected = 0;
        for (TvInputInfo info : inputs) {
            if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
            hdmiPorts++;
            if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
        }
        boolean stateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
        Collections.sort(inputs, (a, b) -> {
            boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
            if (ha != hb) return ha ? -1 : 1;
            return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
        });
        for (TvInputInfo info : inputs) {
            boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
            String custom = null;
            CharSequence cl = info.loadCustomLabel(this);
            if (cl != null && cl.toString().trim().length() > 0) custom = cl.toString().trim();
            String device = null;
            int state = tim.getInputState(info.getId());
            boolean connected = state == TvInputManager.INPUT_STATE_CONNECTED;
            if (hdmi && stateBogus) connected = custom != null || device != null;
            if (!connected) continue;
            CharSequence l = info.loadLabel(this);
            String label = custom != null ? custom : device != null ? device : l == null ? info.getId() : l.toString();
            if (hdmi && custom == null && device == null && !label.toUpperCase(Locale.ROOT).contains("HDMI")) {
                label = "HDMI " + label;
            }
            out.add(new InputItem(info.getId(), label, hdmi ? R.drawable.ic_input : R.drawable.ic_av));
        }
        return out;
    }

    /** Rebuilds the pills of the connected inputs; called when inputs come, go or change state. */
    private void updateInputsRow() {
        if (inputsRow == null) return;
        List<InputItem> inputs = showInputsRow ? connectedInputs() : Collections.<InputItem>emptyList();
        int before = inputPills;
        boolean hadFocus = inputsRow.hasFocus();
        inputsRow.removeAllViews();
        inputPills = inputs.size();
        if (inputs.isEmpty()) {
            inputsRow.setVisibility(View.GONE);
        } else {
            TextView label = new TextView(this);
            label.setText(R.string.inputs_label);
            label.setTextColor(getColor(R.color.ink_b3));
            label.setTextSize(13f);
            label.setAllCaps(true);
            label.setTypeface(getResources().getFont(R.font.outfit));
            label.setLetterSpacing(0.16f);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.setMarginStart((int) (6 * density));
            labelLp.bottomMargin = (int) (8 * density);
            inputsRow.addView(label, labelLp);
            inputsPills = new LinearLayout(this);
            inputsPills.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < inputs.size(); i++) inputsPills.addView(inputPill(inputs.get(i), i, inputs.size()));
            inputsRow.addView(inputsPills, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            inputsRow.setVisibility(View.VISIBLE);
        }
        if (before != inputPills || (before == 0) != inputs.isEmpty()) {
            dockDeco.setExtraBottom(inputsExtraPx());
            grid.invalidateItemDecorations();
            grid.requestLayout();
        }
        if (hadFocus && inputs.isEmpty()) focusTile(lastDockFocused);
        grid.post(this::placeInputsRow);
    }

    private View inputPill(InputItem item, int index, int total) {
        TextView pill = new TextView(this, null, 0, R.style.InputPill);
        pill.setText(item.label);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(item.icon, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (36 * density));
        lp.setMarginStart(index == 0 ? 0 : (int) (10 * density));
        pill.setLayoutParams(lp);
        pill.setOnClickListener(v -> openInput(item.id));
        pill.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == 0;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == total - 1;
                case KeyEvent.KEYCODE_DPAD_UP:
                    focusTile(lastDockFocused);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (adapter.getItemCount() > COLUMNS) focusTile(COLUMNS);
                    return true;
                default:
                    return false;
            }
        });
        return pill;
    }

    /** Hangs the inputs row under the dock band, following the dock as the grid scrolls. */
    private void placeInputsRow() {
        if (inputsRow == null || inputsRow.getVisibility() != View.VISIBLE) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
        if (vh == null) {
            inputsRow.setAlpha(0f);
            return;
        }
        View item = vh.itemView;
        View card = item.findViewById(R.id.card);
        float x = grid.getLeft() + item.getLeft() + card.getLeft() - 8 * density;
        float y = grid.getTop() + item.getTop() + card.getBottom() + (14 + 10) * density;
        inputsRow.setX(x);
        inputsRow.setY(y);
        boolean inside = y + inputsRow.getHeight() > 0 && y < root.getHeight();
        inputsRow.setAlpha(inside ? 1f : 0f);
    }

    // ---- hidden rows and the bottom glow -------------------------------------------------------------

    /** With no grid rows under the dock, the rest of the apps stay hidden until focus goes down to them. */
    private boolean rowsHiddenBelowDock() {
        return cfg.rowsBelowDock == 0;
    }

    /**
     * Opens or closes the grid under the dock. Closed: only a thin glow along the bottom edge says there
     * is more; going down (from the dock or the inputs) fades the rows in.
     */
    private void setGridRevealed(boolean reveal) {
        if (!rowsHiddenBelowDock()) reveal = true;
        if (adapter.revealed() == reveal) return;
        adapter.setRevealed(reveal);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(child);
            int pos = vh.getBindingAdapterPosition();
            if (pos < COLUMNS) continue;
            child.animate().cancel();
            if (reveal) {
                child.setAlpha(0f);
                child.setTranslationY(16 * density);
                child.animate().alpha(1f).translationY(0f).setDuration(240).start();
            } else {
                child.animate().alpha(0f).setDuration(150).start();
            }
        }
        updateGlow();
    }

    /** The glow shows while there are apps below that are not on screen. */
    private void updateGlow() {
        boolean show = rowsHiddenBelowDock() && !adapter.revealed() && adapter.getItemCount() > COLUMNS;
        if (show) {
            inputsGlow.setVisibility(View.VISIBLE);
            inputsGlow.animate().alpha(1f).setDuration(260).start();
        } else {
            inputsGlow.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                if (adapter.revealed() || !rowsHiddenBelowDock()) inputsGlow.setVisibility(View.GONE);
            }).start();
        }
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                // The name the TV gives the port (what the owner or the connected device called it) goes next to the type.
                CharSequence custom = info.loadCustomLabel(this);
                if (custom != null && custom.toString().trim().length() > 0
                        && !custom.toString().trim().equalsIgnoreCase(label)) {
                    label = label + "  \u00B7  " + custom.toString().trim();
                }
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        dropIn(popup, panel, anchor, Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    /** Dropdown under the gear pill: the launcher's own settings, then the TV's. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (230 * density));
        addInputRow(rows, popup, R.drawable.ic_tune, getString(R.string.launcher_settings), -1,
                () -> startActivity(new Intent(this, SettingsActivity.class)));
        addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.system_settings), -1,
                () -> open(new Intent(Settings.ACTION_SETTINGS)));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (230 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.ink_1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- module cards ----------------------------------------------------------------------------

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
