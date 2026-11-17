package patch.kalendar;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ekran modula (otvara se iz LSPosed-a ili iz podesavanja u Pravoslavcu): preuzimanje ćiriličnih Google fontova.
 * Pravoslavac nema INTERNET dozvolu, pa fontove preuzima ovaj ekran, a izbor fonta za svaki tekst je u uređivaču
 * tema unutar Pravoslavca (Podešavanja → Teme, uz svaku boju iz grupe „Текст“).
 */
public class MainActivity extends Activity {

    private static final int BG = 0xff121212;
    private static final int FG = 0xffe0e0e0;
    private static final String SAMPLE = FontCatalog.PREVIEW_TEXT;

    private final Set<String> busy = new HashSet<String>();
    private LinearLayout list;
    private TextView count;
    private String filter = "";
    private ScrollView scroll;

    // pregled fontova koji nisu preuzeti (mali isječci sa slovima pangrama, učitavaju se kad red postane vidljiv)
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Map<String, TextView> samples = new HashMap<String, TextView>();
    private final Map<String, TextView> names = new HashMap<String, TextView>();
    private final Map<String, Typeface> faces = new HashMap<String, Typeface>();
    private final Set<String> previewBusy = new HashSet<String>();
    private final Set<String> previewFailed = new HashSet<String>();
    private final Set<String> previewIncomplete = new HashSet<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Pravoslavac Dark – fontovi");

