import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Guards save compatibility (see docs/SAVE_COMPAT.md).
 *
 * Starsector saves the campaign with XStream, field by field, without running constructors, and only
 * ignores unknown elements for one field of its own. So for every class of this mod that ends up in a
 * save: removing or renaming a field, changing its type, or removing an enum constant breaks loading
 * old saves. This walks the saved object graph from its roots and compares it with
 * tests/save-fields.baseline. Missing or changed entries fail; new entries fail until the baseline is
 * updated (run test.ps1 -UpdateSaveBaseline after making the new field null-safe).
 */
public class SaveCompatCheck {

    private static final String PKG = "com.bankofstarsector.";

    /** Objects this mod puts into the save: persistent data, saved scripts and intel. */
    private static final String[] ROOTS = {
        "com.bankofstarsector.core.BankData",                        // sector persistent data
        "com.bankofstarsector.intel.BankingIntelPlugin",             // intel manager
        "com.bankofstarsector.intel.CollectionIntelPlugin",          // intel manager (0.1.x saves)
        "com.bankofstarsector.intel.LoanIntelPlugin",                // intel manager (0.1.x saves)
        "com.bankofstarsector.collection.CollectionFleetScript",     // sector script
        "com.bankofstarsector.faction.PBCPostInitScript",            // sector script
    };

    public static void main(String[] args) throws Exception {
        File baselineFile = new File(args[0]);
        boolean update = args.length > 1 && "update".equals(args[1]);

        Set<String> current = new TreeSet<String>();
        Set<Class<?>> seen = new LinkedHashSet<Class<?>>();
        Deque<Class<?>> todo = new ArrayDeque<Class<?>>();
        ClassLoader cl = SaveCompatCheck.class.getClassLoader();
        for (String r : ROOTS) todo.add(Class.forName(r, false, cl));

        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (!seen.add(c)) continue;
            if (c.isEnum()) {
                for (Object k : c.getEnumConstants()) current.add("const " + c.getName() + " " + ((Enum<?>) k).name());
                continue;
            }
            // XStream also writes inherited fields: walk superclasses that belong to this mod.
            for (Class<?> k = c; k != null && k.getName().startsWith(PKG); k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic()) continue;
                    current.add("field " + k.getName() + " " + f.getName() + " " + f.getGenericType().getTypeName());
                    collect(f.getGenericType(), todo);
                }
                if (k != c) seen.add(k);
            }
        }

        if (update) {
            Files.write(baselineFile.toPath(), current, StandardCharsets.UTF_8);
            System.out.println("SAVE BASELINE UPDATED: " + current.size() + " entries");
            return;
        }

        Set<String> baseline = new TreeSet<String>();
        for (String line : Files.readAllLines(baselineFile.toPath(), StandardCharsets.UTF_8)) {
            line = line.replace("﻿", "").trim();
            if (!line.isEmpty() && !line.startsWith("#")) baseline.add(line);
        }

        int problems = 0;
        for (String b : baseline) {
            if (!current.contains(b)) {
                System.out.println("FAIL removed or changed (breaks old saves): " + b);
                problems++;
            }
        }
        List<String> added = new ArrayList<String>();
        for (String c : current) if (!baseline.contains(c)) added.add(c);
        for (String a : added) {
            System.out.println("FAIL new saved entry, not in baseline: " + a);
            problems++;
        }
        if (!added.isEmpty()) {
            System.out.println("  New fields load as null/0 from old saves: initialize them lazily, then run test.ps1 -UpdateSaveBaseline.");
        }
        System.out.println("saved classes: " + seen.size() + ", entries: " + current.size());
        if (problems == 0) System.out.println("SAVE COMPAT OK");
    }

    private static void collect(Type t, Deque<Class<?>> todo) {
        if (t instanceof Class) {
            Class<?> c = (Class<?>) t;
            while (c.isArray()) c = c.getComponentType();
            if (c.getName().startsWith(PKG)) todo.add(c);
        } else if (t instanceof ParameterizedType) {
            collect(((ParameterizedType) t).getRawType(), todo);
            for (Type a : ((ParameterizedType) t).getActualTypeArguments()) collect(a, todo);
        } else if (t instanceof GenericArrayType) {
            collect(((GenericArrayType) t).getGenericComponentType(), todo);
        } else if (t instanceof WildcardType) {
            for (Type u : ((WildcardType) t).getUpperBounds()) collect(u, todo);
        }
    }
}
