package patch.kalendar;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preuzimanje fontova sa Google Fonts (izvrsava se u aplikaciji modula, koja ima INTERNET dozvolu).
 * Preuzeti TTF se cuva lokalno (za pregled) i upisuje u postavke modula, odakle ga cita Pravoslavac (vidi Fonts).
 */
final class FontStore {
    private FontStore() {}

    private static final int MAX_BYTES = 8 * 1024 * 1024;

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    /** Postavke modula; pokusava se citljiv-za-sve nacin koji LSPosed podrzava (MODE_WORLD_READABLE = 1). */
    private static SharedPreferences prefs(Context c, String name) {
        try {
            return c.getSharedPreferences(name, 1);
        } catch (SecurityException e) {
            return c.getSharedPreferences(name, Context.MODE_PRIVATE);
        }
    }

    private static final String CATALOG_PREFS = "fonts_catalog";
    private static final String CATALOG_URL = "https://fonts.google.com/metadata/fonts";

    /** Spisak fontova sa ćirilicom: osvježen sa Googlea ako je osvježavan, inače ugrađeni (FontCatalog.FAMILIES). */
    static String[] catalog(Context c) {
        String s = c.getSharedPreferences(CATALOG_PREFS, Context.MODE_PRIVATE).getString("list", "");
        if (s.length() == 0) return FontCatalog.FAMILIES;
        String[] a = s.split("\\|");
        Arrays.sort(a, String.CASE_INSENSITIVE_ORDER);
        return a;
    }

    /** Preuzima spisak svih Google fontova i čuva one sa podskupom "cyrillic". Vraća broj fontova. Blokira. */
    static int refreshCatalog(Context c) throws Exception {
        String json = new String(get(CATALOG_URL), "UTF-8");
        int i = json.indexOf('{');   // odgovor počinje zaštitnim prefiksom )]}'
        if (i < 0) throw new IOException("Neočekivan odgovor Googlea");
        JSONArray arr = new JSONObject(json.substring(i)).getJSONArray("familyMetadataList");
        List<String> out = new ArrayList<String>();
        for (int k = 0; k < arr.length(); k++) {
            JSONObject f = arr.getJSONObject(k);
            JSONArray subs = f.optJSONArray("subsets");
            if (subs == null) continue;
            for (int j = 0; j < subs.length(); j++) {
                if ("cyrillic".equals(subs.optString(j))) {
                    String fam = f.optString("family", "");
                    if (fam.length() > 0 && fam.indexOf('|') < 0) out.add(fam);
                    break;
                }
            }
        }
        if (out.isEmpty()) throw new IOException("Spisak je prazan (format odgovora se promijenio?)");
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder();
        for (String f : out) {
            if (sb.length() > 0) sb.append('|');
            sb.append(f);
        }
        c.getSharedPreferences(CATALOG_PREFS, Context.MODE_PRIVATE).edit().putString("list", sb.toString()).commit();
        return out.size();
    }

    private static File localDir(Context c) {
        return new File(c.getFilesDir(), "fonts");
    }

    /** Lokalni (regularni) fajl preuzetog fonta, ili null. */
    static File localFile(Context c, String family) {
        File f = new File(localDir(c), FontCatalog.slug(family) + "-r.ttf");
        return f.isFile() ? f : null;
    }

    static List<String> installed(Context c) {
        List<String> out = new ArrayList<String>();
        String s = prefs(c, FontCatalog.PREFS_INDEX).getString("list", "");
        for (String f : s.split("\\|")) {
            if (f.length() > 0) out.add(f);
        }
        return out;
    }

    private static void saveList(Context c, List<String> l, String slug, long ver) {
        StringBuilder sb = new StringBuilder();
        for (String f : l) {
            if (sb.length() > 0) sb.append('|');
            sb.append(f);
        }
        SharedPreferences.Editor e = prefs(c, FontCatalog.PREFS_INDEX).edit().putString("list", sb.toString());
        if (ver != 0L) e.putLong("v_" + slug, ver);
        else e.remove("v_" + slug);
        e.commit();
    }

