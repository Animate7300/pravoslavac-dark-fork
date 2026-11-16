package patch.kalendar;

import android.graphics.Typeface;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * Fontovi po vrsti teksta (izvrsava se u procesu Pravoslavca / Zitija svetih).
 * <p>
 * Fontove preuzima aplikacija modula (ona ima INTERNET) i upisuje u svoje postavke (FontCatalog.PREFS_*), a ovdje se
 * jednom uvezu u podatke aplikacije (files/fonts) i odatle koriste: nativni tekst preko Typeface-a, WebView preko
 * "lazne" putanje asseta (vidi Hook.INTERCEPT i webCss()).
 */
final class Fonts {
    private Fonts() {}

    private static final int TAG_FKEY = 0x7e0c0a21;
    private static final int TAG_FSTYLE = 0x7e0c0a22;
    private static final int TAG_FORIG = 0x7e0c0a23;
    private static final int TAG_FSIZE = 0x7e0c0a24;
    private static final int TAG_ZOOM0 = 0x7e0c0a25;

    /** Postavlja se dok modul sam mijenja font (da hook za setTypeface to ne "popravlja"). */
    static volatile boolean busy;

    private static final class Face {
        Typeface reg;
        Typeface bold;
        final Typeface[] styled = new Typeface[4];
    }

    private static final Map<String, Face> FACES = new HashMap<String, Face>();
    private static XSharedPreferences idx;
    private static long idxCheck;

    // ------------------------------------------------------------------ spisak i kesiranje

    private static synchronized XSharedPreferences index() {
        if (idx == null) idx = new XSharedPreferences(FontCatalog.MODULE_APP_ID, FontCatalog.PREFS_INDEX);
        long now = System.currentTimeMillis();
        if (now - idxCheck > 1500L) {
            idxCheck = now;
            if (idx.hasFileChanged()) idx.reload();
        }
        return idx;
    }

