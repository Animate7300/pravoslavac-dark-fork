package patch.kalendar;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Strana Pravoslavca: trazi od modula (FontProvider) spisak, pregled i preuzimanje fontova, jer Pravoslavac nema INTERNET.
 * Svi pozivi blokiraju (pozivati sa pozadinske niti). Izvrsava se u procesu Pravoslavca.
 */
final class FontClient {

    static final class State {
        String[] catalog = new String[0];
        final Set<String> installed = new HashSet<String>();
        final Set<String> bad = new HashSet<String>();
    }

    static final class Preview {
        final File file;
        final boolean complete;

        Preview(File file, boolean complete) {
            this.file = file;
            this.complete = complete;
        }
    }

    private final Context ctx;

    FontClient(Context c) {
        this.ctx = c;
    }

    @SuppressWarnings("deprecation")
    private Bundle call(String method, String arg) throws IOException {
        Bundle r;
        try {
            r = ctx.getContentResolver().call(Uri.parse("content://" + FontCatalog.PROVIDER_AUTHORITY), method, arg, null);
        } catch (Throwable t) {
            String m = t.getMessage() != null ? t.getMessage() : "";
            if (m.contains("Unknown URI") || m.contains("Unknown URL") || m.contains("Unknown authority")) {
                throw new IOException("Православац не види провајдер модула (видљивост пакета, Android 11+): " + m);
            }
            throw new IOException(t.getClass().getSimpleName() + ": " + m);
        }
        if (r == null) throw new IOException("модул не одговара");
        String e = r.getString("error");
        if (e != null) throw new IOException(e);
        return r;
    }

    State state() throws IOException {
        Bundle b = call("state", null);
        State s = new State();
        String[] cat = b.getStringArray("catalog");
        if (cat != null) s.catalog = cat;
        String[] in = b.getStringArray("installed");
        if (in != null) s.installed.addAll(Arrays.asList(in));
        String[] bad = b.getStringArray("bad");
        if (bad != null) s.bad.addAll(Arrays.asList(bad));
        return s;
    }

    int refresh() throws IOException {
        return call("refresh", null).getInt("count", 0);
    }

    void download(String family) throws IOException {
        call("download", family);
    }

    void delete(String family) throws IOException {
        call("delete", family);
    }

    void deleteAll() throws IOException {
        call("deleteAll", null);
    }

    void markBad(String family) {
        try {
            call("bad", family);
        } catch (Throwable ignored) {
            // nije bitno: pregled ce se ponoviti
        }
    }

    private File cacheFile(String family) {
        File d = new File(ctx.getCacheDir(), "pdk_fontprev");
        d.mkdirs();
        return new File(d, FontCatalog.slug(family) + ".ttf");
    }

    /** Isjecak fonta za pregled (kes u Pravoslavcu; ako ga nema, trazi se od modula). */
    Preview preview(String family) throws IOException {
        File f = cacheFile(family);
        if (f.isFile() && f.length() > 0) {
            boolean ok = FontStore.previewComplete(f);
            if (!ok) markBad(family);
            return new Preview(ok ? f : null, ok);
        }
        Bundle b = call("preview", family);
        if (!b.getBoolean("complete", true)) return new Preview(null, false);
        byte[] data = b.getByteArray("data");
        if (data == null || data.length == 0) throw new IOException("нема исјечка фонта");
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
        return new Preview(f, true);
    }
}
