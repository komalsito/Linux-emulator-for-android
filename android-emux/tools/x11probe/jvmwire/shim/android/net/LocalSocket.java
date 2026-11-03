package android.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.net.StandardProtocolFamily;

/**
 * JVM stand-in for android.net.LocalSocket, used ONLY by
 * tools/x11probe/jvmwire so the app's real X11Client.kt can be compiled and
 * executed on a desktop JVM against a real X server. It is not part of the APK
 * (the APK compiles against android.jar and runs the platform's own
 * LocalSocket); nothing under app/src/main ever sees this file.
 *
 * Implemented over JDK16+ AF_UNIX SocketChannel, which is what the app's
 * `<rootfs>/tmp/.X11-unix/XN` connection is on the device.
 */
public class LocalSocket {
    private SocketChannel ch;
    private InputStream in;
    private OutputStream out;
    private int soTimeout;

    public LocalSocket() {}

    public void connect(LocalSocketAddress addr) throws IOException {
        ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(addr.getPath()));
        in = Channels.newInputStream(ch);
        out = Channels.newOutputStream(ch);
    }

    public InputStream getInputStream() { return in; }

    public OutputStream getOutputStream() { return out; }

    public void setSoTimeout(int ms) { this.soTimeout = ms; }

    public int getSoTimeout() { return soTimeout; }

    /** The app sets a 256 KiB receive buffer on the unix transport too. */
    public void setReceiveBufferSize(int n) { this.rcvBuf = n; }

    public int getReceiveBufferSize() { return rcvBuf; }

    private int rcvBuf = 0;

    public void close() throws IOException {
        if (ch != null) ch.close();
    }
}
