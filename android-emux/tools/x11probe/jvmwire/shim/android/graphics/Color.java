package android.graphics;

/**
 * JVM stand-in for android.graphics.Color — see ../net/LocalSocket.java for why
 * these exist. Only the members X11Client.kt actually touches are present, so
 * an older revision of the client that still called Color.rgb() per pixel
 * compiles and runs here too (and produces the same numbers the platform does).
 */
public class Color {
    public static int rgb(int red, int green, int blue) {
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    public static int red(int color) { return (color >> 16) & 0xFF; }

    public static int green(int color) { return (color >> 8) & 0xFF; }

    public static int blue(int color) { return color & 0xFF; }
}