    /** Fontovi koje je modul preuzeo (za izbor u uredjivacu tema). */
    static synchronized List<String> available() {
        List<String> out = new ArrayList<String>();
        try {
            String s = index().getString("list", "");
            for (String f : s.split("\\|")) {
                if (f.length() > 0) out.add(f);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);   // azbucni red
        return out;
    }

    /** Ponovo ucitaj spisak i fontove (poslije promjene izbora ili novog preuzimanja). */
    static synchronized void reset() {
        FACES.clear();
        idxCheck = 0;
        try {
            if (idx != null) idx.reload();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static File dir() {
        return new File(Prefs.dir(), "files/fonts");
    }

    private static long readLong(File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            try {
                return Long.parseLong(r.readLine().trim());
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }

    /** {regularni, podebljani ili null} fajl fonta, uvezen iz postavki modula ako treba; null ako fonta nema. */
    private static File[] cached(String family) {
        String slug = FontCatalog.slug(family);
        File d = dir();
        File reg = new File(d, slug + "-r.ttf");
        File bold = new File(d, slug + "-b.ttf");
        File ver = new File(d, slug + ".ver");
        long want = 0L;
        try {
            want = index().getLong("v_" + slug, 0L);
        } catch (Throwable t) {
            // spisak nedostupan: koristi se ono sto je vec uvezeno
        }
        if (want == 0L) {
            // spisak modula postoji, a fonta u njemu nema: preuzeti font je obrisan, pa se brise i kopija
            try {
                File idxFile = index().getFile();
                if (idxFile != null && idxFile.exists()) {
                    reg.delete();
                    bold.delete();
                    ver.delete();
                    return null;
                }
            } catch (Throwable t) {
                // spisak nedostupan: ostaje kesirano
            }
        }
        if (want != 0L && !(reg.isFile() && readLong(ver) == want)) {
            try {
                XSharedPreferences p = new XSharedPreferences(FontCatalog.MODULE_APP_ID, FontCatalog.PREFS_FONT + slug);
                String r = p.getString("reg", null);
                if (r != null) {
                    d.mkdirs();
                    write(reg, Base64.decode(r, Base64.DEFAULT));
                    String b = p.getString("bold", null);
                    if (b != null) write(bold, Base64.decode(b, Base64.DEFAULT));
                    else bold.delete();
                    write(ver, Long.toString(p.getLong("ver", want)).getBytes("UTF-8"));
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        if (!reg.isFile()) return null;
        return new File[] { reg, bold.isFile() ? bold : null };
    }

    private static synchronized Face face(String family) {
        Face f = FACES.get(family);
        if (f == null) {
            f = new Face();
            try {
                File[] c = cached(family);
                if (c != null) {
                    f.reg = Typeface.createFromFile(c[0]);
                    if (c[1] != null) f.bold = Typeface.createFromFile(c[1]);
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
                f.reg = null;
                f.bold = null;
            }
            FACES.put(family, f);
        }
        return f.reg == null ? null : f;
    }

    /** Typeface fonta izabranog za datu boju/tekst (kljuc iz Colors) u datom stilu, ili null (originalni font). */
    static Typeface get(String key, int style) {
        String fam = Prefs.font(key);
        if (fam == null) return null;
        Face f = face(fam);
        if (f == null) return null;
        int s = style & 3;
        synchronized (Fonts.class) {
            Typeface t = f.styled[s];
            if (t == null) {
                Typeface base = f.reg;
                int rest = s;
                if ((s & Typeface.BOLD) != 0 && f.bold != null) {
                    base = f.bold;
                    rest = s & ~Typeface.BOLD;
                }
                t = rest == 0 ? base : Typeface.create(base, rest);
                f.styled[s] = t;
            }
            return t;
        }
    }

    private static synchronized boolean isOurs(Typeface t) {
        if (t == null) return false;
        for (Face f : FACES.values()) {
            if (f.reg == t || f.bold == t) return true;
            for (Typeface s : f.styled) {
                if (s == t) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ nativni tekst

    private static void set(TextView tv, Typeface t) {
        busy = true;
        try {
            tv.setTypeface(t);
        } finally {
            busy = false;
        }
    }

    private static void setSize(TextView tv, float px) {
        busy = true;
        try {
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
        } finally {
            busy = false;
        }
    }

    /** Primjenjuje font i velicinu izabrane za tekst "key" na TextView (ili vraca original ako nije izabrano nista). */
    static void apply(TextView tv, String key) {
        if (tv == null || key == null) return;
        try {
            if (!Prefs.dark()) return;
            Object oldKey = tv.getTag(TAG_FKEY);
            String fam = Prefs.font(key);
            int pct = Prefs.fontSize(key);
            if (fam == null && pct == 100) {
                if (oldKey != null) restore(tv);
                return;
            }
            if (tv.getTag(TAG_FSTYLE) == null) {   // prvi put: zapamti izvorni font, stil i velicinu
                Typeface cur = tv.getTypeface();
                tv.setTag(TAG_FORIG, cur == null ? (Object) Boolean.FALSE : (Object) cur);
                tv.setTag(TAG_FSTYLE, Integer.valueOf(cur == null ? 0 : cur.getStyle()));
                tv.setTag(TAG_FSIZE, Float.valueOf(tv.getTextSize()));
            }
            tv.setTag(TAG_FKEY, key);
            Typeface t;
            if (fam == null) {
                Object o = tv.getTag(TAG_FORIG);
                t = o instanceof Typeface ? (Typeface) o : null;
            } else {
                t = get(key, ((Integer) tv.getTag(TAG_FSTYLE)).intValue());
            }
            if (t != null && tv.getTypeface() != t) set(tv, t);
            float want = ((Float) tv.getTag(TAG_FSIZE)).floatValue() * pct / 100f;
            if (Math.abs(tv.getTextSize() - want) > 0.5f) setSize(tv, want);
        } catch (Throwable e) {
            XposedBridge.log(e);
        }
    }

    /** Ponovo primijeni font koji je ovom TextView-u vec dodijeljen (npr. poslije izmjene izbora u temi). */
    static void refresh(TextView tv) {
        Object k = tv.getTag(TAG_FKEY);
        if (k instanceof String) apply(tv, (String) k);
    }

    private static void restore(TextView tv) {
        Object o = tv.getTag(TAG_FORIG);
        Object sz = tv.getTag(TAG_FSIZE);
        tv.setTag(TAG_FKEY, null);
        tv.setTag(TAG_FSTYLE, null);
        tv.setTag(TAG_FORIG, null);
        tv.setTag(TAG_FSIZE, null);
        set(tv, o instanceof Typeface ? (Typeface) o : null);
        if (sz instanceof Float) setSize(tv, ((Float) sz).floatValue());
    }

    /** Poziva se prije TextView.setTextSize(unit, size): velicina se mnozi izabranim procentom za taj tekst. */
    static void onSetTextSize(TextView tv, Object[] args) {
        Object k = tv.getTag(TAG_FKEY);
        if (!(k instanceof String)) return;
        float px = android.util.TypedValue.applyDimension(((Integer) args[0]).intValue(),
                ((Float) args[1]).floatValue(), tv.getResources().getDisplayMetrics());
        tv.setTag(TAG_FSIZE, Float.valueOf(px));
        args[0] = Integer.valueOf(android.util.TypedValue.COMPLEX_UNIT_PX);
        args[1] = Float.valueOf(px * Prefs.fontSize((String) k) / 100f);
    }

    /** Velicina teksta stranica u WebView-u ("Листе књига и молитви"): zoom teksta WebView-a (pozivati na pocetku ucitavanja). */
    static void applyWebZoom(android.webkit.WebView wv) {
        try {
            if (!Prefs.dark()) return;
            android.webkit.WebSettings ws = wv.getSettings();
            Object o = wv.getTag(TAG_ZOOM0);
            int base;
            if (o instanceof Integer) {
                base = ((Integer) o).intValue();
            } else {
                base = ws.getTextZoom();
                wv.setTag(TAG_ZOOM0, Integer.valueOf(base));
            }
            int want = Math.round(base * Prefs.fontSize("c_web_text") / 100f);
            if (ws.getTextZoom() != want) ws.setTextZoom(want);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Font svim TextView-ovima unutar v (ukljucujuci v). */
    static void applyTree(View v, String key) {
        if (v instanceof TextView) apply((TextView) v, key);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTree(g.getChildAt(i), key);
        }
    }

    /**
     * Poziva se prije TextView.setTypeface: ako je TextView pod nasim fontom, aplikacijin font se zamjenjuje nasim
     * (uz zadrzan stil: podebljano / kurziv).
     */
    static void onSetTypeface(TextView tv, Object[] args) {
        Object k = tv.getTag(TAG_FKEY);
        if (!(k instanceof String)) return;
        Typeface in = (Typeface) args[0];
        if (isOurs(in)) return;
        int st = (in == null ? 0 : in.getStyle()) | (args.length > 1 ? ((Integer) args[1]).intValue() : 0);
        tv.setTag(TAG_FSTYLE, Integer.valueOf(st & 3));
        Typeface t = get((String) k, st);
        if (t == null) return;
        args[0] = t;
        if (args.length > 1) args[1] = Integer.valueOf(0);
    }

    // ------------------------------------------------------------------ WebView

    /** CSS familija (npr. "pdk_open_sans,sans-serif") fonta izabranog za tekst "key", ili null. */
    static String cssFamily(String key) {
        String fam = Prefs.font(key);
        if (fam == null || face(fam) == null) return null;
        return "pdk_" + FontCatalog.slug(fam) + ",sans-serif";
    }

    /** {kljuc, selektor za font-family, selektor za velicinu (zoom; null = rjesava se drugacije)}. */
    private static final String[][] WEB_RULES = {
            { "c_web_text", "html,body,body *", null },
            { "c_web_link", "a,a *", "a" },
            { "c_title", "h1,h2,h3,h4,h1 *,h2 *,h3 *,h4 *,.color-red,.color-red *", "h1,h2,h3,h4,.color-red" },
            { "c_btn_text", "[data-dkb][data-dkb],[data-dkb][data-dkb] *", "[data-dkb][data-dkb]" },
    };

    /** CSS sa @font-face i pravilima font-family za tekstove u WebView-u (prazno ako nije izabran nijedan font). */
    static String webCss() {
        try {
            if (!Prefs.dark()) return "";
            StringBuilder faces = new StringBuilder();
            StringBuilder rules = new StringBuilder();
            Set<String> done = new HashSet<String>();
            for (String[] r : WEB_RULES) {
                String css = cssFamily(r[0]);
                if (css != null) {
                    String fam = Prefs.font(r[0]);
                    if (done.add(fam)) faceDecl(faces, fam);
                    rules.append(r[1]).append("{font-family:").append(css).append(" !important}\n");
                }
                int pct = Prefs.fontSize(r[0]);
                if (r[2] != null && pct != 100) {
                    rules.append(r[2]).append("{zoom:").append(pct).append("% !important}\n");
                }
            }
            return faces.toString() + rules;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return "";
        }
    }

    private static void faceDecl(StringBuilder sb, String fam) {
        Face f = face(fam);
        if (f == null) return;
        String slug = FontCatalog.slug(fam);
        String base = "file:///android_asset/" + FontCatalog.WEB_DIR + "/";
        sb.append("@font-face{font-family:pdk_").append(slug).append(";font-weight:400;src:url(")
                .append(base).append(slug).append("-r.ttf)}\n");
        if (f.bold != null) {
            sb.append("@font-face{font-family:pdk_").append(slug).append(";font-weight:700;src:url(")
                    .append(base).append(slug).append("-b.ttf)}\n");
        }
    }

    /** Fajl fonta za "lazni" asset zahtjev (npr. open_sans-r.ttf), ili null. */
    static File webFile(String name) {
        if (name == null || !name.matches("[a-z0-9_]+-[rb]\\.ttf")) return null;
        String slug = name.substring(0, name.length() - 6);
        String fam = null;
        for (String f : available()) {
            if (FontCatalog.slug(f).equals(slug)) fam = f;
        }
        if (fam == null) fam = FontCatalog.bySlug(slug);
        if (fam == null) return null;
        File[] c = cached(fam);
        if (c == null) return null;
        return name.endsWith("-b.ttf") ? c[1] : c[0];
    }
}
