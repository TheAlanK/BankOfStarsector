import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays Starsector's script class-loader policy (com.fs.starfarer.loading.scripts.B.loadClass,
 * recovered with javap from starfarer_obf.jar, 0.98a-RC8) over every class a mod jar references.
 */
public class SandboxCheck {

    static final Set<String> IO_ALLOWED = new HashSet<String>(Arrays.asList(
        "java.io.BufferedInputStream", "java.io.BufferedReader", "java.io.FilterInputStream",
        "java.io.InputStreamReader", "java.io.Reader", "java.io.Serializable", "java.io.InvalidClassException",
        "java.io.ObjectStreamException", "java.io.InputStream", "java.io.IOException", "java.io.PrintStream",
        "java.io.PrintWriter", "java.io.ByteArrayInputStream", "java.io.FilterOutputStream", "java.io.OutputStream",
        "java.io.Closeable", "java.io.Flushable", "java.nio.ByteBuffer", "java.nio.CharBuffer", "java.nio.IntBuffer",
        "java.io.StringReader", "java.io.FileReader", "java.lang.Class"));
    static final Set<String> REFLECT_ALLOWED = new HashSet<String>(Arrays.asList(
        "java.lang.reflect.AnnotatedElement", "java.lang.reflect.InvocationTargetException",
        "java.lang.reflect.Type", "java.lang.reflect.GenericDeclaration"));

    static String verdict(String n) {
        if ((n.startsWith("java.io") || n.startsWith("java.nio.file.File")) && !IO_ALLOWED.contains(n))
            return "File access and reflection are not allowed to scripts.";
        if (n.startsWith("javax.script")) return "javax.script access not allowed to scripts.";
        if (n.startsWith("java.util.prefs")) return "java preferences access not allowed to scripts.";
        if (n.startsWith("java.lang.reflect") && !REFLECT_ALLOWED.contains(n))
            return "File access and reflection are not allowed to scripts. (" + n + ")";
        if (n.equals("sun.reflect.misc.MethodUtil")) return "File access and reflection are not allowed to scripts. (" + n + ")";
        return null;
    }

    public static void main(String[] args) throws Exception {
        Pattern desc = Pattern.compile("L([a-zA-Z0-9_/$]+);");
        Map<String, Set<String>> bad = new TreeMap<String, Set<String>>();
        int classes = 0;
        Set<String> all = new TreeSet<String>();
        try (JarFile jf = new JarFile(args[0])) {
            Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) {
                JarEntry je = e.nextElement();
                if (!je.getName().endsWith(".class")) continue;
                classes++;
                Set<String> refs = new TreeSet<String>();
                for (String s : classRefsAndDescriptors(read(jf.getInputStream(je)))) {
                    if (s.startsWith("[") || s.contains(";") || s.startsWith("(")) {
                        Matcher m = desc.matcher(s);
                        while (m.find()) refs.add(m.group(1).replace('/', '.'));
                    } else {
                        refs.add(s.replace('/', '.'));
                    }
                }
                all.addAll(refs);
                for (String r : refs) {
                    String v = verdict(r);
                    if (v != null) {
                        if (!bad.containsKey(je.getName())) bad.put(je.getName(), new TreeSet<String>());
                        bad.get(je.getName()).add(r + "  ->  " + v);
                    }
                }
            }
        }
        System.out.println("classes scanned: " + classes + ", distinct referenced types: " + all.size());
        if (bad.isEmpty()) System.out.println("SANDBOX OK: no reference would be rejected by the script class loader");
        for (Map.Entry<String, Set<String>> b : bad.entrySet()) {
            System.out.println("BLOCKED in " + b.getKey());
            for (String s : b.getValue()) System.out.println("   " + s);
        }
    }

    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    /** CONSTANT_Class names plus every UTF8 entry that looks like a descriptor. */
    static List<String> classRefsAndDescriptors(byte[] b) throws Exception {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(b));
        d.readInt(); d.readUnsignedShort(); d.readUnsignedShort();
        int count = d.readUnsignedShort();
        String[] utf = new String[count];
        List<Integer> classIdx = new ArrayList<Integer>();
        List<String> out = new ArrayList<String>();
        for (int i = 1; i < count; i++) {
            int tag = d.readUnsignedByte();
            switch (tag) {
                case 1: utf[i] = d.readUTF(); break;
                case 7: classIdx.add(d.readUnsignedShort()); break;
                case 3: case 4: d.readInt(); break;
                case 5: case 6: d.readLong(); i++; break;
                case 8: case 16: case 19: case 20: d.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18: d.readInt(); break;
                case 15: d.readUnsignedByte(); d.readUnsignedShort(); break;
                default: throw new IllegalStateException("bad tag " + tag);
            }
        }
        for (int idx : classIdx) out.add(utf[idx]);
        for (String s : utf) if (s != null && (s.startsWith("(") || s.matches("\\[*L[a-zA-Z0-9_/$]+;"))) out.add(s);
        return out;
    }
}
