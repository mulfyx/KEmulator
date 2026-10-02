package tlsfixture;

/** Loaded and rewritten by the real MIDlet class loader. */
public final class ApiProbe {
    public static String load(String name) throws ClassNotFoundException {
        return Class.forName(name).getName();
    }
}
