package android.net;

/**
 * JVM stand-in for android.net.LocalSocketAddress — see LocalSocket.java in
 * this directory for why this exists and why it never reaches the APK.
 */
public class LocalSocketAddress {
    public enum Namespace { ABSTRACT, FILESYSTEM, RESERVED }

    private final String path;
    private final Namespace ns;

    public LocalSocketAddress(String path) { this(path, Namespace.ABSTRACT); }

    public LocalSocketAddress(String path, Namespace ns) {
        this.path = path;
        this.ns = ns;
    }

    public String getPath() { return path; }

    public String getName() { return path; }

    public Namespace getNamespace() { return ns; }
}
