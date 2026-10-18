package com.zed.nullshell;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Openbox as the VNC desktop, with tint2 as its panel. Openbox has no taskbar of its own, and there is no Alt+Tab on a
 * touchscreen, so tint2 supplies the window buttons, system tray and clock, plus a "Menu" button that opens Openbox's
 * root menu (the same as a right-click on the desktop, which is awkward to do from a phone).
 *
 * <p>NullShell never overwrites the user's Openbox files. At each start a small shell snippet writes rc.xml (the system
 * default, so the mouse and key bindings are kept, with the font size and a Super+Space menu key added), the menu, an
 * autostart file and the tint2 panel, but each one only if it does not exist yet. After that the files belong to the user,
 * who can change them in Kali (obconf, lxappearance) or replace them by applying a rice ({@link RiceManager}). The panel
 * takes its colours from the window theme at the moment it is first written. The user's wallpaper is set with feh at every
 * start ({@link DesktopWallpaper}) without touching any file.
 */
final class OpenboxConfig {
    private OpenboxConfig() { }

    /** Needed in addition to the VNC server packages. xdotool only powers the panel's Menu button, feh the wallpaper. */
    static final String PACKAGES = "openbox tint2 xdotool feh";

    private static final int[] FONT_PT = {11, 13, 16};
    private static final int[] PANEL_HEIGHT = {40, 52, 64};

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static File userThemes(Context c) { return new File(new File(RootfsManager.dir(c), "root"), ".themes"); }
    private static File systemThemes(Context c) { return new File(RootfsManager.dir(c), "usr/share/themes"); }

    // ------------------------------------------------------------------ themes

    private static File themerc(Context c, String name) {
        File f = new File(userThemes(c), name + "/openbox-3/themerc");
        return f.isFile() ? f : new File(systemThemes(c), name + "/openbox-3/themerc");
    }

