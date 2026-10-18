package com.zed.nullshell;

import android.content.Context;

/**
 * The user's own wallpaper, for both desktops. It is set with feh by the start script every time the desktop starts, so it
 * does not depend on (or touch) any config file in Kali.
 */
final class DesktopWallpaper {
    private DesktopWallpaper() { }

    static final String[] NAMES = {"Fill the screen (crop to fit)", "Stretch", "Centre", "Tile"};
    private static final String[] FEH_MODE = {"--bg-fill", "--bg-scale", "--bg-center", "--bg-tile"};

    /** Shell snippet for the start script; empty when no wallpaper was chosen. */
    static String script(Context c) {
        String wp = Prefs.vncWallpaperPath(c);
        if (wp == null || wp.isEmpty()) return "";
        if (!IceWmConfig.safeGuestPath(wp)) return "echo 'Wallpaper skipped: the file name has characters that cannot be used.'\n";
        String mode = FEH_MODE[Prefs.vncWallpaperFit(c)];
        StringBuilder s = new StringBuilder();
        s.append("NS_WP=").append(ProotLauncher.quote(wp)).append("\n");
        s.append("if [ ! -f \"$NS_WP\" ]; then echo \"Wallpaper skipped: $NS_WP was not found.\"\n");
        s.append("elif ! command -v feh >/dev/null 2>&1; then echo 'Wallpaper skipped: feh is not installed (Install extras on the VNC server screen).'\n");
        // twice: the window manager may paint its own background just after it starts
        s.append("else ( sleep 2; feh --no-fehbg ").append(mode).append(" \"$NS_WP\"; sleep 4; feh --no-fehbg ").append(mode)
                .append(" \"$NS_WP\" ) >/dev/null 2>&1 &\n");
        s.append("fi\n");
        return s.toString();
    }
}