        int pad = dp(16);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        scroll = sv;
        sv.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override
            public void onScrollChanged() {
                loadVisiblePreviews();
            }
        });

        TextView info = new TextView(this);
        info.setText("Ovdje preuzimate ćirilične Google fontove (internet se koristi samo kad pritisnete „Preuzmi“).\n\n"
                + "Font za svaki tekst birate u aplikaciji Pravoslavac (ili Житије светих): "
                + "Meni → Podešavanja → Teme → uz boju iz grupe „Текст“ pritisnite „Фонт“.");
        info.setTextSize(15f);
        info.setTextColor(FG);
        info.setPadding(0, 0, 0, pad);
        root.addView(info);

        final Button refresh = new Button(this);
        refresh.setText("Osvježi spisak fontova sa Googlea");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshCatalog(refresh);
            }
        });
        root.addView(refresh);

        final Button delAll = new Button(this);
        delAll.setText("Obriši sve preuzete fontove");
        delAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (FontStore.installed(MainActivity.this).isEmpty()) {
                    Toast.makeText(MainActivity.this, "Nema preuzetih fontova", Toast.LENGTH_SHORT).show();
                    return;
                }
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Obrisati sve preuzete fontove?")
                        .setMessage("Tekstovi koji ih koriste vratiće se na originalni font.")
                        .setPositiveButton("Obriši", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                FontStore.deleteAll(MainActivity.this);
                                rebuild();
                            }
                        })
                        .setNegativeButton("Odustani", null)
                        .show();
            }
        });
        root.addView(delAll);

        EditText search = new EditText(this);
        search.setHint("Pretraga");
        search.setSingleLine(true);
        search.setTextColor(FG);
        search.setHintTextColor(0xff777777);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                rebuild();
            }
        });
        root.addView(search);

        count = new TextView(this);
        count.setTextSize(12f);
        count.setTextColor(0xff9aa0a6);
        count.setPadding(0, dp(4), 0, dp(8));
        root.addView(count);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        setContentView(sv);
        rebuild();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    private String label(String fam, boolean has) {
        String s = has ? fam + "  ✓" : fam;
        if (!has && previewIncomplete.contains(fam)) s += "  ⚠ нема сва слова";
        return s;
    }

    private void rebuild() {
        samples.clear();
        names.clear();
        list.removeAllViews();
        java.util.List<String> have = FontStore.installed(this);
        String[] all = FontStore.catalog(this);
        int shown = 0;
        for (final String fam : all) {
            if (filter.length() > 0 && !fam.toLowerCase(java.util.Locale.ROOT).contains(filter)) continue;
            shown++;
            final boolean has = have.contains(fam);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(label(fam, has));
            names.put(fam, name);
            name.setTextSize(13f);
            name.setTextColor(has ? 0xff8ab4f8 : 0xff9aa0a6);
            left.addView(name);
            TextView sample = new TextView(this);
            sample.setText(SAMPLE);
            sample.setTextSize(16f);
            samples.put(fam, sample);
            sample.setTextColor(FG);
            File lf = has ? FontStore.localFile(this, fam) : null;
            if (lf != null) {
                try {
                    sample.setTypeface(Typeface.createFromFile(lf));
                } catch (Throwable ignored) {
                    // ostaje podrazumijevani font
                }
            }
            if (!has && faces.containsKey(fam)) sample.setTypeface(faces.get(fam));
            left.addView(sample);
            row.setTag(fam);
            row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final Button b = new Button(this);
            boolean working = busy.contains(fam);
            b.setText(working ? "…" : has ? "Obriši" : "Preuzmi");
            b.setEnabled(!working);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (has) {
                        FontStore.delete(MainActivity.this, fam);
                        rebuild();
                    } else {
                        download(fam);
                    }
                }
            });
            row.addView(b);
            list.addView(row);
        }
        count.setText(shown + " / " + all.length + " fontova sa ćirilicom");
        list.post(new Runnable() {
            @Override
            public void run() {
                loadVisiblePreviews();
            }
        });
    }

    /** Pokreće učitavanje pregleda za redove koji su na ekranu (i za nekoliko redova ispod njih). */
    private void loadVisiblePreviews() {
        Rect r = new Rect();
        int n = list.getChildCount();
        for (int i = 0; i < n; i++) {
            View row = list.getChildAt(i);
            if (!row.getGlobalVisibleRect(r)) continue;
            for (int k = i; k < Math.min(n, i + 4); k++) {
                Object t = list.getChildAt(k).getTag();
                if (t instanceof String) startPreview((String) t);
            }
        }
    }

    private void startPreview(final String fam) {
        if (FontStore.installed(this).contains(fam)) return;   // preuzeti font se prikazuje cijeli
        if (faces.containsKey(fam) || previewBusy.contains(fam) || previewFailed.contains(fam)) return;
        File cached = FontStore.previewCached(this, fam);
        if (cached != null) {
            applyPreview(fam, cached, FontStore.previewComplete(cached));
            return;
        }
        previewBusy.add(fam);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                File f = null;
                boolean ok = true;
                try {
                    f = FontStore.preview(MainActivity.this, fam);
                    ok = FontStore.previewComplete(f);
                } catch (Throwable e) {
                    f = null;
                }
                final File ff = f;
                final boolean fok = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        previewBusy.remove(fam);
                        if (ff == null) previewFailed.add(fam);
                        else applyPreview(fam, ff, fok);
                    }
                });
            }
        });
    }

    private void applyPreview(String fam, File f, boolean complete) {
        try {
            Typeface tf = Typeface.createFromFile(f);
            faces.put(fam, tf);
            if (!complete) previewIncomplete.add(fam);
            TextView s = samples.get(fam);
            if (s != null) s.setTypeface(tf);
            TextView nm = names.get(fam);
            if (nm != null) nm.setText(label(fam, false));
        } catch (Throwable e) {
            previewFailed.add(fam);
        }
    }

    private void refreshCatalog(final Button b) {
        b.setEnabled(false);
        b.setText("Osvježavam…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                int n = 0;
                try {
                    n = FontStore.refreshCatalog(MainActivity.this);
                } catch (Throwable e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final String fe = err;
                final int fn = n;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        b.setEnabled(true);
                        b.setText("Osvježi spisak fontova sa Googlea");
                        rebuild();
                        Toast.makeText(MainActivity.this,
                                fe == null ? "Spisak osvježen: " + fn + " fontova" : "Greška: " + fe,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void download(final String fam) {
        busy.add(fam);
        rebuild();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    FontStore.download(MainActivity.this, fam);
                } catch (Throwable e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy.remove(fam);
                        rebuild();
                        Toast.makeText(MainActivity.this,
                                fe == null ? fam + ": preuzeto" : fam + ": " + fe, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
