package patch.kalendar;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

final class Prefs {
    private Prefs() {}

    static final String MODULE_PKG = "patch.kalendar";
    static final String FILE = "patch_settings";
    static final String OLD_FILE = "settings";

    static final String KEY_OPEN_CALENDAR = "open_calendar";
    static final String KEY_SKIP_SPLASH = "skip_splash";
    static final String KEY_DARK = "dark";
    static final String KEY_LEFT = "left_align";
    static final String KEY_CUSTOM_FONT = "use_custom_font";

    private static String dataDir;
    private static XSharedPreferences xp;
    private static long lastCheck;
    private static boolean force;

    static void init(String dir) {
        if (dir != null && dir.length() > 0) dataDir = dir;
    }

    static synchronized void refresh() {
        force = true;
    }

    private static XSharedPreferences sp() {
        if (xp == null) {
            String dir = dataDir != null ? dataDir : "/data/data/" + Hook.PKG;
            xp = new XSharedPreferences(new File(dir, "shared_prefs/" + FILE + ".xml"));
        }
        long now = System.currentTimeMillis();
        if (force) {
            force = false;
            lastCheck = now;
            xp.reload();
        } else if (now - lastCheck > 1000L) {
            lastCheck = now;
            if (xp.hasFileChanged()) xp.reload();
        }
        return xp;
    }

    private static synchronized boolean get(String key, boolean def) {
        try {
            return sp().getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String lastJson, lastSel;
    private static Themes.Theme lastTheme;

    private static Themes.Theme theme() {
        try {
            String json = sp().getString(Themes.KEY_THEMES, null);
            String sel = sp().getString(Themes.KEY_SEL, null);
            if (lastTheme != null && eq(json, lastJson) && eq(sel, lastSel)) return lastTheme;
            lastTheme = Themes.find(Themes.parse(json), sel);
            lastJson = json;
            lastSel = sel;
            return lastTheme;
        } catch (Throwable t) {
            return Themes.builtins().get(0);
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static synchronized int color(String key) {
        return Colors.parse(theme().color(key)).intValue();
    }

    static boolean openCalendar() { return get(KEY_OPEN_CALENDAR, true); }
    static boolean skipSplash() { return get(KEY_SKIP_SPLASH, true); }
    static boolean dark() { return get(KEY_DARK, true); }
    static boolean left() { return get(KEY_LEFT, true); }
    static boolean useCustomFont() { return get(KEY_CUSTOM_FONT, false); }
}