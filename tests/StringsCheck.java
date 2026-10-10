import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translation tables check:
 *  - every key referenced by the code exists in the English table,
 *  - every translation has exactly the English key set (plus optional desc.* keys),
 *  - placeholders (%s, %d, %%) match per key, and each string formats without error.
 * usage: java StringsCheck <modDir>
 */
public class StringsCheck {

    /** Keys the code builds at runtime (prefix + enum/constant name). */
    static final String[] DYNAMIC = {
        "terminal.tab_overview", "terminal.tab_loans", "terminal.tab_investments", "terminal.tab_credit", "terminal.tab_history",
        "bankruptcy.state.NONE", "bankruptcy.state.FILED", "bankruptcy.state.ACTIVE", "bankruptcy.state.RECOVERY",
        "notice.phase1.title", "notice.phase2.title", "notice.phase3.title", "notice.phase4.title",
        "tx.LOAN", "tx.PAYMENT", "tx.AUTOPAY", "tx.PAYOFF", "tx.CURED", "tx.MISSED", "tx.DEFAULT", "tx.GARNISH",
        "tx.SEIZURE", "tx.SEIZED", "tx.INVEST", "tx.WITHDRAW", "tx.EARLY_WITHDRAW", "tx.BANKRUPTCY",
        "tx.ENFORCEMENT", "tx.REFUSED", "tx.SOVEREIGN",
        "factor.payment", "factor.amounts", "factor.length", "factor.newCredit", "factor.mix",
        "rating.excellent", "rating.good", "rating.fair", "rating.poor",
        "reason.thinFile", "reason.seriousDelinquency", "reason.delinquency", "reason.publicRecord", "reason.pastDueNow",
        "reason.limitedPaymentHistory", "reason.balanceRatio", "reason.tooManyBalances", "reason.shortHistory",
        "reason.inquiries", "reason.newAccounts", "reason.recentOpening", "reason.mix",
        "terminal.credit.tipOnTime", "terminal.credit.tipLate", "terminal.credit.tipInquiries", "terminal.credit.tipAge",
        "terminal.credit.tipBalance", "terminal.credit.tipPayoff", "terminal.credit.tipMix",
    };
    static final String[] LOANS = {"EMERGENCY", "SMALL", "CORPORATE", "MEGACORP", "SOVEREIGN"};
    static final String[] INVESTMENTS = {"SAVINGS", "BONDS", "COMMODITIES", "VENTURE", "MILITARY"};

    public static void main(String[] args) throws Exception {
        File mod = new File(args[0]);
        File dir = new File(mod, "data/strings");
        Map<String, String> en = read(new File(dir, "bos_strings_en.json"));
        int problems = 0;

        Set<String> used = new TreeSet<String>(Arrays.asList(DYNAMIC));
        for (String l : LOANS) { used.add("loan." + l + ".name"); used.add("loan." + l + ".desc"); }
        for (String i : INVESTMENTS) { used.add("invest." + i + ".name"); used.add("invest." + i + ".desc"); }
        Pattern p = Pattern.compile("(?:Str\\.(?:get|f)\\(|heading\\(info, )\"([a-zA-Z0-9_.]+)\"(?=\\s*[),])");
        Pattern ternary = Pattern.compile("Str\\.get\\([^?]+\\? \"([a-zA-Z0-9_.]+)\" : \"([a-zA-Z0-9_.]+)\"\\)");
        for (File f : listJava(new File(mod, "src"))) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher m = p.matcher(src);
            while (m.find()) if (!m.group(1).endsWith(".")) used.add(m.group(1));
            Matcher t = ternary.matcher(src);
            while (t.find()) { used.add(t.group(1)); used.add(t.group(2)); }
        }
        for (String k : used) {
            if (!en.containsKey(k)) { System.out.println("FAIL missing English key used by code: " + k); problems++; }
        }

        File[] tables = dir.listFiles();
        for (File f : tables) {
            String n = f.getName();
            if (!n.startsWith("bos_strings_") || n.equals("bos_strings_en.json")) continue;
            Map<String, String> tr = read(f);
            for (String k : en.keySet()) {
                if (!tr.containsKey(k)) { System.out.println("FAIL " + n + " missing key " + k); problems++; continue; }
                if (!signature(en.get(k)).equals(signature(tr.get(k)))) {
                    System.out.println("FAIL " + n + " placeholders differ for " + k + ": " + signature(en.get(k)) + " vs " + signature(tr.get(k)));
                    problems++;
                }
            }
            for (String k : tr.keySet()) {
                if (!en.containsKey(k) && !k.startsWith("desc.")) { System.out.println("FAIL " + n + " has unknown key " + k); problems++; }
            }
            problems += formats(n, tr);
        }
        problems += formats("bos_strings_en.json", en);

        System.out.println("keys used by code: " + used.size() + ", English keys: " + en.size());
        System.out.println(problems == 0 ? "STRINGS OK" : "FAIL " + problems + " string problem(s)");
    }

    static int formats(String table, Map<String, String> m) {
        int bad = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            int n = 0;
            Matcher x = Pattern.compile("%[sd]").matcher(e.getValue());
            while (x.find()) n++;
            Object[] a = new Object[n];
            Arrays.fill(a, "1");
            try {
                String.format(e.getValue().replace("%d", "%s"), a);
            } catch (Exception ex) {
                System.out.println("FAIL " + table + " cannot format " + e.getKey() + ": " + ex);
                bad++;
            }
        }
        return bad;
    }

    static String signature(String s) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("%%|%[sd]").matcher(s);
        while (m.find()) sb.append(m.group()).append(' ');
        return sb.toString().trim();
    }

    static Map<String, String> read(File f) throws Exception {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) throw new IllegalStateException(f.getName() + " has a UTF-8 BOM");
        JSONObject j = new JSONObject(text);
        Map<String, String> out = new LinkedHashMap<String, String>();
        Iterator<?> it = j.keys();
        while (it.hasNext()) {
            String k = String.valueOf(it.next());
            out.put(k, j.getString(k));
        }
        return out;
    }

    static List<File> listJava(File dir) {
        List<File> out = new ArrayList<File>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory()) out.addAll(listJava(f));
            else if (f.getName().endsWith(".java")) out.add(f);
        }
        return out;
    }
}
