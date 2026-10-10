package com.bankofstarsector.core;

import com.fs.starfarer.api.Global;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Player-facing text. Starsector has no language setting, so the mod ships its own tables:
 * data/strings/bos_strings_en.json (reference) and bos_strings_&lt;lang&gt;.json translations.
 * Missing keys fall back to English, then to the key itself.
 *
 * Language: BankSettings.LANGUAGE = "auto" (system locale), "en" or "pt_BR".
 */
public final class Str {

    private static final Logger log = Logger.getLogger(Str.class);
    private static final String DIR = "data/strings/";
    public static final String[] SUPPORTED = {"en", "pt_BR"};

    private static Map<String, String> english = new HashMap<String, String>();
    private static Map<String, String> active = english;
    private static String language = "en";

    private Str() {}

    public static void load(String setting) {
        english = read("en");
        String lang = resolve(setting);
        active = "en".equals(lang) ? english : read(lang);
        if (active.isEmpty()) {
            active = english;
            lang = "en";
        }
        language = lang;
        log.info("Bank of Starsector: language " + language + " (" + active.size() + " strings)");
    }

    static String resolve(String setting) {
        if (setting == null || setting.isEmpty() || "auto".equalsIgnoreCase(setting)) {
            Locale l = Locale.getDefault();
            if ("pt".equals(l.getLanguage())) return "pt_BR";
            return "en";
        }
        for (String s : SUPPORTED) if (s.equalsIgnoreCase(setting)) return s;
        return "en";
    }

    private static Map<String, String> read(String lang) {
        Map<String, String> out = new HashMap<String, String>();
        try {
            JSONObject j = Global.getSettings().loadJSON(DIR + "bos_strings_" + lang + ".json", BankModPlugin.MOD_ID);
            if (j == null) return out;
            Iterator<?> it = j.keys();
            while (it.hasNext()) {
                String k = String.valueOf(it.next());
                out.put(k, j.getString(k));
            }
        } catch (Exception e) {
            log.warn("Bank of Starsector: could not read strings for " + lang + ": " + e.getMessage());
        }
        return out;
    }

    /** descriptions.csv entries this mod owns: id, type, number of text fields. */
    private static final Object[][] DESCRIPTIONS = {
        {"pbc", com.fs.starfarer.api.loading.Description.Type.FACTION, 2},
        {"pbc_bullion", com.fs.starfarer.api.loading.Description.Type.PLANET, 1},
        {"pbc_vault", com.fs.starfarer.api.loading.Description.Type.PLANET, 1},
        {"pbc_ledger", com.fs.starfarer.api.loading.Description.Type.PLANET, 1},
    };
    private static final Map<String, String> originalDescriptions = new HashMap<String, String>();

    /**
     * descriptions.csv has no localization, so translated text1/text2 are swapped in at load.
     * The English originals are remembered so switching back to English restores them.
     */
    public static void applyDescriptions() {
        for (Object[] d : DESCRIPTIONS) {
            String id = (String) d[0];
            try {
                com.fs.starfarer.api.loading.Description desc = Global.getSettings().getDescription(id,
                    (com.fs.starfarer.api.loading.Description.Type) d[1]);
                if (desc == null) continue;
                for (int i = 1; i <= (Integer) d[2]; i++) {
                    String k = id + ".text" + i;
                    if (!originalDescriptions.containsKey(k)) {
                        originalDescriptions.put(k, i == 1 ? desc.getText1() : desc.getText2());
                    }
                    String key = "desc." + k;
                    String value = active.containsKey(key) ? active.get(key) : originalDescriptions.get(k);
                    if (i == 1) desc.setText1(value);
                    else desc.setText2(value);
                }
            } catch (Exception e) {
                log.warn("Bank of Starsector: could not localize description " + id + ": " + e.getMessage());
            }
        }
    }

    public static String language() {
        return language;
    }

    /** Text for key, untouched (may contain %s placeholders meant for addPara highlights). */
    public static String get(String key) {
        String v = active.get(key);
        if (v == null) v = english.get(key);
        return v != null ? v : key;
    }

    /** Text for key with %s / %d placeholders filled in. */
    public static String f(String key, Object... args) {
        try {
            return String.format(get(key), args);
        } catch (Exception e) {
            return get(key);
        }
    }
}
