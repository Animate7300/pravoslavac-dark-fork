package patch.kalendar;

import android.app.Activity;
import android.content.Context;
import android.content.res.XResources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Calendar;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage, IXposedHookInitPackageResources {

    static final String PKG_PRAVOSLAVAC = "com.pravoslavac";
    static final String PKG_ZITIJE = "com.zitijesvetih";

    static String PKG = PKG_PRAVOSLAVAC;
    static String CAL_INIT = "n0";
    static String CAL_JUMP = "Y";

    static boolean select(String pkg) {
        if (PKG_PRAVOSLAVAC.equals(pkg)) {
            PKG = pkg;
            CAL_INIT = "n0";
            CAL_JUMP = "Y";
            return true;
        }
        if (PKG_ZITIJE.equals(pkg)) {
            PKG = pkg;
            CAL_INIT = "p0";
            CAL_JUMP = "a0";
            return true;
        }
        return false;
    }

    static final int LIGHT_ROW = 0xfffbf7ea;
    static final int DARK_RED = 0xffcc0000;

    static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    static boolean start;
    static boolean inSplash;

    // ========== PRILAGOĐENI FONT - KEŠ ==========
    private static android.graphics.Typeface customTypefaceRegular;
    private static android.graphics.Typeface customTypefaceBold;
    private static String fontBase64Regular;
    private static String fontBase64Bold;
    private static boolean fontLoadAttempted = false;

    private static android.graphics.Typeface getCustomTypeface(Context appContext, boolean isBold) {
        if (isBold && customTypefaceBold != null) return customTypefaceBold;
        if (!isBold && customTypefaceRegular != null) return customTypefaceRegular;
        
        if (!fontLoadAttempted) {
            fontLoadAttempted = true;
            XposedBridge.log("PravoslavacDark: Pokušavam da učitam font iz modula...");
        }

        try {
            Context moduleCtx = appContext.createPackageContext("io.github.borko17.pravoslavac.dark",
                    Context.CONTEXT_IGNORE_SECURITY);
            String fontName = isBold ? "fonts/noto_serif_bold.ttf" : "fonts/noto_serif_regular.ttf";
            
            String[] assets = moduleCtx.getAssets().list("fonts");
            if (assets != null) {
                XposedBridge.log("PravoslavacDark: Fajlovi u assets/fonts: " + java.util.Arrays.toString(assets));
            } else {
                XposedBridge.log("PravoslavacDark: Folder 'fonts' ne postoji u assets!");
            }

            android.graphics.Typeface tf = android.graphics.Typeface.createFromAsset(
                    moduleCtx.getAssets(), fontName);
            
            if (isBold) customTypefaceBold = tf;
            else customTypefaceRegular = tf;
            
            XposedBridge.log("PravoslavacDark: Uspešno učitan font: " + fontName);
            return tf;
        } catch (Exception e) {
            XposedBridge.log("PravoslavacDark: GREŠKA pri učitavanju fonta '" + (isBold ? "bold" : "regular") + "': " + e.getMessage());
            return null;
        }
    }

    private static String getFontBase64(Context appContext, boolean isBold) {
        if (isBold && fontBase64Bold != null) return fontBase64Bold;
        if (!isBold && fontBase64Regular != null) return fontBase64Regular;

        try {
            Context moduleCtx = appContext.createPackageContext("io.github.borko17.pravoslavac.dark",
                    Context.CONTEXT_IGNORE_SECURITY);
            String fontName = isBold ? "fonts/noto_serif_bold.ttf" : "fonts/noto_serif_regular.ttf";
            
            InputStream is = moduleCtx.getAssets().open(fontName);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[8192];
            int read;
            while ((read = is.read(data)) != -1) buffer.write(data, 0, read);
            is.close();
            
            String b64 = android.util.Base64.encodeToString(buffer.toByteArray(), android.util.Base64.NO_WRAP);
            if (isBold) fontBase64Bold = b64;
            else fontBase64Regular = b64;
            
            XposedBridge.log("PravoslavacDark: Uspešno konvertovan font u Base64: " + fontName);
            return b64;
        } catch (Exception e) {
            XposedBridge.log("PravoslavacDark: GREŠKA pri čitanju fonta za Base64 '" + (isBold ? "bold" : "regular") + "': " + e.getMessage());
            return "";
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!select(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;
        Prefs.init(lpparam.appInfo != null ? lpparam.appInfo.dataDir : null);
        Dark.reload();

        hook(PKG + ".Splashscreen", cl, "onCreate", "android.os.Bundle", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                start = Prefs.openCalendar();
                inSplash = Prefs.skipSplash();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                inSplash = false;
                if (Prefs.dark() && !Prefs.skipSplash()) {
                    try {
                        Activity a = (Activity) param.thisObject;
                        a.getWindow().setBackgroundDrawable(new ColorDrawable(0xff000000));
                        View content = a.findViewById(android.R.id.content);
                        if (content instanceof ViewGroup) {
                            ViewGroup g = (ViewGroup) content;
                            g.setBackgroundColor(0xff000000);
                            if (g.getChildCount() > 0) g.getChildAt(0).setBackgroundColor(0xff000000);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }
        });

        hook(PKG + ".SlidingTabsPravoslavniCrkveniKalendar_i_raspored_citanja_Fragment", cl, CAL_INIT,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (start) {
                            start = false;
                            Calendar c = Calendar.getInstance();
                            Integer month = Integer.valueOf(c.get(Calendar.MONTH) + 1);
                            Integer year = Integer.valueOf(c.get(Calendar.YEAR));
                            XposedHelpers.callMethod(param.thisObject, CAL_JUMP, new Object[] { month, year });
                        }
                    }
                });

        hook("android.os.Handler", cl, "postDelayed", "java.lang.Runnable", long.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inSplash) {
                    param.args[1] = Long.valueOf(0L);
                }
            }
        });

        hook("android.app.Activity", cl, "setContentView", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inSplash) {
                    param.setResult(null);
                }
            }
        });

        hook("android.webkit.WebViewClient", cl, "onPageStarted", "android.webkit.WebView", "java.lang.String",
                "android.graphics.Bitmap", STARTED);
        hook("android.webkit.WebViewClient", cl, "onPageFinished", "android.webkit.WebView", "java.lang.String",
                FINISHED);

        hook("android.webkit.WebView", cl, "setWebViewClient", "android.webkit.WebViewClient", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args[0] != null) hookClientClass(param.args[0].getClass());
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });

        try {
            XC_MethodHook webViewLoad = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        WebView wv = (WebView) param.thisObject;
                        if (Prefs.dark() || Prefs.left() || Prefs.useCustomFont()) wv.getSettings().setJavaScriptEnabled(true);
                        if (Prefs.dark()) wv.setBackgroundColor(Dark.WEB_BG);
                        Object a0 = param.args.length > 0 ? param.args[0] : null;
                        boolean js = a0 instanceof String && ((String) a0).startsWith("javascript:");
                        if (!js) hideWebView(wv);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            };
            Class<?> webView = XposedHelpers.findClass("android.webkit.WebView", cl);
            XposedBridge.hookAllMethods(webView, "loadUrl", webViewLoad);
            XposedBridge.hookAllMethods(webView, "loadDataWithBaseURL", webViewLoad);
            XposedBridge.hookAllMethods(webView, "loadData", webViewLoad);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        hook("android.webkit.WebView", cl, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (Prefs.dark()) ((WebView) param.thisObject).setBackgroundColor(Dark.WEB_BG);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });

        hookTheme(cl);
        hookInflater(cl);

        hook("android.view.View", cl, "setBackgroundResource", int.class, new OddRowHook());

        hook("android.view.View", cl, "setBackgroundColor", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Prefs.dark()) return;
                try {
                    int c = ((Integer) param.args[0]).intValue();
                    if (c == LIGHT_ROW) {
                        param.args[0] = Integer.valueOf(Dark.ROW);
                    } else if (Dark.isSunBg(c)) {
                        Dark.markSunday((View) param.thisObject);
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });

        hook("android.widget.AbsListView", cl, "obtainView", int.class, boolean[].class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Prefs.dark()) return;
                try {
                    Object r = param.getResult();
                    if (!(r instanceof View)) return;
                    Object lv = param.thisObject;
                    if (lv instanceof android.widget.ExpandableListView) {
                        Dark.styleMenu((View) r, true);
                        Dark.fixMenuDividers((android.widget.ExpandableListView) lv);
                    } else if (lv instanceof View && ((View) lv).getTag(Dark.TAG_BOOKLIST) != null) {
                        Dark.styleBookItem((View) r);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });

        XC_MethodHook imgHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!Prefs.dark()) return;
                try {
                    Dark.onImageChanged((android.widget.ImageView) param.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        hook("android.widget.ImageView", cl, "setImageDrawable", android.graphics.drawable.Drawable.class, imgHook);
        hook("android.widget.ImageView", cl, "setImageResource", int.class, imgHook);

        hook("android.widget.TextView", cl, "setTextColor", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Prefs.dark()) return;
                try {
                    if (Dark.applying) return;
                    int role = Dark.roleColor((View) param.thisObject);
                    if (role != 0) {
                        param.args[0] = Integer.valueOf(role);
                        return;
                    }
                    int c = ((Integer) param.args[0]).intValue();
                    if (c == DARK_RED) {
                        ((View) param.thisObject).setTag(Dark.TAG_ORIG, Integer.valueOf(c));
                        param.args[0] = Integer.valueOf(Dark.HOLIDAY);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });

        hookText(cl);
        hookCss(cl);
        hookSettingsMenu(cl);

        hook("android.widget.TextView", cl, "setJustificationMode", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Prefs.left()) param.args[0] = Integer.valueOf(0);
            }
        });

        // ========== PRILAGOĐENI FONT - NATIVNI TEXTVIEW ==========
        hook("android.widget.TextView", cl, "setTypeface", android.graphics.Typeface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!Prefs.useCustomFont()) return;
                try {
                    android.widget.TextView tv = (android.widget.TextView) param.thisObject;
                    android.graphics.Typeface original = (android.graphics.Typeface) param.args[0];
                    int style = original != null ? original.getStyle() : android.graphics.Typeface.NORMAL;
                    boolean isBold = (style & android.graphics.Typeface.BOLD) != 0;
                    android.graphics.Typeface custom = getCustomTypeface(tv.getContext(), isBold);
                    if (custom != null) {
                        param.args[0] = android.graphics.Typeface.create(custom, style);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    private static final int TAG_HIDE = 0x7e0c0b01;
    private static final int TAG_GEN = 0x7e0c0b02;

    static int generation(WebView wv) {
        Object g = wv.getTag(TAG_GEN);
        return g instanceof Integer ? ((Integer) g).intValue() : 0;
    }

    static void hideWebView(final WebView wv) {
        if (!Prefs.dark() && !Prefs.left() && !Prefs.useCustomFont()) return;
        try {
            Object old = wv.getTag(TAG_HIDE);
            if (old instanceof Runnable) wv.removeCallbacks((Runnable) old);
            wv.setTag(TAG_GEN, Integer.valueOf(generation(wv) + 1));
            wv.setAlpha(0f);
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    wv.setAlpha(1f);
                }
            };
            wv.setTag(TAG_HIDE, r);
            wv.postDelayed(r, 1500);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    static void revealAfterDraw(final WebView wv, final int gen) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                wv.postVisualStateCallback(System.nanoTime(), new WebView.VisualStateCallback() {
                    @Override
                    public void onComplete(long requestId) {
                        if (generation(wv) == gen) showWebView(wv);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        wv.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation(wv) == gen) showWebView(wv);
            }
        }, 80);
    }

    static void showWebView(WebView wv) {
        try {
            Object old = wv.getTag(TAG_HIDE);
            if (old instanceof Runnable) wv.removeCallbacks((Runnable) old);
            wv.setAlpha(1f);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void hook(String cls, ClassLoader cl, String method, Object... paramsAndCallback) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, paramsAndCallback);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void hookTheme(ClassLoader cl) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                applyTheme((Activity) param.thisObject);
            }
        };
        hook("android.app.Activity", cl, "onCreate", "android.os.Bundle", h);
        hook("androidx.fragment.app.FragmentActivity", cl, "onCreate", "android.os.Bundle", h);
    }

    private static void applyTheme(Activity a) {
        if (!Prefs.dark()) return;
        if (!a.getClass().getName().startsWith(PKG + ".")) return;
        try {
            int id = Dark.light() ? 0 : a.getResources().getIdentifier("Theme.AppCompat", "style", PKG);
            if (id != 0) a.setTheme(id);
            a.getWindow().setBackgroundDrawable(new ColorDrawable(Dark.BG));
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void hookInflater(ClassLoader cl) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                DEPTH.get()[0]++;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int[] d = DEPTH.get();
                d[0]--;
                if (d[0] == 0 && Prefs.dark()) {
                    Object r = param.getResult();
                    if (r instanceof View) {
                        Dark.walk((View) r);
                        if (param.args.length > 1 && param.args[1] instanceof android.widget.ExpandableListView) {
                            Dark.styleMenu((View) r, false);
                        }
                    }
                }
            }
        };
        hook("android.view.LayoutInflater", cl, "inflate", int.class, "android.view.ViewGroup", boolean.class, h);
        hook("android.view.LayoutInflater", cl, "inflate", "org.xmlpull.v1.XmlPullParser", "android.view.ViewGroup",
                boolean.class, h);
    }

    private static void hookText(ClassLoader cl) {
        hook("android.widget.TextView", cl, "setText", CharSequence.class, "android.widget.TextView$BufferType",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Prefs.dark()) return;
                        try {
                            Object o = param.args[0];
                            if (o instanceof CharSequence) {
                                param.args[0] = Dark.fixText((android.widget.TextView) param.thisObject,
                                        (CharSequence) o);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
    }

    private static final XC_MethodHook STARTED = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            hideWebView((WebView) param.args[0]);
        }
    };

    private static final XC_MethodHook FINISHED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            final WebView wv = (WebView) param.args[0];
            try {
                final int gen = generation(wv);
                final android.webkit.ValueCallback<String> reveal = new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        revealAfterDraw(wv, gen);
                    }
                };
                if (Prefs.left()) wv.evaluateJavascript(Assets.JUSTIFY_JS, null);
                wv.evaluateJavascript(Prefs.dark() ? Assets.darkJs() : "1", reveal);
            } catch (Throwable t) {
                XposedBridge.log(t);
                showWebView(wv);
            }
        }
    };

    private static final XC_MethodHook INTERCEPT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!Prefs.dark() && !Prefs.left() && !Prefs.useCustomFont()) return;
            try {
                if (!(param.args[0] instanceof WebView) || !(param.args[1] instanceof WebResourceRequest)) return;
                String path = ((WebResourceRequest) param.args[1]).getUrl().getPath();
                String marker = "/android_asset/";
                if (path == null || !path.contains(marker)) return;
                String lower = path.toLowerCase(java.util.Locale.ROOT);
                boolean css = lower.endsWith(".css") && path.contains("/android_asset/Content/Style/");
                boolean html = lower.endsWith(".html") || lower.endsWith(".htm");
                if (!css && !html) return;
                WebView wv = (WebView) param.args[0];
                String asset = path.substring(path.indexOf(marker) + marker.length());
                InputStream in = wv.getContext().getAssets().open(asset);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                in.close();
                byte[] data = out.toByteArray();
                if (css) {
                    param.setResult(new WebResourceResponse("text/css", "UTF-8",
                            new ByteArrayInputStream(patchCss(data, wv.getContext()))));
                } else {
                    String[] enc = new String[1];
                    byte[] page = patchHtml(data, enc, wv.getContext());
                    param.setResult(new WebResourceResponse("text/html", enc[0], new ByteArrayInputStream(page)));
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static String leftify(String s) {
        return s.replaceAll("(?i)text-aligns*:s*justify", "text-align:left");
    }

    private static byte[] patchCss(byte[] data, Context ctx) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (Prefs.left()) {
            data = leftify(new String(data, "ISO-8859-1")).getBytes("ISO-8859-1");
        }
        out.write(data);
        if (Prefs.left()) out.write(("\n" + Assets.LEFT_CSS).getBytes("UTF-8"));
        if (Prefs.dark()) out.write(("\n" + Assets.darkCss()).getBytes("UTF-8"));
        if (Prefs.useCustomFont() && ctx != null) {
            out.write(("\n" + buildFontCss(ctx)).getBytes("UTF-8"));
        }
        return out.toByteArray();
    }

    private static byte[] patchHtml(byte[] data, String[] encOut, Context ctx) throws Exception {
        String s = new String(data, "ISO-8859-1");
        String enc = "UTF-8";
        boolean bom = data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB
                && (data[2] & 0xff) == 0xBF;
        if (!bom) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)charsets*=s*([w-]+)")
                    .matcher(s.substring(0, Math.min(s.length(), 2048)));
            if (m.find()) enc = m.group(1);
        }
        encOut[0] = enc;
        if (Prefs.left()) s = leftify(s);
        StringBuilder css = new StringBuilder();
        if (Prefs.left()) css.append(Assets.LEFT_CSS);
        if (Prefs.dark()) css.append(Assets.darkCss());
        if (Prefs.useCustomFont() && ctx != null) {
            css.append(buildFontCss(ctx));
        }
        String tag = "<style id=\"__dkcss0\">" + css + "</style>";
        if (Prefs.dark()) tag += "<script>" + Assets.navJs() + "</script>";
        java.util.regex.Matcher he = java.util.regex.Pattern.compile("(?i)</head>").matcher(s);
        int at;
        if (he.find()) {
            at = he.start();
        } else {
            java.util.regex.Matcher bo = java.util.regex.Pattern.compile("(?i)<body[^>]*>").matcher(s);
            at = bo.find() ? bo.end() : (bom ? 3 : 0);
        }
        s = s.substring(0, at) + tag + s.substring(at);
        return s.getBytes("ISO-8859-1");
    }

    private static String buildFontCss(Context ctx) {
        StringBuilder sb = new StringBuilder();
        String b64Regular = getFontBase64(ctx, false);
        String b64Bold = getFontBase64(ctx, true);
        if (!b64Regular.isEmpty()) {
            sb.append("@font-face { font-family: 'PravoslavacCustom'; src: url(data:font/ttf;base64,")
              .append(b64Regular).append("); font-weight: normal; font-style: normal; }\n");
        }
        if (!b64Bold.isEmpty()) {
            sb.append("@font-face { font-family: 'PravoslavacCustom'; src: url(data:font/ttf;base64,")
              .append(b64Bold).append("); font-weight: bold; font-style: normal; }\n");
        }
        sb.append("body, p, div, span, h1, h2, h3, h4, h5, h6, li, td, th, a, blockquote { ")
          .append("font-family: 'PravoslavacCustom', 'Noto Serif', Georgia, serif !important; }\n");
        return sb.toString();
    }

    private static final java.util.Set<Class<?>> HOOKED_CLIENTS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Class<?>>());

    static void hookClientClass(Class<?> c) {
        if (c == null || c == android.webkit.WebViewClient.class || !HOOKED_CLIENTS.add(c)) return;
        try {
            XposedBridge.hookAllMethods(c, "shouldInterceptRequest", INTERCEPT);
            XposedBridge.hookAllMethods(c, "onPageStarted", STARTED);
            XposedBridge.hookAllMethods(c, "onPageFinished", FINISHED);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void hookCss(ClassLoader cl) {
        hook("android.webkit.WebViewClient", cl, "shouldInterceptRequest", "android.webkit.WebView",
                "android.webkit.WebResourceRequest", INTERCEPT);
    }

    private static final class OddRowHook extends XC_MethodHook {
        private int oddId = -1;
        private int evenId = -1;
        private boolean handled;

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            handled = false;
            if (!Prefs.dark()) return;
            try {
                View v = (View) param.thisObject;
                if (oddId == -1) {
                    oddId = v.getResources().getIdentifier("tablerow_border_neparni_dani", "drawable", PKG);
                    evenId = v.getResources().getIdentifier("tablerow_border_parni_dani", "drawable", PKG);
                }
                int id = ((Integer) param.args[0]).intValue();
                boolean odd = oddId > 0 && id == oddId;
                boolean even = evenId > 0 && id == evenId;
                if (odd || even) {
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.RECTANGLE);
                    if (odd) g.setColor(Dark.ROW);
                    float density = v.getResources().getDisplayMetrics().density;
                    int w = Math.max(1, Math.round(density * 3f));
                    g.setStroke(w, Dark.ROW_BORDER);
                    v.setBackground(g);
                    param.setResult(null);
                    handled = true;
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!Prefs.dark()) return;
            if (handled) {
                handled = false;
                return;
            }
            try {
                Dark.fixBackground((View) param.thisObject);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    private static final java.util.Set<String> REPLACED =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    private static void rc(XResources res, String name, int color) {
        REPLACED.add(name);
        try {
            res.setReplacement(PKG, "color", name, Integer.valueOf(color));
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static XResources RES;

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam)
            throws Throwable {
        if (!select(resparam.packageName)) return;
        RES = resparam.res;
        reapplyResources();
    }

    static void reapplyResources() {
        XResources res = RES;
        if (res == null || !Prefs.dark()) return;
        Dark.reload();

        rc(res, "primary_material_dark", Dark.BAR);
        rc(res, "primary_dark_material_dark", Dark.BAR_DARK);
        rc(res, "accent_material_dark", Dark.ACCENT);
        rc(res, "colorPrimary", Dark.BAR);
        rc(res, "colorPrimaryDark", Dark.BAR_DARK);
        rc(res, "colorAccent", Dark.ACCENT);
        rc(res, "viewpager_tab_color", Dark.BAR);
        rc(res, "list_background", Dark.SURFACE);
        rc(res, "list_divider", Dark.DIVIDER);
        rc(res, "textcolormain", Dark.TEXT);
        rc(res, "background_material_light", Dark.BG);
        rc(res, "background_floating_material_light", Dark.SURFACE);
        rc(res, "my_color_light", Dark.ARROW_PRESSED);

        try {
            res.setReplacement(PKG, "drawable", "paper", new XResources.DrawableLoader() {
                @Override
                public Drawable newDrawable(XResources r, int id) throws Throwable {
                    if (!Prefs.dark()) return null;
                    return new ColorDrawable(Dark.BG);
                }
            });
            res.setReplacement(PKG, "drawable", "customscroll", new XResources.DrawableLoader() {
                @Override
                public Drawable newDrawable(XResources r, int id) throws Throwable {
                    if (!Prefs.dark()) return null;
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.RECTANGLE);
                    g.setColor(Dark.SCROLL);
                    g.setCornerRadius(4f * r.getDisplayMetrics().density);
                    return g;
                }
            });
            res.setReplacement(PKG, "drawable", "gradient_bg", new XResources.DrawableLoader() {
                @Override
                public Drawable newDrawable(XResources r, int id) throws Throwable {
                    if (!Prefs.dark()) return null;
                    if (Dark.light()) {
                        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[] { Dark.mix(Dark.BG, Dark.TEXT, 0.05f), Dark.mix(Dark.BG, Dark.TEXT, 0.12f) });
                    }
                    return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[] { 0xff262626, 0xff1c1c1c });
                }
            });
            res.setReplacement(PKG, "drawable", "gradient_bg_hover", new XResources.DrawableLoader() {
                @Override
                public Drawable newDrawable(XResources r, int id) throws Throwable {
                    if (!Prefs.dark()) return null;
                    if (Dark.light()) {
                        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                                new int[] { Dark.mix(Dark.BG, Dark.ACCENT, 0.18f), Dark.mix(Dark.BG, Dark.ACCENT, 0.28f) });
                    }
                    return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[] { 0xff2e3f5e, 0xff243350 });
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    static boolean restoreResources(Context ctx) {
        XResources res = RES;
        if (res == null || REPLACED.isEmpty()) return true;
        boolean all = true;
        android.content.res.Resources plain = null;
        try {
            android.content.res.Resources cur = ctx.getResources();
            plain = new android.content.res.Resources(cur.getAssets(), cur.getDisplayMetrics(),
                    cur.getConfiguration());
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
        java.util.List<String> names = new java.util.ArrayList<String>(REPLACED);
        for (String name : names) {
            try {
                int id = plain.getIdentifier(name, "color", PKG);
                if (id == 0) continue;
                res.setReplacement(PKG, "color", name, Integer.valueOf(plain.getColor(id)));
            } catch (Throwable t) {
                all = false;
                XposedBridge.log(t);
            }
        }
        return all;
    }

    static final String MENU_TITLE = "Подешавања";

    private static String groupName(Object g) {
        if (g == null) return null;
        if (g instanceof String) return (String) g;
        try {
            Object n = XposedHelpers.getObjectField(g, "b");
            return n != null ? n.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void addMenuGroup(Object activity) throws Throwable {
        android.widget.ExpandableListView lv =
                (android.widget.ExpandableListView) XposedHelpers.getObjectField(activity, "H0");
        android.widget.ExpandableListAdapter ad = lv.getExpandableListAdapter();
        java.util.List<Object> list = (java.util.List<Object>) XposedHelpers.getObjectField(ad, "e");
        for (Object g : list) {
            if (MENU_TITLE.equals(groupName(g))) return;
        }
        java.lang.reflect.Constructor<?> ctor = list.get(0).getClass().getDeclaredConstructor();
        ctor.setAccessible(true);
        Object g = ctor.newInstance();
        java.lang.reflect.Field f = g.getClass().getDeclaredField("b");
        f.setAccessible(true);
        f.set(g, MENU_TITLE);
        list.add(g);
        hookGroupIcon(ad.getClass());
        if (ad instanceof android.widget.BaseExpandableListAdapter) {
            ((android.widget.BaseExpandableListAdapter) ad).notifyDataSetChanged();
        }
    }

    private static boolean groupIconHooked;

    private static synchronized void hookGroupIcon(Class<?> adapterClass) {
        if (groupIconHooked) return;
        groupIconHooked = true;
        XC_MethodHook menuRowHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object r = param.getResult();
                if (r instanceof View) Dark.styleMenu((View) r, false);
            }
        };
        for (Class<?> c = adapterClass; c != null && c != Object.class && !c.getName().startsWith("android.");
                c = c.getSuperclass()) {
            XposedBridge.hookAllMethods(c, "getRealChildView", menuRowHook);
            XposedBridge.hookAllMethods(c, "getChildView", menuRowHook);
            XposedBridge.hookAllMethods(c, "getGroupView", menuRowHook);
        }
        XposedBridge.hookAllMethods(adapterClass, "getGroupView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    int pos = ((Integer) param.args[0]).intValue();
                    android.widget.BaseExpandableListAdapter ad =
                            (android.widget.BaseExpandableListAdapter) param.thisObject;
                    if (!MENU_TITLE.equals(groupName(ad.getGroup(pos)))) return;
                    View v = (View) param.getResult();
                    int id = v.getResources().getIdentifier("tag_img_group", "id", PKG);
                    View iv = id != 0 ? v.findViewById(id) : null;
                    if (iv instanceof android.widget.ImageView) {
                        int px = Math.round(32f * v.getResources().getDisplayMetrics().density);
                        ((android.widget.ImageView) iv).setImageDrawable(new GearDrawable(px));
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });
    }

    private static void hookSettingsMenu(final ClassLoader cl) {
        hook(PKG + ".MainActivity", cl, "onCreate", "android.os.Bundle", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    addMenuGroup(param.thisObject);
                    SettingsUi.reopenIfPending((Activity) param.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        });

        hook("android.widget.ExpandableListView", cl, "setOnGroupClickListener",
                android.widget.ExpandableListView.OnGroupClickListener.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        final android.widget.ExpandableListView.OnGroupClickListener orig =
                                (android.widget.ExpandableListView.OnGroupClickListener) param.args[0];
                        if (orig == null) return;
                        param.args[0] = new android.widget.ExpandableListView.OnGroupClickListener() {
                            @Override
                            public boolean onGroupClick(android.widget.ExpandableListView parent, View v,
                                                        int groupPosition, long id) {
                                try {
                                    Object g = parent.getExpandableListAdapter().getGroup(groupPosition);
                                    if (MENU_TITLE.equals(groupName(g)) && parent.getContext() instanceof Activity) {
                                        Activity a = (Activity) parent.getContext();
                                        closeDrawer(a);
                                        SettingsUi.show(a);
                                        return true;
                                    }
                                    if (parent.getExpandableListAdapter().getChildrenCount(groupPosition) == 0
                                            && parent.getContext() instanceof Activity) {
                                        SettingsUi.dismiss((Activity) parent.getContext());
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                                return orig.onGroupClick(parent, v, groupPosition, id);
                            }
                        };
                    }
                });

        hook("android.widget.ExpandableListView", cl, "setOnChildClickListener",
                android.widget.ExpandableListView.OnChildClickListener.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        final android.widget.ExpandableListView.OnChildClickListener orig =
                                (android.widget.ExpandableListView.OnChildClickListener) param.args[0];
                        if (orig == null) return;
                        param.args[0] = new android.widget.ExpandableListView.OnChildClickListener() {
                            @Override
                            public boolean onChildClick(android.widget.ExpandableListView parent, View v,
                                                        int groupPosition, int childPosition, long id) {
                                try {
                                    if (parent.getContext() instanceof Activity) {
                                        SettingsUi.dismiss((Activity) parent.getContext());
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                                return orig.onChildClick(parent, v, groupPosition, childPosition, id);
                            }
                        };
                    }
                });

        XC_MethodHook back = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (SettingsUi.onBack((Activity) param.thisObject)) param.setResult(null);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        hook(PKG + ".MainActivity", cl, "onBackPressed", back);
        hook("androidx.activity.ComponentActivity", cl, "onBackPressed", back);
        hook("android.app.Activity", cl, "onBackPressed", back);
    }

    static void closeDrawer(Activity a) {
        try {
            View root = a.getWindow().getDecorView();
            View d = findByClass(root, "DrawerLayout");
            if (d == null) return;
            Class<?> c = d.getClass();
            try {
                c.getMethod("closeDrawers").invoke(d);
                return;
            } catch (NoSuchMethodException e) {
            }
            try {
                c.getMethod("d", boolean.class).invoke(d, Boolean.FALSE);
                return;
            } catch (NoSuchMethodException e) {
            }
            c.getMethod("c").invoke(d);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static View findByClass(View v, String simpleName) {
        for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals(simpleName)) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByClass(g.getChildAt(i), simpleName);
                if (r != null) return r;
            }
        }
        return null;
    }
}