package patch.kalendar;

/** Spisak Google fontova sa ćirilicom i zajedničke konstante (bez Xposed zavisnosti: koristi ga i aplikacija modula). */
final class FontCatalog {
    private FontCatalog() {}

    /** Paket (applicationId) aplikacije modula: iz njenih postavki Pravoslavac čita preuzete fontove. */
    static final String MODULE_APP_ID = "io.github.borko17.pravoslavac.dark";
    /** Aktivnost modula (spisak fontova za preuzimanje). */
    static final String MODULE_ACTIVITY = "patch.kalendar.MainActivity";
    /** Postavke modula: spisak preuzetih fontova ("list", odvojeni sa |) i verzije ("v_<slug>"). */
    static final String PREFS_INDEX = "fonts_index";
    /** Prefiks postavki jednog fonta: "reg" i "bold" (base64 TTF) i "ver". */
    static final String PREFS_FONT = "font_";
    /** Lazni direktorij asseta preko kojeg se fontovi serviraju WebView stranicama. */
    static final String WEB_DIR = "__pdkfonts__";

    /**
     * Fontovi sa ćirilicom (uključujući srpska slova). Preuzeti font se ionako provjerava (Ж Ђ Ћ Џ Ј Љ Њ),
     * pa pogrešan unos ovdje samo daje poruku o grešci pri preuzimanju.
     */
    static final String[] FAMILIES = {
            "Alegreya", "Alegreya Sans", "Alice", "Amatic SC", "Andika", "Anonymous Pro", "Arimo", "Arsenal",
            "Bad Script", "Bitter", "Caveat", "Comfortaa", "Commissioner", "Cormorant", "Cormorant Garamond",
            "Cousine", "Cuprum", "Didact Gothic", "EB Garamond", "Exo 2", "Fira Mono", "Fira Sans",
            "Fira Sans Condensed", "Forum", "Gentium Book Plus", "Gentium Plus", "Golos Text", "IBM Plex Mono",
            "IBM Plex Sans", "IBM Plex Serif", "Inter", "Istok Web", "JetBrains Mono", "Jost", "Jura", "Kurale",
            "Ledger", "Lexend", "Literata", "Lobster", "Lora", "Manrope", "Marck Script", "Marmelad",
            "Merriweather", "Merriweather Sans", "Montserrat", "Mulish", "Neucha", "Noto Sans", "Noto Serif",
            "Nunito", "Nunito Sans", "Old Standard TT", "Onest", "Open Sans", "Oranienbaum", "Oswald", "Pacifico",
            "Pangolin", "Philosopher", "Play", "Playfair Display", "Playfair Display SC", "Poiret One",
            "Prosto One", "PT Mono", "PT Sans", "PT Sans Narrow", "PT Serif", "Raleway", "Roboto",
            "Roboto Condensed", "Roboto Mono", "Roboto Slab", "Rubik", "Russo One", "Scada", "Source Code Pro",
            "Source Sans 3", "Source Serif 4", "Spectral", "Tenor Sans", "Tinos", "Ubuntu", "Ubuntu Condensed",
            "Underdog", "Vollkorn", "Yeseva One",
    };

    /** "Open Sans" -> "open_sans" (ime fajla i CSS familije). */
    static String slug(String family) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < family.length(); i++) {
            char c = Character.toLowerCase(family.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
        }
        return sb.toString();
    }

    /** Familija iz spiska sa datim slug-om, ili null. */
    static String bySlug(String slug) {
        for (String f : FAMILIES) {
            if (slug(f).equals(slug)) return f;
        }
        return null;
    }
}
