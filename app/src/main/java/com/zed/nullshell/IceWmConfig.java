package com.zed.nullshell;

import android.content.Context;

import java.util.regex.Pattern;

/**
 * IceWM as the VNC desktop. NullShell never overwrites the user's IceWM files: each of ~/.icewm/preferences and
 * ~/.icewm/menu is written only when it does not exist yet, as a starting point for a phone screen (readable fonts, no
 * CPU/network graphs, a solid background and a start menu with the installed Kali GUI tools). After that the files belong
 * to the user, who can change them in Kali or replace them by applying a rice ({@link RiceManager}).
 */
final class IceWmConfig {
    private IceWmConfig() { }

    private static final int[] FONT_PT = {11, 13, 16};

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static String font(int pt, boolean bold) { return "\"sans:" + (bold ? "bold:" : "") + "size=" + pt + "\""; }

    /** The starting preferences. A theme set by an applied rice is used when it is installed; without one the background is solid. */
    private static String defaults(Context c, boolean withTheme) {
        int size = Prefs.vncUiSize(c);
        int pt = FONT_PT[size];
        StringBuilder p = new StringBuilder();
        p.append("# Written by NullShell once, because this file did not exist. It is yours now: NullShell never changes it again.\n");
        if (size > 0) {
            p.append("TitleBarHeight=").append(size == 1 ? 30 : 38).append('\n');
            p.append("BorderSizeX=").append(size == 1 ? 4 : 6).append("\nBorderSizeY=").append(size == 1 ? 4 : 6).append('\n');
            p.append("SmallIconSize=").append(size == 1 ? 24 : 32).append('\n');
        }
        p.append("TaskBarShowCPUStatus=0\nTaskBarShowNetStatus=0\nTaskBarShowMEMStatus=0\nTaskBarShowAPMStatus=0\n");
        p.append("TaskBarShowMailboxStatus=0\nTaskBarShowClock=1\nTaskBarShowWorkspaces=0\nTaskBarShowTray=1\n");
        p.append("TaskBarDoubleHeight=0\nTaskBarAutoHide=0\n");
        String normal = font(pt, false), bold = font(pt, true);
        for (String k : new String[]{"MenuFontNameXft", "StatusFontNameXft", "NormalTaskBarFontNameXft",
                "ToolButtonFontNameXft", "NormalButtonFontNameXft", "ListBoxFontNameXft", "ClockFontNameXft",
                "LabelFontNameXft", "InputFontNameXft", "ToolTipFontNameXft", "QuickSwitchFontNameXft"})
            p.append(k).append('=').append(normal).append('\n');
        for (String k : new String[]{"TitleFontNameXft", "ActiveTaskBarFontNameXft", "ActiveButtonFontNameXft"})
            p.append(k).append('=').append(bold).append('\n');
        if (!withTheme) p.append("DesktopBackgroundImage=\"\"\nDesktopBackgroundColor=\"rgb:20/24/2C\"\n");
        return p.toString();
    }

    /** A wallpaper path goes into quoted shell text, so it may not hold quotes, backslashes or newlines. */
    static boolean safeGuestPath(String p) {
        return p != null && p.startsWith("/") && p.length() < 512 && p.indexOf('"') < 0 && p.indexOf('\\') < 0 && p.indexOf('\'') < 0
                && p.indexOf('\n') < 0 && p.indexOf('\r') < 0;
    }

    /** Shell snippet for the VNC start script. Quoted heredocs, so nothing in the text is expanded by the shell. */
    static String script(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("mkdir -p \"$HOME/.icewm\"\n");
        String t = Prefs.vncTheme(c);
        if (!SAFE_NAME.matcher(t).matches()) t = "";
        s.append("NS_THEME=").append(ProotLauncher.quote(t)).append("\n");
        // a theme (from an applied rice) is used for the new file only if it is really there
        s.append("if [ -n \"$NS_THEME\" ] && [ ! -f \"$HOME/.icewm/themes/$NS_THEME/default.theme\" ] && ")
                .append("[ ! -f \"/usr/share/icewm/themes/$NS_THEME/default.theme\" ]; then NS_THEME=''; fi\n");

        // preferences: only when missing
        s.append("if [ ! -e \"$HOME/.icewm/preferences\" ]; then\n");
        s.append("  if [ -n \"$NS_THEME\" ]; then\n");
        s.append("    printf 'Theme=\"%s/default.theme\"\\n' \"$NS_THEME\" > \"$HOME/.icewm/preferences\"\n");
        s.append("    cat >> \"$HOME/.icewm/preferences\" <<'NS_ICE_PREF_THEME'\n").append(defaults(c, true)).append("NS_ICE_PREF_THEME\n");
        s.append("  else\n");
        s.append("    cat > \"$HOME/.icewm/preferences\" <<'NS_ICE_PREF_PLAIN'\n").append(defaults(c, false)).append("NS_ICE_PREF_PLAIN\n");
        s.append("  fi\n");
        s.append("fi\n");

        // start menu: only when missing
        s.append("if [ ! -e \"$HOME/.icewm/menu\" ]; then\n");
        s.append("{\n");
        s.append("  echo '# Written by NullShell once, because this file did not exist. The tools below appear once they are installed.'\n");
        s.append("  add() { command -v \"$2\" >/dev/null 2>&1 && printf 'prog \"%s\" - %s\\n' \"$1\" \"$3\"; }\n");
        s.append("  add 'Terminal' xterm 'xterm -fa Monospace -fs 11'\n");
        s.append("  add 'Files' thunar thunar\n");
        s.append("  add 'Files' pcmanfm pcmanfm\n");
        s.append("  add 'Web browser' firefox-esr firefox-esr\n");
        s.append("  add 'Text editor' mousepad mousepad\n");
        s.append("  echo 'separator'\n");
        s.append("  add 'Wireshark' wireshark wireshark\n");
        s.append("  add 'Burp Suite' burpsuite burpsuite\n");
        s.append("  add 'Zenmap' zenmap zenmap\n");
        s.append("  add 'Ettercap' ettercap 'ettercap -G'\n");
        s.append("  add 'GIMP' gimp gimp\n");
        s.append("  echo 'separator'\n");
        s.append("  command -v icewm-menu-fdo >/dev/null 2>&1 && echo 'menuprog \"All applications\" - icewm-menu-fdo'\n");
        s.append("  echo 'separator'\n");
        s.append("  echo 'prog \"Restart IceWM\" - icewm --restart'\n");
        s.append("} > \"$HOME/.icewm/menu\"\n");
        s.append("fi\n");
        s.append(DesktopWallpaper.script(c));
        return s.toString();
    }
}
