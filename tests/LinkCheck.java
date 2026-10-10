import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Loads and links every class of a mod jar against the game's jars (no game code is run). */
public class LinkCheck {
    public static void main(String[] args) throws Exception {
        File modJar = new File(args[0]);
        File core = new File(args[1]);
        List<URL> urls = new ArrayList<URL>();
        urls.add(modJar.toURI().toURL());
        for (File f : core.listFiles()) if (f.getName().endsWith(".jar")) urls.add(f.toURI().toURL());
        for (int i = 2; i < args.length; i++) urls.add(new File(args[i]).toURI().toURL());
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), LinkCheck.class.getClassLoader());
        int ok = 0, bad = 0;
        try (JarFile jf = new JarFile(modJar)) {
            Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (!n.endsWith(".class")) continue;
                String cn = n.replace('/', '.').substring(0, n.length() - 6);
                try {
                    Class<?> c = Class.forName(cn, false, cl);
                    c.getDeclaredMethods();
                    c.getDeclaredFields();
                    ok++;
                } catch (Throwable t) {
                    bad++;
                    System.out.println("FAIL " + cn + ": " + t);
                }
            }
        }
        System.out.println("linked " + ok + " classes, failures " + bad);
    }
}
