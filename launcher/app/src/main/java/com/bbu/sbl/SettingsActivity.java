package com.bbu.sbl;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The launcher's own settings: a glass panel that slides in over the dimmed home screen. The first page lists
 * the categories; each one opens its settings as a page inside the same panel, and choices open as further
 * pages. Left or Back goes up one level. Everything is saved in {@link Prefs}; the home screen notices the
 * change when it comes back and rebuilds itself.
 */
public class SettingsActivity extends Activity {

    /** One page of the panel: how to fill it, and which row had focus when a deeper page was opened. */
    private static final class Frame {
        final String crumb, title, desc;
        final Runnable build;
        int focus;

        Frame(String crumb, String title, String desc, Runnable build) {
            this.crumb = crumb;
            this.title = title;
            this.desc = desc;
            this.build = build;
        }
    }

    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private LinearLayout list;
    private TextView crumbView, titleView, footerView;
    private View panel, scrim;
    private android.widget.ScrollView scroll;
    private Config cfg;
    private boolean closing;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiMode.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        list = findViewById(R.id.settingsList);
        crumbView = findViewById(R.id.panelCrumb);
        titleView = findViewById(R.id.paneTitle);
        footerView = findViewById(R.id.paneFooter);
        scroll = findViewById(R.id.settingsScroll);
        panel = findViewById(R.id.panel);
        scrim = findViewById(R.id.scrim);
        stack.push(new Frame("", getString(R.string.launcher_settings), "", this::rootPage));
        panel.post(() -> {
            panel.setPivotX(panel.getWidth());
            panel.setPivotY(0f);
            panel.setScaleY(0.92f);
            panel.setTranslationY(-12 * getResources().getDisplayMetrics().density);
            panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            scrim.animate().alpha(1f).setDuration(200).start();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onBackPressed() {
        up();
    }

    /** One level up, or out of the panel from the first page. */
    private void up() {
        if (stack.size() > 1) {
            stack.pop();
            render();
        } else {
            close();
        }
    }

    private void close() {
        if (closing) return;
        closing = true;
        panel.animate().alpha(0f).scaleY(0.92f).setDuration(150).start();
        scrim.animate().alpha(0f).setDuration(150).withEndAction(this::finish).start();
    }

    // ---- pages -----------------------------------------------------------------------------------

    private int focusedIndex() {
        View f = list.getFocusedChild();
        return f == null ? 0 : Math.max(0, list.indexOfChild(f));
    }

    /** Opens a deeper page; the current page remembers its focused row. */
    private void push(String title, String desc, Runnable build) {
        Frame current = stack.peek();
        if (current != null) current.focus = focusedIndex();
        stack.push(new Frame(current == null ? "" : current.title, title, desc, build));
        render();
    }

    /** Rebuilds the current page from the current settings, keeping focus where it was. */
    private void refresh() {
        Frame f = stack.peek();
        if (f != null && list.getChildCount() > 0) f.focus = focusedIndex();
        cfg = Config.load(this);
        render();
    }

    private void render() {
        Frame f = stack.peek();
        if (f == null) return;
        if (cfg == null) cfg = Config.load(this);
        list.removeAllViews();
        crumbView.setText(f.crumb);
        crumbView.setVisibility(f.crumb.isEmpty() ? View.GONE : View.VISIBLE);
        titleView.setText(f.title);
        footerView.setText(f.desc);
        f.build.run();
        capHeight();
        wireEdges();
        View target = null;
        for (int i = Math.min(f.focus, list.getChildCount() - 1); i >= 0 && target == null; i--) {
            if (list.getChildAt(i).isFocusable()) target = list.getChildAt(i);
        }
        if (target == null) target = firstFocusable();
        if (target != null) target.requestFocus();
    }

    /** The list is as tall as its rows, up to a limit; beyond that it scrolls. */
    private void capHeight() {
        float d = getResources().getDisplayMetrics().density;
        int max = (int) (6.5f * 44 * d);
        list.measure(View.MeasureSpec.makeMeasureSpec((int) (440 * d), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = Math.min(max, list.getMeasuredHeight());
        scroll.setLayoutParams(lp);
    }

    private View firstFocusable() {
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) return list.getChildAt(i);
        return null;
    }

    /** Left goes up a level; Right does nothing; Up and Down stop at the ends instead of wandering off. */
    private void wireEdges() {
        View first = null, last = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            if (!list.getChildAt(i).isFocusable()) continue;
            if (first == null) first = list.getChildAt(i);
            last = list.getChildAt(i);
        }
        final View f = first, l = last;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (!row.isFocusable()) continue;
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    up();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return v == f;
                return keyCode == KeyEvent.KEYCODE_DPAD_DOWN && v == l;
            });
        }
    }

    // ---- the categories and their settings -------------------------------------------------------

    private void rootPage() {
        category(R.string.sec_home, R.string.desc_home, R.drawable.ic_cat_home, this::homePane);
        category(R.string.sec_apps, R.string.desc_apps, R.drawable.ic_cat_apps, this::appsPane);
        // Themes are switched off for now: themePane() and ThemeManager stay in the code but nothing opens them.
        category(R.string.sec_permissions, R.string.desc_permissions, R.drawable.ic_cat_lock, this::permissionsPane);
        category(R.string.sec_other, R.string.desc_system, R.drawable.ic_cat_info, this::systemPane);
    }

    private void category(int title, int desc, int icon, Runnable build) {
        View r = row(getString(title), getString(desc), "\u203A", () -> push(getString(title), getString(desc), build));
        ImageView iv = r.findViewById(R.id.sIcon);
        iv.setImageResource(icon);
        iv.setVisibility(View.VISIBLE);
    }

    private void homePane() {
        row(R.string.language_setting, R.string.language_sub, languageLabel(), this::chooseLanguage);
        row(R.string.appearance_setting, R.string.appearance_sub, modeLabel(UiMode.mode(this)), this::chooseAppearance);
        row(R.string.columns, R.string.columns_sub, String.valueOf(cfg.columns), () -> chooseNumber(
                getString(R.string.columns), Prefs.COLUMNS, 4, 8, cfg.columns));
        toggle(R.string.inputs_row_setting, R.string.inputs_row_sub, Prefs.INPUTS_ROW, cfg.showInputsRow);
        toggle(R.string.featured_setting, R.string.featured_sub, Prefs.FEATURED, cfg.showFeatured);
        if (cfg.showFeatured) {
            row(R.string.featured_interval_setting, R.string.featured_interval_sub,
                    intervalLabel(Prefs.getInt(this, Prefs.FEATURED_INTERVAL, 6)), this::chooseInterval);
        }
        toggle(R.string.weather_setting, R.string.weather_sub, Prefs.WEATHER, Prefs.getBool(this, Prefs.WEATHER, false));
        if (Prefs.getBool(this, Prefs.WEATHER, false)) {
            row(R.string.weather_unit_setting, R.string.weather_unit_sub,
                    getString("f".equals(Weather.unit(this)) ? R.string.unit_f : R.string.unit_c), this::chooseUnit);
        }
    }

    private void appsPane() {
        row(R.string.sort_apps, R.string.sort_apps_sub, sortLabel(cfg.sortMode), this::chooseSort);
        row(R.string.hidden_apps, R.string.hidden_apps_sub, String.valueOf(cfg.hiddenApps.size()), this::chooseHidden);
        row(R.string.clear_usage, R.string.clear_usage_sub, "", () -> {
            UsageTracker.clear(this);
            Toast.makeText(this, R.string.usage_cleared, Toast.LENGTH_SHORT).show();
        });
    }

    private void themePane() {
        final String active = Prefs.getString(this, Prefs.THEME, null);
        String on = getString(R.string.theme_active);
        row(getString(R.string.theme_default), getString(R.string.theme_default_sub), active == null ? on : "", () -> {
            ThemeManager.reset(this);
            Toast.makeText(this, R.string.theme_applied, Toast.LENGTH_SHORT).show();
            finish();
        });
        for (final ThemeManager.Theme theme : ThemeManager.installed(this)) {
            row(theme.name, getString(R.string.theme_saved_sub), theme.name.equals(active) ? on : "", () -> themeActions(theme));
        }
        row(R.string.theme_install_file, R.string.theme_install_file_sub, "", this::installFromFiles);
        row(R.string.theme_install_web, R.string.theme_install_web_sub, "", this::installFromWeb);
    }

    private void permissionsPane() {
        final boolean files = filesAccess();
        row(R.string.perm_files, R.string.perm_files_sub, status(files), () -> askPermission(files,
                R.string.perm_files, R.string.perm_files_body, this::openFilesAccess,
                "adb shell appops set " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow"));
        final boolean tv = FeaturedLoader.probe(this) == FeaturedLoader.OK;
        row(R.string.perm_tv, R.string.perm_tv_sub, status(tv), () -> askPermission(tv,
                R.string.perm_tv, R.string.perm_tv_body, this::requestTvListings,
                "adb shell pm grant " + getPackageName() + " android.permission.READ_TV_LISTINGS"));
    }

    private void systemPane() {
        row(R.string.backup_now, R.string.backup_now_sub, "", this::backupNow);
        row(R.string.backup_restore, R.string.backup_restore_sub, "", this::chooseBackup);
        row(R.string.reset_all, R.string.reset_all_sub, "", () -> confirm(R.string.reset_all, R.string.reset_all_body, () -> {
            Prefs.resetAll(this);
            Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
            refresh();
        }));
        row(getString(R.string.about), getString(R.string.about_sub), version(), null);
    }

    // ---- backup ----------------------------------------------------------------------------------

    private void backupNow() {
        String err = null;
        List<File> written = null;
        try {
            written = Backup.create(this);
            if (written.isEmpty()) err = "no writable folder";
        } catch (Exception e) {
            err = String.valueOf(e.getMessage());
        }
        if (err != null) {
            message(getString(R.string.backup_now), getString(R.string.backup_failed, err));
            return;
        }
        StringBuilder where = new StringBuilder();
        for (File f : written) where.append(f.getPath()).append('\n');
        message(getString(R.string.backup_now), getString(R.string.backup_saved, where.toString().trim()));
    }

    private void chooseBackup() {
        final List<File> found = Backup.find(this);
        if (found.isEmpty()) {
            message(getString(R.string.backup_restore), getString(R.string.backup_none));
            return;
        }
        final java.text.DateFormat fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        push(getString(R.string.backup_restore), getString(R.string.backup_restore_sub), () -> {
            for (final File f : found) {
                String parent = f.getParentFile() == null ? "" : f.getParentFile().getPath();
                row(fmt.format(new java.util.Date(Backup.created(f))), parent, "", () -> confirm(R.string.backup_restore,
                        R.string.backup_restore_confirm, () -> {
                            String err = Backup.restore(this, f);
                            Toast.makeText(this, err == null ? getString(R.string.backup_restored)
                                    : getString(R.string.backup_restore_failed, err), Toast.LENGTH_LONG).show();
                            if (err == null) {
                                stack.pop();
                                refresh();
                            }
                        }));
            }
        });
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private String status(boolean ok) {
        return getString(ok ? R.string.granted : R.string.not_granted);
    }

    // ---- rows ------------------------------------------------------------------------------------

    private void row(int title, int sub, String value, Runnable action) {
        row(getString(title), getString(sub), value, action);
    }

    /** A row with a title, a line of explanation and a value on the right; without an action it takes no focus. */
    private View row(String title, String sub, String value, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_setting, list, false);
        ((TextView) row.findViewById(R.id.sTitle)).setText(title);
        ((TextView) row.findViewById(R.id.sValue)).setText(value);
        final String hint = sub == null ? "" : sub;
        if (action != null) {
            row.setOnClickListener(v -> action.run());
            row.setOnFocusChangeListener((v, focused) -> {
                if (!focused) return;
                Frame f = stack.peek();
                footerView.setText(hint.isEmpty() && f != null ? f.desc : hint);
            });
        } else {
            row.setFocusable(false);
            row.setClickable(false);
            row.setAlpha(0.75f);
        }
        list.addView(row);
        return row;
    }

    private void toggle(int title, int sub, String key, boolean current) {
        View r = row(getString(title), getString(sub), "", () -> {
            Prefs.putBool(this, key, !current);
            refresh();
        });
        r.findViewById(R.id.sValue).setVisibility(View.GONE);
        View sw = r.findViewById(R.id.sSwitch);
        sw.setBackgroundResource(current ? R.drawable.switch_on : R.drawable.switch_off);
        sw.setVisibility(View.VISIBLE);
    }

    /** A row of a choice page: a check mark on the one that is selected. */
    private View checkRow(String label, boolean checked) {
        View row = row(label, null, "", () -> { });
        setCheck(row, checked);
        return row;
    }

    private void setCheck(View row, boolean checked) {
        ((TextView) row.findViewById(R.id.sValue)).setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, checked ? R.drawable.ic_check : 0, 0);
    }

    // ---- choices ---------------------------------------------------------------------------------

    /** A page with one row per option; picking one goes back and calls {@code onPick}. */
    private void chooseOne(String title, String[] labels, int checked, IntConsumer onPick) {
        push(title, "", () -> {
            for (int i = 0; i < labels.length; i++) {
                final int which = i;
                View row = checkRow(labels[i], i == checked);
                row.setOnClickListener(v -> {
                    stack.pop();
                    onPick.accept(which);
                    if (!isFinishing()) refresh();
                });
            }
        });
    }

    private String sortLabel(String mode) {
        switch (mode) {
            case "recent":
                return getString(R.string.sort_recent);
            case "most":
                return getString(R.string.sort_most);
            case "manual":
                return getString(R.string.sort_manual);
            default:
                return getString(R.string.sort_alpha);
        }
    }

    private void chooseSort() {
        final String[] modes = Config.SORT_MODES;
        String[] labels = new String[modes.length];
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            labels[i] = sortLabel(modes[i]);
            if (modes[i].equals(cfg.sortMode)) checked = i;
        }
        chooseOne(getString(R.string.sort_apps), labels, checked, which -> {
            Prefs.putString(this, Prefs.SORT_MODE, modes[which]);
            if ("manual".equals(modes[which])) Toast.makeText(this, R.string.manual_order_hint, Toast.LENGTH_LONG).show();
        });
    }

    private void chooseNumber(String title, String key, int min, int max, int current) {
        String[] labels = new String[max - min + 2];
        labels[0] = getString(R.string.default_value);
        int checked = Prefs.has(this, key) ? current - min + 1 : 0;
        for (int v = min; v <= max; v++) labels[v - min + 1] = String.valueOf(v);
        chooseOne(title, labels, checked, which -> {
            if (which == 0) Prefs.remove(this, key);
            else Prefs.putInt(this, key, min + which - 1);
        });
    }

    private static final int[] INTERVALS = {6, 10, 30, 0};

    private String intervalLabel(int seconds) {
        return seconds <= 0 ? getString(R.string.interval_off) : getString(R.string.interval_seconds, seconds);
    }

    private void chooseInterval() {
        int current = Prefs.getInt(this, Prefs.FEATURED_INTERVAL, 6);
        String[] labels = new String[INTERVALS.length];
        int checked = 0;
        for (int i = 0; i < INTERVALS.length; i++) {
            labels[i] = intervalLabel(INTERVALS[i]);
            if (INTERVALS[i] == current) checked = i;
        }
        chooseOne(getString(R.string.featured_interval_setting), labels, checked,
                which -> Prefs.putInt(this, Prefs.FEATURED_INTERVAL, INTERVALS[which]));
    }

    private String languageLabel() {
        String chosen = Lang.chosen(this);
        for (int i = 0; i < Lang.TAGS.length; i++) if (Lang.TAGS[i].equals(chosen)) return Lang.NAMES[i];
        return getString(R.string.language_auto);
    }

    private void chooseLanguage() {
        String[] labels = new String[Lang.TAGS.length + 1];
        labels[0] = getString(R.string.language_auto);
        System.arraycopy(Lang.NAMES, 0, labels, 1, Lang.NAMES.length);
        String chosen = Lang.chosen(this);
        int checked = 0;
        for (int i = 0; i < Lang.TAGS.length; i++) if (Lang.TAGS[i].equals(chosen)) checked = i + 1;
        chooseOne(getString(R.string.language_setting), labels, checked, which -> {
            if (which == 0) Prefs.remove(this, Prefs.LANGUAGE);
            else Prefs.putString(this, Prefs.LANGUAGE, Lang.TAGS[which - 1]);
            recreate();
        });
    }

    private static final String[] LOOKS = {UiMode.DARK, UiMode.LIGHT, UiMode.AUTO};

    private String modeLabel(String mode) {
        return getString(UiMode.LIGHT.equals(mode) ? R.string.appearance_light
                : UiMode.AUTO.equals(mode) ? R.string.appearance_auto : R.string.appearance_dark);
    }

    private void chooseAppearance() {
        final String mode = UiMode.mode(this);
        String[] labels = new String[LOOKS.length];
        int checked = 0;
        for (int i = 0; i < LOOKS.length; i++) {
            labels[i] = modeLabel(LOOKS[i]);
            if (LOOKS[i].equals(mode)) checked = i;
        }
        chooseOne(getString(R.string.appearance_setting), labels, checked, which -> {
            Prefs.putString(this, Prefs.UI_MODE, LOOKS[which]);
            recreate();
        });
    }

    private void chooseUnit() {
        final boolean f = "f".equals(Weather.unit(this));
        chooseOne(getString(R.string.weather_unit_setting),
                new String[]{getString(R.string.unit_c), getString(R.string.unit_f)}, f ? 1 : 0,
                which -> Prefs.putString(this, Prefs.WEATHER_UNIT, which == 1 ? "f" : "c"));
    }

    /** Every app can be hidden or shown with one press; each press is saved at once. */
    private void chooseHidden() {
        final List<String[]> apps = installedApps();
        final Set<String> hidden = new HashSet<>(cfg.hiddenApps);
        push(getString(R.string.hidden_apps), getString(R.string.hidden_apps_sub), () -> {
            for (final String[] app : apps) {
                final View row = checkRow(app[1], hidden.contains(app[0]));
                row.setOnClickListener(v -> {
                    if (!hidden.remove(app[0])) hidden.add(app[0]);
                    setCheck(row, hidden.contains(app[0]));
                    Prefs.putList(this, Prefs.HIDDEN_APPS, new ArrayList<>(hidden));
                });
            }
        });
    }

    /** Every app the home screen could show, as {package, label}, by label. */
    private List<String[]> installedApps() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            out.add(new String[]{pkg, String.valueOf(ri.loadLabel(pm))});
        }
        Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    // ---- themes ----------------------------------------------------------------------------------

    /** A saved theme: apply it or delete it from the TV's memory. */
    private void themeActions(final ThemeManager.Theme theme) {
        push(theme.name, getString(R.string.theme_saved_sub), () -> {
            row(R.string.theme_apply, R.string.theme_apply_sub, "", () -> {
                String err = ThemeManager.apply(this, theme);
                if (err != null) {
                    Toast.makeText(this, getString(R.string.theme_failed, err), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, R.string.theme_applied, Toast.LENGTH_SHORT).show();
                finish();
            });
            row(R.string.theme_delete, R.string.theme_delete_sub, "", () -> {
                ThemeManager.delete(this, theme);
                stack.pop();
                refresh();
            });
        });
    }

    /** Lists the themes found on the TV's storage and USB drives; the one picked is saved in the TV's memory. */
    private void installFromFiles() {
        final List<ThemeManager.Theme> found = ThemeManager.scan(this);
        if (found.isEmpty()) {
            message(getString(R.string.theme_install_file), getString(R.string.theme_none_found)
                    + (filesAccess() ? "" : "\n\n" + getString(R.string.theme_need_files))
                    + "\n\n" + getString(R.string.theme_folders_body) + "\n" + folderList());
            return;
        }
        String[] labels = new String[found.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = found.get(i).name;
        chooseOne(getString(R.string.theme_install_file), labels, -1, which -> {
            String err = ThemeManager.importTheme(this, found.get(which));
            Toast.makeText(this, err == null ? getString(R.string.theme_saved)
                    : getString(R.string.theme_add_failed, err), Toast.LENGTH_LONG).show();
        });
    }

    /** Asks for an address (a .zip theme, or a config.json) and saves the theme in the TV's memory. */
    private void installFromWeb() {
        final TvDialog dialog = new TvDialog(this)
                .title(getString(R.string.theme_install_web))
                .message(getString(R.string.theme_url_help));
        final EditText input = dialog.input(getString(R.string.theme_url_hint));
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        dialog.button(getString(R.string.close), null)
                .button(getString(R.string.theme_download), () -> {
                    final String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    Toast.makeText(this, R.string.theme_downloading, Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        final String err = ThemeManager.importUrl(getApplicationContext(), url);
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            Toast.makeText(this, err == null ? getString(R.string.theme_saved)
                                    : getString(R.string.theme_add_failed, err), Toast.LENGTH_LONG).show();
                            refresh();
                        });
                    }).start();
                })
                .show();
    }

    private String folderList() {
        StringBuilder sb = new StringBuilder();
        for (File f : ThemeManager.roots(this)) sb.append(f.getPath()).append('\n');
        return sb.toString().trim();
    }

    // ---- permissions -----------------------------------------------------------------------------

    private boolean filesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void openFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (ActivityNotFoundException e2) {
                message(getString(R.string.perm_files), getString(R.string.settings_unavailable) + "\n\n"
                        + "adb shell appops set " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
            }
        }
    }

    /** READ_TV_LISTINGS is a normal runtime permission: the system asks on screen. */
    private void requestTvListings() {
        requestPermissions(new String[]{FeaturedLoader.READ_TV_LISTINGS}, 2);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    /**
     * Explains what a permission is for. When it is not granted yet, offers to open the system screen
     * for it (when there is one) and always shows the adb command that grants it from a computer.
     */
    private void askPermission(boolean granted, int title, int body, Runnable open, String adb) {
        TvDialog d = new TvDialog(this)
                .title(getString(title))
                .message(getString(body) + "\n\n" + status(granted) + "\n\n" + getString(R.string.adb_hint) + "\n" + adb)
                .button(getString(R.string.close), null);
        if (!granted && open != null) d.button(getString(R.string.open_settings), open);
        d.show();
    }

    // ---- dialogs ---------------------------------------------------------------------------------

    private void confirm(int title, int body, Runnable yes) {
        new TvDialog(this)
                .title(getString(title))
                .message(getString(body))
                .button(getString(R.string.close), null)
                .button(getString(R.string.ok), yes)
                .show();
    }

    private void message(String title, String text) {
        new TvDialog(this)
                .title(title)
                .message(text)
                .button(getString(R.string.ok), null)
                .show();
    }
}
