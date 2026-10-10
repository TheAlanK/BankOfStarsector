import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TooltipMakerAPI.addPara (intel screens, tooltips, confirm prompts) runs String.format on its
 * first argument; a pre-formatted or dynamic string containing '%' then crashes the game
 * ("Fatal: Conversion = 'r'"). TextPanelAPI.addPara (dialogs) does not format - verified in
 * com.fs.starfarer.ui.newui.String - so `text.` receivers are allowed.
 *
 * Rule: on info/prompt/tooltip receivers, the first argument must be a string literal or
 * Str.get(...) (raw translation formats, whose placeholders StringsCheck validates).
 * usage: java FormatSafetyCheck <srcDir>
 */
public class FormatSafetyCheck {
    public static void main(String[] args) throws Exception {
        Pattern call = Pattern.compile("\\b(info|prompt|tooltip|outer)\\.addPara\\(\\s*");
        int bad = 0, calls = 0;
        for (File f : list(new File(args[0]))) {
            String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = call.matcher(lines[i]);
                while (m.find()) {
                    calls++;
                    if (!safeFirstArg(lines[i].substring(m.end()))) {
                        System.out.println("FAIL " + f.getName() + ":" + (i + 1) + " dynamic text used as format: " + lines[i].trim());
                        bad++;
                    }
                }
            }
        }
        System.out.println("tooltip addPara calls checked: " + calls);
        System.out.println(bad == 0 ? "FORMAT SAFETY OK" : "FAIL " + bad + " unsafe addPara call(s)");
    }

    /** Safe: a plain string literal (not concatenated) or Str.get(...). */
    static boolean safeFirstArg(String rest) {
        if (rest.startsWith("Str.get(") || rest.startsWith("com.bankofstarsector.core.Str.get(")) return true;
        if (!rest.startsWith("\"")) return false;
        int i = 1;
        while (i < rest.length()) {
            char ch = rest.charAt(i);
            if (ch == '\\') { i += 2; continue; }
            if (ch == '"') break;
            i++;
        }
        String after = rest.substring(Math.min(rest.length(), i + 1)).trim();
        return !after.startsWith("+");
    }

    static List<File> list(File dir) {
        List<File> out = new ArrayList<File>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory()) out.addAll(list(f));
            else if (f.getName().endsWith(".java")) out.add(f);
        }
        return out;
    }
}
