package com.zed.nullshell;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * "Rices": a saved desktop look. A rice is one {@code .nsrice} file (a zip) holding the files that make up how a Kali
 * desktop looks, taken from the Kali home folder: window manager and panel settings (Openbox, tint2, IceWM, rofi, picom,
 * dunst, polybar ...), GTK/Qt settings as written by lxappearance, themes, icons and cursors, fonts and the wallpapers the
 * configs point at, plus the few NullShell desktop settings. It can be saved under any name, exported, imported and applied
 * again. Everything works on the files directly (no shell in Kali is needed), so it works while the desktop is stopped.
 *
 * <p>Safety rules, because rices are shared between people and applying one writes into the home folder:
 * <ul>
 * <li>Only paths on a fixed allow-list ({@link #CATS}) are ever saved or restored. ssh keys, shell history, browser data and
 *     everything else are never read; shell prompt files (.bashrc ...) are an opt-in category that is off by default.</li>
 * <li>On import every entry is checked again: no absolute or ".." paths, nothing outside the allow-list, nothing written
 *     through a symbolic link, and a cap on the total size and number of files.</li>
 * <li>Symbolic links are stored as links and created last. Executable bits are kept from a list, since a zip cannot
 *     hold them.</li>
 * <li>The tool list ({@code pkg=}) is only honoured for package names this class knows.</li>
 * </ul>
 * Pure java.io/java.nio apart from the {@link Context} wrappers at the bottom, so it can be tested on a desktop JVM.
 */
final class RiceManager {
    private RiceManager() { }

    static final String EXT = ".nsrice";
    /** The rice that is saved automatically just before another one is applied. */
    static final String BACKUP_NAME = "Before last apply";
    static final int FORMAT = 1;

    private static final String META = "nullshell/rice.txt", LINKS = "nullshell/links.txt", EXEC = "nullshell/exec.txt";
    private static final String FILES = "files/";
    private static final long MAX_TOTAL = 3L << 30;          // bytes written by one apply
    private static final int MAX_ENTRIES = 60000;
    private static final long MAX_TEXT = 512 * 1024;         // largest config file that is read for references or secrets

    private static final Pattern NAME_OK = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} ._()-]{0,47}");
    private static final Pattern IMAGE = Pattern.compile("(?i).*\\.(png|jpe?g|webp|bmp|gif|svg|xpm)");

    // ------------------------------------------------------------------ what a rice may contain

    static final class Cat {
        final String id, label;
        final boolean byDefault;
        final String[] paths;
        Cat(String id, String label, boolean byDefault, String... paths) {
            this.id = id; this.label = label; this.byDefault = byDefault; this.paths = paths;
        }
    }

    static final String CONFIGS = "configs", THEMES = "themes", ICONS = "icons", FONTS = "fonts", WALLPAPERS = "wallpapers", SHELL = "shell";

    static final Cat[] CATS = {
            new Cat(CONFIGS, "Settings (WM, panel, rofi, GTK/Qt, terminal ...)", true,
                    ".config/openbox", ".config/tint2", ".icewm", ".config/rofi", ".config/picom", ".config/picom.conf",
                    ".config/dunst", ".config/polybar", ".config/conky", ".conkyrc", ".config/jgmenu", ".config/sxhkd",
                    ".config/xsettingsd", ".xsettingsd", ".config/gtk-3.0", ".config/gtk-4.0", ".gtkrc-2.0",
                    ".config/qt5ct", ".config/qt6ct", ".config/Kvantum", ".config/fontconfig", ".Xresources", ".Xdefaults",
                    ".config/kitty", ".config/alacritty", ".config/xfce4/terminal", ".config/lxterminal",
                    ".config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml", ".config/fastfetch", ".config/neofetch",
                    ".fehbg", ".config/pcmanfm", ".config/libfm"),
            new Cat(THEMES, "Themes", true, ".themes", ".local/share/themes", ".icewm/themes"),
            new Cat(ICONS, "Icons and cursors", true, ".icons", ".local/share/icons"),
            new Cat(FONTS, "Fonts", true, ".fonts", ".local/share/fonts"),
            new Cat(WALLPAPERS, "Wallpapers", true, ".local/share/backgrounds", "Pictures/Wallpapers", "Pictures/wallpapers",
                    "Wallpapers", "wallpapers"),
            new Cat(SHELL, "Shell prompt and aliases (may hold private info)", false,
                    ".bashrc", ".zshrc", ".p10k.zsh", ".config/starship.toml"),
    };

    /** Settings paths that belong to the themes category instead. */
    private static final String[] CONFIG_EXCLUDE = {".icewm/themes"};

    /** Never packed, wherever they are. */
    private static final Set<String> SKIP_NAMES = new HashSet<>(Arrays.asList(".git", ".cache", "__pycache__", ".DS_Store"));

    static Cat cat(String id) {
        for (Cat c : CATS) if (c.id.equals(id)) return c;
        return null;
    }

    private static boolean under(String rel, String base) { return rel.equals(base) || rel.startsWith(base + "/"); }

    private static boolean configExcluded(String rel) {
        for (String e : CONFIG_EXCLUDE) if (under(rel, e)) return true;
        return false;
    }

    /** The category a home-relative path may be stored in, or null when it is not on the allow-list. */
    static String categoryOf(String rel) {
        for (Cat c : CATS) {
            if (c.id.equals(WALLPAPERS)) continue;
            for (String p : c.paths) {
                if (!under(rel, p)) continue;
                if (c.id.equals(CONFIGS) && configExcluded(rel)) continue;
                return c.id;
            }
        }
        Cat w = cat(WALLPAPERS);
        for (String p : w.paths) if (under(rel, p)) return WALLPAPERS;
        // an image the configs point at: anywhere in the home folder except hidden folders (those are covered above)
        if (IMAGE.matcher(rel).matches() && !rel.startsWith(".")) return WALLPAPERS;
        if (IMAGE.matcher(rel).matches() && under(rel, ".local/share/backgrounds")) return WALLPAPERS;
        return null;
    }

    /** A home-relative path with no tricks: not absolute, no "." or ".." parts, no control characters or backslashes. */
    static boolean safeRel(String rel) {
        if (rel == null || rel.isEmpty() || rel.length() > 400) return false;
        if (rel.startsWith("/") || rel.endsWith("/")) return false;
        for (int i = 0; i < rel.length(); i++) {
            char ch = rel.charAt(i);
            if (ch < 32 || ch == 127 || ch == '\\') return false;
        }
        for (String p : rel.split("/", -1)) {
            if (p.isEmpty() || p.equals(".") || p.equals("..") || p.length() > 255) return false;
        }
        return true;
    }

    static boolean validName(String name) { return name != null && NAME_OK.matcher(name).matches() && !name.endsWith(" ") && !name.endsWith("."); }

    // ------------------------------------------------------------------ packages a rice may ask for

    /** {package, program that proves it is installed}. Only these names are ever passed to apt. */
    static final String[][] PKGS = {
            {"openbox", "openbox"}, {"tint2", "tint2"}, {"rofi", "rofi"}, {"picom", "picom"}, {"dunst", "dunst"},
            {"polybar", "polybar"}, {"conky-all", "conky"}, {"jgmenu", "jgmenu"}, {"sxhkd", "sxhkd"},
            {"xsettingsd", "xsettingsd"}, {"kitty", "kitty"}, {"alacritty", "alacritty"}, {"fastfetch", "fastfetch"},
            {"neofetch", "neofetch"}, {"feh", "feh"}, {"pcmanfm", "pcmanfm"},
            {"qt5ct", "qt5ct"}, {"lxterminal", "lxterminal"}, {"xfce4-terminal", "xfce4-terminal"}, {"icewm", "icewm"},
            {"xdotool", "xdotool"}, {"volumeicon-alsa", "volumeicon"}, {"pasystray", "pasystray"},
            {"network-manager-gnome", "nm-applet"}, {"lxappearance", "lxappearance"}, {"obconf", "obconf"},
            {"lxpolkit", "lxpolkit"}, {"thunar", "thunar"},
    };

    /** The tools to offer from the Rices screen for making a rice by hand. */
    static final String TOOLS = "lxappearance obconf rofi feh tint2 xdotool";

    private static final String[][] PATH_PKG = {
            {".config/openbox", "openbox"}, {".config/tint2", "tint2"}, {".config/rofi", "rofi"}, {".config/picom", "picom"},
            {".config/picom.conf", "picom"}, {".config/dunst", "dunst"}, {".config/polybar", "polybar"},
            {".config/conky", "conky-all"}, {".conkyrc", "conky-all"}, {".config/jgmenu", "jgmenu"},
            {".config/sxhkd", "sxhkd"}, {".config/xsettingsd", "xsettingsd"}, {".xsettingsd", "xsettingsd"},
            {".config/kitty", "kitty"}, {".config/alacritty", "alacritty"}, {".config/fastfetch", "fastfetch"},
            {".config/neofetch", "neofetch"}, {".fehbg", "feh"},
            {".config/pcmanfm", "pcmanfm"}, {".config/qt5ct", "qt5ct"}, {".config/lxterminal", "lxterminal"},
            {".config/xfce4/terminal", "xfce4-terminal"}, {".icewm", "icewm"},
    };

    /** Files whose text names the programs they start. */
    private static final String[] STARTUP_FILES = {".config/openbox/autostart", ".icewm/startup", ".fehbg",
            ".config/sxhkd/sxhkdrc", ".config/openbox/rc.xml", ".config/tint2/tint2rc"};

    static boolean knownPackage(String p) {
        for (String[] k : PKGS) if (k[0].equals(p)) return true;
        return false;
    }

    static String programOf(String pkg) {
        for (String[] k : PKGS) if (k[0].equals(pkg)) return k[1];
        return null;
    }

    private static List<String> detectPackages(File home, List<String> files) {
        Set<String> out = new LinkedHashSet<>();
        for (String f : files) for (String[] pp : PATH_PKG) if (under(f, pp[0])) out.add(pp[1]);
        Set<String> have = new HashSet<>(files);
        for (String sf : STARTUP_FILES) {
            if (!have.contains(sf)) continue;
            String text = readText(new File(home, sf));
            if (text == null) continue;
            for (String[] k : PKGS) {
                if (k[0].equals("openbox") || k[0].equals("icewm") || k[0].equals("tint2")) continue;
                if (Pattern.compile("(?<![\\w.-])" + Pattern.quote(k[1]) + "(?![\\w-])").matcher(text).find()) out.add(k[0]);
            }
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ reading files

    /** The text of a small file, or null when it is big, unreadable, a link or looks binary. */
    private static String readText(File f) {
        try {
            Path p = f.toPath();
            if (Files.isSymbolicLink(p) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || f.length() > MAX_TEXT) return null;
            byte[] b = Files.readAllBytes(p);
            for (int i = 0; i < Math.min(b.length, 1024); i++) if (b[i] == 0) return null;
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ planning what to save

    static final class Plan {
        final List<String> files = new ArrayList<>();
        final List<String[]> links = new ArrayList<>();
        final List<String> exec = new ArrayList<>();
        final Map<String, long[]> perCat = new LinkedHashMap<>();   // category -> {files, bytes}
        long bytes;
        void count(String cat, long size) {
            long[] a = perCat.get(cat);
            if (a == null) { a = new long[2]; perCat.put(cat, a); }
            a[0]++; a[1] += size;
            bytes += size;
        }
    }

    /**
     * What would be saved for the chosen categories. {@code extraWallRel} is the NullShell wallpaper (home-relative), or
     * null. Nothing is read through symbolic links.
     */
    static Plan plan(File home, Set<String> ids, String extraWallRel) {
        Plan p = new Plan();
        Set<String> seen = new HashSet<>();
        for (Cat c : CATS) {
            if (!ids.contains(c.id)) continue;
            for (String path : c.paths) walk(home, path, c.id, p, seen);
        }
        if (ids.contains(WALLPAPERS)) {
            List<String> refs = new ArrayList<>();
            if (extraWallRel != null) refs.add(extraWallRel);
            Pattern ref = Pattern.compile("(?i)(?:/root|~|\\$\\{?HOME\\}?)/([^\\s'\"\\\\);,<>|]+?\\.(?:png|jpe?g|webp|bmp|gif|svg|xpm))");
            for (String f : new ArrayList<>(p.files)) {
                String c = categoryOf(f);
                if (!CONFIGS.equals(c)) continue;
                String text = readText(new File(home, f));
                if (text == null) continue;
                Matcher m = ref.matcher(text);
                while (m.find() && refs.size() < 300) refs.add(m.group(1));
            }
            for (String r : refs) {
                if (!safeRel(r) || seen.contains(r)) continue;
                String c = categoryOf(r);
                if (!WALLPAPERS.equals(c)) continue;                // an image inside another category is already packed with it
                walk(home, r, WALLPAPERS, p, seen);
            }
        }
        return p;
    }

    private static void walk(File home, String rel, String cat, Plan p, Set<String> seen) {
        if (!safeRel(rel) || seen.contains(rel)) return;
        if (CONFIGS.equals(cat) && configExcluded(rel)) return;
        File f = new File(home, rel);
        Path path = f.toPath();
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            String name = f.getName();
            if (SKIP_NAMES.contains(name)) return;
            if (Files.isSymbolicLink(path)) {
                String target = Files.readSymbolicLink(path).toString();
                if (validTarget(target)) { seen.add(rel); p.links.add(new String[]{rel, target}); p.count(cat, 0); }
                return;
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                String[] kids = f.list();
                if (kids == null) return;
                Arrays.sort(kids);
                for (String k : kids) walk(home, rel + "/" + k, cat, p, seen);
                return;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return;     // sockets, devices ...
            if (name.endsWith(".pyc") || name.endsWith(".log")) return;
            seen.add(rel);
            p.files.add(rel);
            p.count(cat, f.length());
            if (f.canExecute()) p.exec.add(rel);
        } catch (IOException | RuntimeException ignored) { }
    }

    private static boolean validTarget(String t) {
        if (t == null || t.isEmpty() || t.length() > 1024) return false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch < 32 || ch == 127) return false;
        }
        return true;
    }

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|secret|passw(?:or)?d|token|authorization|bearer)\\s*[=:]\\s*[\"']?[A-Za-z0-9_\\-./+=]{8,}");

    /** Saved files in the settings and shell categories whose text looks like it holds a key, token or password. */
    static List<String> secretLooking(File home, Plan plan) {
        List<String> out = new ArrayList<>();
        for (String f : plan.files) {
            String c = categoryOf(f);
            if (!CONFIGS.equals(c) && !SHELL.equals(c)) continue;
            String text = readText(new File(home, f));
            if (text != null && SECRET.matcher(text).find()) { out.add(f); if (out.size() >= 30) break; }
        }
        return out;
    }

    // ------------------------------------------------------------------ saving

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * Writes a rice. {@code skip} lists home-relative files to leave out (for example ones that looked like they hold
     * secrets). The file is written under a temporary name and moved into place at the end.
     */
    static void save(File home, File out, String name, Set<String> ids, Map<String, String> prefs, Set<String> skip,
                     String extraWallRel) throws IOException {
        if (!validName(name)) throw new IOException("That name cannot be used");
        Plan p = plan(home, ids, extraWallRel);
        if (skip != null) { p.files.removeAll(skip); p.exec.removeAll(skip); }
        List<String> pkgs = ids.contains(CONFIGS) ? detectPackages(home, p.files) : new ArrayList<String>();
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create the rices folder");
        File tmp = new File(out.getPath() + ".tmp");
        boolean ok = false;
        try {
            try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024))) {
                z.setLevel(6);
                byte[] buf = new byte[64 * 1024];
                for (String rel : p.files) {
                    File f = new File(home, rel);
                    z.putNextEntry(new ZipEntry(FILES + rel));
                    try (InputStream in = new FileInputStream(f)) {
                        int n;
                        while ((n = in.read(buf)) != -1) z.write(buf, 0, n);
                    } catch (IOException e) {
                        throw new IOException("Could not read " + rel + ": " + e.getMessage());
                    }
                    z.closeEntry();
                }
                StringBuilder meta = new StringBuilder();
                meta.append("format=").append(FORMAT).append('\n');
                meta.append("name=").append(oneLine(name)).append('\n');
                meta.append("created=").append(System.currentTimeMillis()).append('\n');
                for (Cat c : CATS) if (ids.contains(c.id)) meta.append("cat=").append(c.id).append('\n');
                if (prefs != null) for (Map.Entry<String, String> e : prefs.entrySet())
                    meta.append("pref.").append(oneLine(e.getKey())).append('=').append(oneLine(e.getValue())).append('\n');
                for (String k : pkgs) meta.append("pkg=").append(k).append('\n');
                put(z, META, meta.toString());
                StringBuilder links = new StringBuilder();
                for (String[] l : p.links) links.append(l[0]).append('\t').append(l[1]).append('\n');
                put(z, LINKS, links.toString());
                StringBuilder ex = new StringBuilder();
                for (String e : p.exec) ex.append(e).append('\n');
                put(z, EXEC, ex.toString());
            }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ok = true;
        } finally {
            if (!ok) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    // ------------------------------------------------------------------ reading a rice

    static final class Info {
        String name = "";
        long created;
        int format;
        final Set<String> cats = new LinkedHashSet<>();
        final Map<String, String> prefs = new LinkedHashMap<>();
        final List<String> pkgs = new ArrayList<>();
        final Map<String, long[]> perCat = new LinkedHashMap<>();     // category -> {files, bytes (uncompressed)}
        int files, links, execCount, rejected;
        long bytes;
        final List<String> startup = new ArrayList<>();               // what the desktop will start, for the confirmation
        String problem;                                               // non-null: not usable
    }

    private static String entryText(ZipFile z, String name, int max) {
        try {
            ZipEntry e = z.getEntry(name);
            if (e == null || e.isDirectory()) return null;
            try (InputStream in = z.getInputStream(e)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    b.write(buf, 0, n);
                    if (b.size() > max) return null;
                }
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Looks inside a rice without extracting anything. Never throws: a bad file comes back with {@code problem} set. */
    static Info readInfo(File rice) {
        Info info = new Info();
        try (ZipFile z = new ZipFile(rice)) {
            String meta = entryText(z, META, 256 * 1024);
            if (meta == null) { info.problem = "This is not a NullShell rice file."; return info; }
            for (String line : meta.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                if (k.equals("format")) { try { info.format = Integer.parseInt(v); } catch (NumberFormatException ignored) { } }
                else if (k.equals("name")) info.name = v;
                else if (k.equals("created")) { try { info.created = Long.parseLong(v); } catch (NumberFormatException ignored) { } }
                else if (k.equals("cat")) { if (cat(v) != null) info.cats.add(v); }
                else if (k.equals("pkg")) { if (knownPackage(v) && !info.pkgs.contains(v)) info.pkgs.add(v); }
                else if (k.startsWith("pref.")) info.prefs.put(k.substring(5), v);
            }
            if (info.format < 1) { info.problem = "This rice file is damaged."; return info; }
            if (info.format > FORMAT) { info.problem = "This rice was made by a newer version of NullShell. Update the app to use it."; return info; }
            int count = 0;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (++count > MAX_ENTRIES) { info.problem = "This rice has too many files."; return info; }
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith(FILES)) continue;
                String rel = n.substring(FILES.length());
                String c = safeRel(rel) ? categoryOf(rel) : null;
                if (c == null || !info.cats.contains(c)) { info.rejected++; continue; }
                long size = Math.max(0, e.getSize());
                info.files++;
                info.bytes += size;
                long[] a = info.perCat.get(c);
                if (a == null) { a = new long[2]; info.perCat.put(c, a); }
                a[0]++; a[1] += size;
            }
            if (info.bytes > MAX_TOTAL) { info.problem = "This rice is too big (over 3 GB)."; return info; }
            String links = entryText(z, LINKS, 4 * 1024 * 1024);
            if (links != null) for (String l : links.split("\n")) {
                int t = l.indexOf('\t');
                if (t > 0 && safeRel(l.substring(0, t)) && info.cats.contains(categoryOf(l.substring(0, t))) && validTarget(l.substring(t + 1))) info.links++;
            }
            String ex = entryText(z, EXEC, 4 * 1024 * 1024);
            if (ex != null) for (String l : ex.split("\n")) if (safeRel(l) && info.cats.contains(categoryOf(l))) info.execCount++;
            for (String sf : new String[]{".config/openbox/autostart", ".icewm/startup"}) {
                String text = entryText(z, FILES + sf, 64 * 1024);
                if (text == null) continue;
                for (String l : text.split("\n")) {
                    String t = l.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    if (info.startup.size() < 12) info.startup.add(t.length() > 110 ? t.substring(0, 110) + "…" : t);
                }
            }
            if (info.files == 0 && info.links == 0) info.problem = "This rice has nothing in it that can be used.";
        } catch (IOException | RuntimeException e) {
            info.problem = "This file could not be read as a rice (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").";
        }
        return info;
    }

    // ------------------------------------------------------------------ applying

    static final class Result {
        int files, links, skipped;
    }

    /**
     * Writes the chosen categories of a rice into the home folder. The settings category replaces what is there (so
     * nothing stale is left behind); themes, icons, fonts and wallpapers are added next to what exists, same names replaced.
     */
    static Result apply(File home, File rice, Set<String> ids) throws IOException {
        Info info = readInfo(rice);
        if (info.problem != null) throw new IOException(info.problem);
        Set<String> chosen = new LinkedHashSet<>(ids);
        chosen.retainAll(info.cats);
        Result r = new Result();
        if (chosen.isEmpty()) return r;
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("The Kali home folder is missing");
        String homeCanon = home.getCanonicalPath();

        if (chosen.contains(CONFIGS)) for (String p : cat(CONFIGS).paths) deleteTree(new File(home, p), p);

        long total = 0;
        try (ZipFile z = new ZipFile(rice)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            byte[] buf = new byte[64 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith(FILES)) continue;
                String rel = n.substring(FILES.length());
                String c = safeRel(rel) ? categoryOf(rel) : null;
                if (c == null || !chosen.contains(c)) { r.skipped++; continue; }
                int slash = rel.lastIndexOf('/');
                if (slash > 0 && !ensureDir(home, rel.substring(0, slash))) { r.skipped++; continue; }
                File dest = new File(home, rel);
                Path dp = dest.toPath();
                if (!dest.getParentFile().getCanonicalPath().startsWith(homeCanon)) { r.skipped++; continue; }
                if (Files.isSymbolicLink(dp)) Files.delete(dp);
                else if (dest.isDirectory()) { r.skipped++; continue; }
                try (InputStream in = new BufferedInputStream(z.getInputStream(e), 64 * 1024);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 64 * 1024)) {
                    int k;
                    while ((k = in.read(buf)) != -1) {
                        total += k;
                        if (total > MAX_TOTAL) throw new IOException("The rice is bigger than it said it was; stopped.");
                        out.write(buf, 0, k);
                    }
                }
                r.files++;
            }

            // links last, so nothing above can be written through one
            String links = entryText(z, LINKS, 4 * 1024 * 1024);
            if (links != null) for (String l : links.split("\n")) {
                int t = l.indexOf('\t');
                if (t <= 0) continue;
                String rel = l.substring(0, t), target = l.substring(t + 1);
                String c = safeRel(rel) ? categoryOf(rel) : null;
                if (c == null || !chosen.contains(c) || !validTarget(target)) { r.skipped++; continue; }
                int slash = rel.lastIndexOf('/');
                if (slash > 0 && !ensureDir(home, rel.substring(0, slash))) { r.skipped++; continue; }
                File dest = new File(home, rel);
                Path dp = dest.toPath();
                try {
                    if (Files.isSymbolicLink(dp) || (Files.exists(dp, LinkOption.NOFOLLOW_LINKS) && !dest.isDirectory())) Files.delete(dp);
                    else if (dest.isDirectory()) { r.skipped++; continue; }
                    Files.createSymbolicLink(dp, Paths.get(target));
                    r.links++;
                } catch (IOException | RuntimeException ex) { r.skipped++; }
            }

            String ex = entryText(z, EXEC, 4 * 1024 * 1024);
            if (ex != null) for (String l : ex.split("\n")) {
                String c = safeRel(l) ? categoryOf(l) : null;
                if (c == null || !chosen.contains(c)) continue;
                File f = new File(home, l);
                if (f.isFile() && !Files.isSymbolicLink(f.toPath())) //noinspection ResultOfMethodCallIgnored
                    f.setExecutable(true, false);
            }
        }
        return r;
    }

    /** Creates the folders of a home-relative path one by one, refusing to go through a symbolic link. */
    private static boolean ensureDir(File home, String relParent) {
        File cur = home;
        for (String part : relParent.split("/")) {
            cur = new File(cur, part);
            if (Files.isSymbolicLink(cur.toPath())) return false;
            if (!cur.exists()) { if (!cur.mkdir() && !cur.isDirectory()) return false; }
            else if (!cur.isDirectory()) return false;
        }
        return true;
    }

    /** Deletes a file or folder without following links; settings paths that belong to themes are kept. */
    private static void deleteTree(File f, String rel) {
        if (configExcluded(rel)) return;
        Path p = f.toPath();
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isSymbolicLink(p) && f.isDirectory()) {
            String[] kids = f.list();
            if (kids != null) for (String k : kids) deleteTree(new File(f, k), rel + "/" + k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();                                    // fails quietly for a folder that still holds kept files
    }

    // ------------------------------------------------------------------ the library (Android side)

    static File home(Context c) { return new File(RootfsManager.dir(c), "root"); }

    /** Outside the rootfs, so deleting Kali never removes the saved rices. */
    static File library(Context c) { return new File(c.getFilesDir(), "rices"); }

    static File fileFor(Context c, String name) { return new File(library(c), name + EXT); }

    /** Saved rice files, newest first. */
    static List<File> list(Context c) {
        File[] kids = library(c).listFiles();
        List<File> out = new ArrayList<>();
        if (kids != null) for (File k : kids) if (k.isFile() && k.getName().endsWith(EXT)) out.add(k);
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    static String nameOf(File f) { return f.getName().substring(0, f.getName().length() - EXT.length()); }

    /** The NullShell desktop settings that belong to a look. */
    static Map<String, String> capturePrefs(Context c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("wm", Prefs.vncWm(c));
        m.put("icewmTheme", Prefs.vncTheme(c));
        m.put("obTheme", Prefs.vncObTheme(c));
        m.put("wallFit", String.valueOf(Prefs.vncWallpaperFit(c)));
        String w = homeRelWallpaper(c);
        if (w != null) m.put("wallpaper", "/root/" + w);
        return m;
    }

    /** The wallpaper chosen in Settings as a home-relative path when it is a file inside the home folder, else null. */
    static String homeRelWallpaper(Context c) {
        String w = Prefs.vncWallpaperPath(c);
        if (w == null || !w.startsWith("/root/")) return null;
        String rel = w.substring(6);
        return safeRel(rel) && new File(home(c), rel).isFile() ? rel : null;
    }

    private static int intIn(String v, int lo, int hi, int def) {
        try { int n = Integer.parseInt(v); return n < lo || n > hi ? def : n; } catch (NumberFormatException e) { return def; }
    }

    private static boolean plainName(String s) { return s != null && s.matches("[A-Za-z0-9._-]{0,64}"); }

    /** Restores the NullShell settings stored in a rice (only values that make sense; anything else is ignored). */
    static void applyPrefs(Context c, Map<String, String> m) {
        android.content.SharedPreferences.Editor e = Prefs.sp(c).edit();
        String wm = m.get("wm");
        if (Prefs.WM_OPENBOX.equals(wm) || Prefs.WM_ICEWM.equals(wm)) e.putString(Prefs.VNC_WM, wm);
        // themes only matter for a desktop config that has to be written new (a rice without those files)
        if (m.containsKey("icewmTheme") && plainName(m.get("icewmTheme"))) e.putString(Prefs.VNC_THEME, m.get("icewmTheme"));
        if (m.containsKey("obTheme") && plainName(m.get("obTheme"))) e.putString(Prefs.VNC_OBTHEME, m.get("obTheme"));
        if (m.containsKey("wallFit")) e.putInt(Prefs.VNC_OBWALL, intIn(m.get("wallFit"), 0, 3, 0));
        String w = m.get("wallpaper");
        if (w != null && IceWmConfig.safeGuestPath(w) && w.startsWith("/root/")) e.putString(Prefs.VNC_WALLPATH, w);
        e.apply();
    }

    /** Saves the current look under a name into the library. */
    static void saveCurrent(Context c, String name, Set<String> ids, Set<String> skip) throws IOException {
        save(home(c), fileFor(c, name), name, ids, capturePrefs(c), skip, homeRelWallpaper(c));
    }

    /** Applies a saved rice after keeping the current look as {@link #BACKUP_NAME}. */
    static Result applySaved(Context c, File rice, Set<String> ids) throws IOException {
        // only the settings are replaced (and the shell files if chosen); themes, icons and fonts are only added to
        Set<String> keep = new LinkedHashSet<>();
        keep.add(CONFIGS);
        if (ids.contains(SHELL)) keep.add(SHELL);
        // applying the backup itself: the backup below would overwrite it, so work from a copy
        File src = rice, copy = null;
        if (nameOf(rice).equals(BACKUP_NAME)) {
            //noinspection ResultOfMethodCallIgnored
            library(c).mkdirs();
            copy = File.createTempFile("apply", EXT, library(c));
            Files.copy(rice.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            src = copy;
        }
        try {
            try { saveCurrent(c, BACKUP_NAME, keep, null); } catch (IOException e) { throw new IOException("Could not back up your current setup first: " + e.getMessage()); }
            Result r = apply(home(c), src, ids);
            Info info = readInfo(src);
            applyPrefs(c, info.prefs);
            return r;
        } finally {
            if (copy != null) //noinspection ResultOfMethodCallIgnored
                copy.delete();
        }
    }

    /** Programs the rice needs that are not installed in Kali yet, as package names. */
    static List<String> missingPackages(Context c, Info info) {
        List<String> out = new ArrayList<>();
        File rootfs = RootfsManager.dir(c);
        for (String p : info.pkgs) {
            String prog = programOf(p);
            if (prog != null && GuestPaths.which(rootfs, prog) == null) out.add(p);
        }
        return out;
    }

    static String installCommand(List<String> pkgs) {
        StringBuilder s = new StringBuilder("apt update && apt install -y");
        for (String p : pkgs) if (knownPackage(p)) s.append(' ').append(p);
        return s.toString();
    }

    static String describe(Info info) {
        StringBuilder s = new StringBuilder();
        for (String id : info.cats) {
            if (s.length() > 0) s.append("  \u00B7  ");
            s.append(shortLabel(id));
        }
        return s.toString();
    }

    static String shortLabel(String id) {
        switch (id) {
            case CONFIGS: return "settings";
            case THEMES: return "themes";
            case ICONS: return "icons";
            case FONTS: return "fonts";
            case WALLPAPERS: return "wallpapers";
            default: return "shell";
        }
    }
}