    // ------------------------------------------------------------------ panel colours that follow the theme

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}");

    private static Map<String, String> readThemerc(File f) {
        Map<String, String> m = new HashMap<>();
        try {
            for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("!") || t.startsWith("#")) continue;
                int i = t.indexOf(':');
                if (i > 0) m.put(t.substring(0, i).trim().toLowerCase(java.util.Locale.ROOT), t.substring(i + 1).trim());
            }
        } catch (Exception ignored) { }
        return m;
    }

    /** A colour from the theme, trying the exact key and the "window.*.x" wildcard form; null if absent or not #rgb/#rrggbb. */
    private static int[] color(Map<String, String> m, String key) {
        String[] tries = {key, key.replace(".active.", ".*.").replace(".inactive.", ".*.")};
        for (String k : tries) {
            String v = m.get(k);
            if (v == null) continue;
            v = v.trim().split("\\s+")[0];
            if (!HEX.matcher(v).matches()) continue;
            if (v.length() == 4) v = "#" + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2) + v.charAt(3) + v.charAt(3);
            return new int[]{Integer.parseInt(v.substring(1, 3), 16), Integer.parseInt(v.substring(3, 5), 16), Integer.parseInt(v.substring(5, 7), 16)};
        }
        return null;
    }

    private static double lum(int[] c) {
        double[] l = new double[3];
        for (int i = 0; i < 3; i++) { double v = c[i] / 255.0; l[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
        return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2];
    }

    private static double contrast(int[] a, int[] b) {
        double x = lum(a) + 0.05, y = lum(b) + 0.05;
        return x > y ? x / y : y / x;
    }

    private static int[] mix(int[] a, int[] b, double t) {
        return new int[]{(int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t), (int) Math.round(a[2] + (b[2] - a[2]) * t)};
    }

    private static String hex(int[] c) { return String.format(java.util.Locale.ROOT, "#%02X%02X%02X", c[0], c[1], c[2]); }

    private static int[] readable(int[] bg, int[] want) {
        if (want != null && contrast(bg, want) >= 3.5) return want;
        int[] dark = {17, 17, 17}, light = {240, 242, 245};
        return contrast(bg, dark) >= contrast(bg, light) ? dark : light;
    }

    /** Panel colours: {bar, task, taskHover, active, text, activeText} as hex strings. */
    private static String[] palette(Context c) {
        int[] bar = {26, 29, 36}, act = {61, 221, 180}, txt = {230, 233, 239}, actTxt = {10, 20, 18};
        String name = Prefs.vncObTheme(c);
        if (!name.isEmpty() && SAFE_NAME.matcher(name).matches() && themerc(c, name).isFile()) {
            Map<String, String> m = readThemerc(themerc(c, name));
            int[] b = color(m, "window.inactive.title.bg.color");
            if (b == null) b = color(m, "window.active.title.bg.color");
            int[] a = color(m, "window.active.title.bg.color");
            if (b != null) bar = b;
            act = a != null ? a : bar;
            txt = readable(bar, color(m, "window.inactive.label.text.color"));
            if (contrast(act, bar) < 1.3) act = mix(bar, txt, 0.30);
            actTxt = readable(act, color(m, "window.active.label.text.color"));
        }
        return new String[]{hex(bar), hex(mix(bar, txt, 0.10)), hex(mix(bar, txt, 0.20)), hex(act), hex(txt), hex(actTxt)};
    }

    private static String bg(int rounded, String normal, String hover) {
        return "rounded = " + rounded + "\nborder_width = 0\nborder_sides = TBLR\nborder_content_tint_weight = 0\n"
                + "background_content_tint_weight = 0\nbackground_color = " + normal + " 100\nborder_color = #000000 0\n"
                + "background_color_hover = " + hover + " 100\nborder_color_hover = #000000 0\n"
                + "background_color_pressed = " + hover + " 100\nborder_color_pressed = #000000 0\n\n";
    }

    /** The tint2 panel. Backgrounds are numbered from 1 in the order they appear: 1 panel, 2 task, 3 active task / button. */
    private static String tint2rc(Context c) {
        int size = Prefs.vncUiSize(c);
        int pt = FONT_PT[size];
        int h = PANEL_HEIGHT[size];
        String[] p = palette(c);          // bar, task, taskHover, active, text, activeText
        return "# Written by NullShell once, because this file did not exist. It is yours now: NullShell never changes it again.\n"
                + bg(0, p[0], p[0])
                + bg(6, p[1], p[2])
                + bg(6, p[3], p[3])
                + "panel_items = PTSC\n"
                + "panel_size = 100% " + h + "\n"
                + "panel_margin = 0 0\n"
                + "panel_padding = 4 4 4\n"
                + "panel_background_id = 1\n"
                + "panel_position = bottom center horizontal\n"
                + "panel_layer = top\n"
                + "panel_monitor = all\n"
                + "panel_dock = 0\n"
                + "strut_policy = follow_size\n"
                + "wm_menu = 1\n"
                + "autohide = 0\n"
                + "taskbar_mode = single_desktop\n"
                + "taskbar_padding = 4 0 4\n"
                + "taskbar_background_id = 0\n"
                + "task_text = 1\n"
                + "task_icon = 0\n"
                + "task_centered = 1\n"
                + "task_maximum_size = 200 " + h + "\n"
                + "task_padding = 10 4 4\n"
                + "task_font = sans " + pt + "\n"
                + "task_font_color = " + p[4] + " 100\n"
                + "task_active_font_color = " + p[5] + " 100\n"
                + "task_background_id = 2\n"
                + "task_active_background_id = 3\n"
                + "task_urgent_background_id = 3\n"
                + "task_iconified_background_id = 2\n"
                + "systray_padding = 6 2 4\n"
                + "systray_background_id = 0\n"
                + "systray_icon_size = " + (h - 16) + "\n"
                + "time1_format = %H:%M\n"
                + "time1_font = sans Bold " + pt + "\n"
                + "clock_font_color = " + p[4] + " 100\n"
                + "clock_padding = 10 0\n"
                + "clock_background_id = 0\n"
                + "button = new\n"
                + "button_text = Menu\n"
                + "button_font = sans Bold " + pt + "\n"
                + "button_font_color = " + p[5] + " 100\n"
                + "button_padding = 14 4 4\n"
                + "button_background_id = 3\n"
                + "button_centered = 1\n"
                + "button_lclick_command = xdotool key super+space\n";
    }

    /**
     * Shell snippet for the VNC start script. Quoted heredocs, so nothing in the text is expanded by the shell. Every file
     * is written only when it does not exist yet: once it is there it belongs to the user and is never touched again.
     */
    static String script(Context c) {
        StringBuilder s = new StringBuilder();
        int pt = FONT_PT[Prefs.vncUiSize(c)];
        s.append("OB=\"$HOME/.config/openbox\"\n");
        s.append("mkdir -p \"$OB\" \"$HOME/.config/tint2\"\n");

        // a theme (set when a rice was applied) is used for a new rc.xml only if it is really there
        String theme = Prefs.vncObTheme(c);
        if (!SAFE_NAME.matcher(theme).matches()) theme = "";
        s.append("NS_OBTHEME=").append(ProotLauncher.quote(theme)).append("\n");
        s.append("if [ -n \"$NS_OBTHEME\" ] && [ ! -f \"$HOME/.themes/$NS_OBTHEME/openbox-3/themerc\" ] && ")
                .append("[ ! -f \"/usr/share/themes/$NS_OBTHEME/openbox-3/themerc\" ]; then NS_OBTHEME=''; fi\n");

        // rc.xml: the system default has the mouse and key bindings Openbox needs, so start from it. The first <name>
        // after <theme> is the theme's name (the ones after it are font names), so only that one is replaced.
        s.append("if [ ! -e \"$OB/rc.xml\" ] && [ -f /etc/xdg/openbox/rc.xml ]; then\n");
        s.append("  sed -e 's#<size>[0-9]*</size>#<size>").append(pt).append("</size>#g' \\\n");
        s.append("      -e 's#<keyboard>#<keyboard>\\n    <keybind key=\"W-space\"><action name=\"ShowMenu\"><menu>root-menu</menu></action></keybind>#' \\\n");
        s.append("      /etc/xdg/openbox/rc.xml |\n");
        s.append("    awk -v t=\"$NS_OBTHEME\" 'BEGIN{done=(t==\"\")} /<theme>/{d=1} d&&!done&&/<name>/{sub(/<name>[^<]*<\\/name>/,\"<name>\" t \"</name>\");done=1} {print}' > \"$OB/rc.xml\"\n");
        s.append("fi\n");

        // right-click / Menu button / Super+Space menu: the installed Kali GUI tools
        s.append("if [ ! -e \"$OB/menu.xml\" ]; then\n");
        s.append("cat > \"$OB/menu.xml\" <<'NS_OB_MENU_HEAD'\n");
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        s.append("<openbox_menu xmlns=\"http://openbox.org/3.4/menu\">\n");
        s.append("<menu id=\"root-menu\" label=\"NullShell\">\n");
        s.append("NS_OB_MENU_HEAD\n");
        s.append("{\n");
        s.append("  add() { command -v \"$2\" >/dev/null 2>&1 && printf '<item label=\"%s\"><action name=\"Execute\"><command>%s</command></action></item>\\n' \"$1\" \"$3\"; }\n");
        s.append("  add 'Terminal' xterm 'xterm -fa Monospace -fs 11'\n");
        s.append("  add 'Files' thunar thunar\n");
        s.append("  add 'Files' pcmanfm pcmanfm\n");
        s.append("  add 'Web browser' firefox-esr firefox-esr\n");
        s.append("  add 'Text editor' mousepad mousepad\n");
        s.append("  echo '<separator/>'\n");
        s.append("  add 'Wireshark' wireshark wireshark\n");
        s.append("  add 'Burp Suite' burpsuite burpsuite\n");
        s.append("  add 'Zenmap' zenmap zenmap\n");
        s.append("  add 'Ettercap' ettercap 'ettercap -G'\n");
        s.append("  add 'GIMP' gimp gimp\n");
        s.append("  echo '<separator/>'\n");
        s.append("  echo '<menu id=\"client-list-combined-menu\"/>'\n");
        s.append("  echo '<separator/>'\n");
        s.append("  echo '<item label=\"Restart Openbox\"><action name=\"Restart\"/></item>'\n");
        s.append("  echo '</menu>'\n");
        s.append("  echo '</openbox_menu>'\n");
        s.append("} >> \"$OB/menu.xml\"\n");
        s.append("fi\n");

        // what runs once Openbox is up: a solid background and the panel (the user's wallpaper is set separately, below)
        s.append("if [ ! -e \"$OB/autostart\" ]; then\n");
        s.append("  printf '%s\\n' \"xsetroot -solid '#20242C' &\" 'tint2 &' > \"$OB/autostart\"\n");
        s.append("fi\n");

        s.append("if [ ! -e \"$HOME/.config/tint2/tint2rc\" ]; then\n");
        s.append("cat > \"$HOME/.config/tint2/tint2rc\" <<'NS_TINT2_EOF'\n").append(tint2rc(c)).append("NS_TINT2_EOF\n");
        s.append("fi\n");
        s.append(DesktopWallpaper.script(c));
        return s.toString();
    }
}