    // ------------------------------------------------------------------ mreza

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            // nepoznat User-Agent: Google vraca TTF (a ne woff2) fajlove
            c.setRequestProperty("User-Agent", "PravoslavacDark/1.0");
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("Font je prevelik");
            }
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }

    /** Provjera da font ima sva ćirilična slova koja srpski tekst koristi (Android 6+; ranije se ne provjerava). */
    private static boolean hasCyrillic(File f) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            Paint p = new Paint();
            p.setTypeface(Typeface.createFromFile(f));
            for (String ch : new String[] { "Ж", "Ђ", "Ћ", "Џ", "Ј", "Љ", "Њ", "ж", "ђ", "ћ", "џ", "ј", "љ", "њ" }) {
                if (!p.hasGlyph(ch)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Preuzima font (blokira: pozivati sa pozadinske niti). Baca izuzetak sa porukom ako ne uspije. */
    static void download(Context c, String family) throws Exception {
        String enc = URLEncoder.encode(family, "UTF-8");
        String base = "https://fonts.googleapis.com/css2?family=" + enc;
        String css;
        try {
            css = new String(get(base + ":wght@400;700"), "UTF-8");
        } catch (HttpError e) {
            if (e.code != 400) throw e;
            css = new String(get(base), "UTF-8");   // font bez podebljanog stila
        }
        if (css.contains("unicode-range")) throw new IOException("Neočekivan format odgovora (woff2)");

        String regUrl = null;
        String boldUrl = null;
        Matcher fm = Pattern.compile("@font-face\\s*\\{([^}]*)\\}").matcher(css);
        while (fm.find()) {
            String b = fm.group(1);
            Matcher u = Pattern.compile("url\\(([^)]+)\\)").matcher(b);
            if (!u.find()) continue;
            String url = u.group(1).replace("'", "").replace("\"", "").trim();
            Matcher st = Pattern.compile("font-style:\\s*(\\w+)").matcher(b);
            if (st.find() && !"normal".equals(st.group(1))) continue;
            int w1 = 400;
            boolean range = false;
            Matcher w = Pattern.compile("font-weight:\\s*(\\d+)(\\s+\\d+)?").matcher(b);
            if (w.find()) {
                w1 = Integer.parseInt(w.group(1));
                range = w.group(2) != null;   // promjenljivi font: jedan fajl za sve tezine
            }
            if (range || w1 == 400) {
                if (regUrl == null) regUrl = url;
            } else if (w1 == 700) {
                if (boldUrl == null) boldUrl = url;
            }
        }
        if (regUrl == null) throw new IOException("Font nema običan stil");
        if (boldUrl != null && boldUrl.equals(regUrl)) boldUrl = null;

        byte[] reg = get(regUrl);
        byte[] bold = boldUrl == null ? null : get(boldUrl);

        String slug = FontCatalog.slug(family);
        File d = localDir(c);
        d.mkdirs();
        File rf = new File(d, slug + "-r.ttf");
        File bf = new File(d, slug + "-b.ttf");
        write(rf, reg);
        if (!hasCyrillic(rf)) {
            rf.delete();
            throw new IOException("Font nema ćirilicu (ili nema sva srpska slova)");
        }
        if (bold != null) write(bf, bold);
        else bf.delete();

        long ver = System.currentTimeMillis();
        SharedPreferences.Editor e = prefs(c, FontCatalog.PREFS_FONT + slug).edit()
                .putString("reg", Base64.encodeToString(reg, Base64.NO_WRAP))
                .putLong("ver", ver);
        if (bold != null) e.putString("bold", Base64.encodeToString(bold, Base64.NO_WRAP));
        else e.remove("bold");
        e.commit();

        List<String> l = installed(c);
        if (!l.contains(family)) l.add(family);
        saveList(c, l, slug, ver);
    }

    /** Brise sve preuzete fontove odjednom. */
    static void deleteAll(Context c) {
        for (String f : installed(c)) delete(c, f);
    }

    /** Brise preuzeti font (lokalno i iz postavki modula). */
    static void delete(Context c, String family) {
        String slug = FontCatalog.slug(family);
        new File(localDir(c), slug + "-r.ttf").delete();
        new File(localDir(c), slug + "-b.ttf").delete();
        prefs(c, FontCatalog.PREFS_FONT + slug).edit().clear().commit();
        try {
            new File(c.getApplicationInfo().dataDir, "shared_prefs/" + FontCatalog.PREFS_FONT + slug + ".xml").delete();
        } catch (Throwable t) {
            // ostaje prazan fajl postavki
        }
        List<String> l = installed(c);
        l.remove(family);
        saveList(c, l, slug, 0L);
    }
}
