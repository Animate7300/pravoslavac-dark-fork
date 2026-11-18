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
    private int shownCount;   // redova u listi
    private int totalCount;   // fontova koji se nude (bez izbacenih i bez onih kojima pregled pokaze da nemaju srpska slova)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Православац Дарк – фонтови");

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
        info.setText("Овдје преузимате ћириличне Гугл фонтове (интернет се користи само када притиснете „Преузми“).\n\n"
                + "Фонт за сваки текст бирате у апликацији Православац (или Житије светих): "
                + "Мени → Подешавања → Фонтови");
        info.setTextSize(15f);
        info.setTextColor(FG);
        info.setPadding(0, 0, 0, pad);
        root.addView(info);

        final Button refresh = new Button(this);
        refresh.setText("Освјежи списак фонтова са Гугла");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshCatalog(refresh);
            }
        });
        root.addView(refresh);

        final Button delAll = new Button(this);
        delAll.setText("Обриши све преузете фонтове");
        delAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (FontStore.installed(MainActivity.this).isEmpty()) {
                    Toast.makeText(MainActivity.this, "Нема преузетих фонтова", Toast.LENGTH_SHORT).show();
                    return;
                }
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Обрисати све преузете фонтове?")
                        .setMessage("Текстови који их користе вратиће се на оригинални фонт.")
                        .setPositiveButton("Обриши", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                FontStore.deleteAll(MainActivity.this);
                                rebuild();
                            }
                        })
                        .setNegativeButton("Одустани", null)
                        .show();
            }
        });
        root.addView(delAll);

        EditText search = new EditText(this);
        search.setHint("Претрага");
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
        FontStore.purgeIncomplete(this);   // ranije preuzeti fontovi bez slova ђ ћ џ љ њ... se uklanjaju
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
        java.util.Set<String> bad = FontStore.badPreviews(this);
        shownCount = 0;
        totalCount = 0;
        for (final String fam : all) {
            final boolean has = have.contains(fam);
            if (!has && bad.contains(fam)) continue;   // pregled je pokazao da nema sva srpska slova
            totalCount++;
            if (filter.length() > 0 && !fam.toLowerCase(java.util.Locale.ROOT).contains(filter)) continue;
            shownCount++;
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
            b.setText(working ? "…" : has ? "Обриши" : "Преузми");
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
        updateCount();
        list.post(new Runnable() {
            @Override
            public void run() {
                loadVisiblePreviews();
                checkAllPreviews();
            }
        });
    }

    /** Broj fontova koji još nisu provjereni (nisu preuzeti, nisu izbačeni, pregled još nije gotov). */
    private int pendingChecks() {
        java.util.List<String> have = FontStore.installed(this);
        java.util.Set<String> bad = FontStore.badPreviews(this);
        int n = 0;
        for (String fam : FontStore.catalog(this)) {
            if (have.contains(fam) || bad.contains(fam) || faces.containsKey(fam) || previewFailed.contains(fam)) continue;
            n++;
        }
        return n;
    }

    private void updateCount() {
        if (pendingChecks() > 0) {
            count.setText("Брисање фонтова који немају српска слова ћ, ђ, љ, њ, џ…");
        } else {
            count.setText(totalCount + " " + fontWord(totalCount) + " са српском ћирилицом.");
        }
    }

    /** Provjerava u pozadini sve fontove sa spiska (ne samo vidljive), da se nekompatibilni izbace što prije. */
    private void checkAllPreviews() {
        for (String fam : FontStore.catalog(this)) startPreview(fam);
        updateCount();
    }

    /** Pokreće učitavanje pregleda za redove koji su na ekranu (i za nekoliko redova ispod njih). */
    private void loadVisiblePreviews() {
        Rect r = new Rect();
        int n = list.getChildCount();
        java.util.List<String> todo = new java.util.ArrayList<String>();
        for (int i = 0; i < n; i++) {
            View row = list.getChildAt(i);
            if (!row.getGlobalVisibleRect(r)) continue;
            for (int k = i; k < Math.min(n, i + 4); k++) {
                Object t = list.getChildAt(k).getTag();
                if (t instanceof String && !todo.contains(t)) todo.add((String) t);
            }
        }
        // poslije petlje: startPreview moze ukloniti red (font bez srpskih slova), a to mijenja listu
        for (String fam : todo) startPreview(fam);
    }

    /** Font ciji pregled nema sva srpska slova nestaje sa spiska (i pamti se, da se ne nudi ni poslije). */
    private void hideFamily(String fam) {
        FontStore.markBad(this, fam);
        samples.remove(fam);
        names.remove(fam);
        faces.remove(fam);
        for (int i = 0; i < list.getChildCount(); i++) {
            if (fam.equals(list.getChildAt(i).getTag())) {
                list.removeViewAt(i);
                shownCount--;
                totalCount--;
                break;
            }
        }
        updateCount();
        list.post(new Runnable() {
            @Override
            public void run() {
                loadVisiblePreviews();
            }
        });
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
                        if (ff == null) {
                            previewFailed.add(fam);
                            updateCount();
                        } else applyPreview(fam, ff, fok);
                    }
                });
            }
        });
    }

    private void applyPreview(String fam, File f, boolean complete) {
        try {
            if (!complete) {
                hideFamily(fam);
                return;
            }
            Typeface tf = Typeface.createFromFile(f);
            faces.put(fam, tf);
            TextView s = samples.get(fam);
            if (s != null) s.setTypeface(tf);
            TextView nm = names.get(fam);
            if (nm != null) nm.setText(label(fam, false));
        } catch (Throwable e) {
            previewFailed.add(fam);
        }
        updateCount();
    }

    /** фонт / фонта / фонтова (уз број). */
    private static String fontWord(int n) {
        int m10 = n % 10;
        int m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "фонт";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "фонта";
        return "фонтова";
    }

    private void refreshCatalog(final Button b) {
        b.setEnabled(false);
        b.setText("Освјежавам…");
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
                        b.setText("Освјежи списак фонтова са Гугла");
                        rebuild();
                        Toast.makeText(MainActivity.this,
                                fe == null ? "Списак освјежен: " + totalCount + " " + fontWord(totalCount) + " са ћирилицом"
                                        : "Грешка: " + fe,
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
                                fe == null ? fam + ": преузето" : fam + ": " + fe, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
