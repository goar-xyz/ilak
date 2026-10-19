package com.zed.nullshell;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every key the extra-keys row can show, and the saved order. The layout is stored as the key ids separated by
 * spaces; an empty setting means the default row. Unknown ids (from a newer or older version) are skipped.
 */
final class ExtraKeys {
    private ExtraKeys() { }

    static final int MOD_CTRL = 0, MOD_ALT = 1, KEYCODE = 2, TEXT = 3, PASTE = 4, KEYBOARD = 5;

    static final class Def {
        final String id, label, name, text;
        final int kind, code;
        Def(String id, String label, String name, int kind, int code, String text) {
            this.id = id; this.label = label; this.name = name; this.kind = kind; this.code = code; this.text = text;
        }
    }

    private static final Map<String, Def> BY_ID = new LinkedHashMap<>();

    private static void key(String id, String label, String name, int code) { put(new Def(id, label, name, KEYCODE, code, null)); }
    private static void sym(String ch, String name) { put(new Def("t" + ch, ch, name, TEXT, 0, ch)); }
    private static void put(Def d) { BY_ID.put(d.id, d); }

    static {
        put(new Def("ctrl", "CTRL", "Ctrl (sticky)", MOD_CTRL, 0, null));
        put(new Def("alt", "ALT", "Alt (sticky)", MOD_ALT, 0, null));
        key("esc", "ESC", "Escape", KeyEvent.KEYCODE_ESCAPE);
        key("tab", "TAB", "Tab", KeyEvent.KEYCODE_TAB);
        key("left", "←", "Left arrow", KeyEvent.KEYCODE_DPAD_LEFT);
        key("down", "↓", "Down arrow", KeyEvent.KEYCODE_DPAD_DOWN);
        key("up", "↑", "Up arrow", KeyEvent.KEYCODE_DPAD_UP);
        key("right", "→", "Right arrow", KeyEvent.KEYCODE_DPAD_RIGHT);
        key("home", "HOME", "Home", KeyEvent.KEYCODE_MOVE_HOME);
        key("end", "END", "End", KeyEvent.KEYCODE_MOVE_END);
        key("pgup", "PGUP", "Page up", KeyEvent.KEYCODE_PAGE_UP);
        key("pgdn", "PGDN", "Page down", KeyEvent.KEYCODE_PAGE_DOWN);
        key("ins", "INS", "Insert", KeyEvent.KEYCODE_INSERT);
        key("del", "DEL", "Delete", KeyEvent.KEYCODE_FORWARD_DEL);
        sym("-", "Hyphen");
        sym("/", "Slash");
        sym("|", "Pipe");
        sym("~", "Tilde");
        put(new Def("paste", "PASTE", "Paste from clipboard", PASTE, 0, null));
        put(new Def("kb", "KB", "Show or hide the keyboard", KEYBOARD, 0, null));
        int[] f = {KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5,
                KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10,
                KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12};
        for (int i = 0; i < 12; i++) key("f" + (i + 1), "F" + (i + 1), "F" + (i + 1), f[i]);
        sym("_", "Underscore");
        sym("$", "Dollar");
        sym("&", "Ampersand");
        sym(">", "Greater than");
        sym("<", "Less than");
        sym("*", "Asterisk");
        sym("'", "Single quote");
        sym("\"", "Double quote");
        sym("`", "Backtick");
        sym("\\", "Backslash");
        sym("#", "Hash");
        sym("@", "At sign");
        sym("%", "Percent");
        sym("^", "Caret");
        sym("(", "Open parenthesis");
        sym(")", "Close parenthesis");
        sym("[", "Open bracket");
        sym("]", "Close bracket");
        sym("{", "Open brace");
        sym("}", "Close brace");
        sym("=", "Equals");
        sym("+", "Plus");
        sym(";", "Semicolon");
        sym(":", "Colon");
        sym(".", "Period");
        sym(",", "Comma");
        sym("!", "Exclamation mark");
        sym("?", "Question mark");
    }

    /** The row people get until they customise it. */
    static final String DEFAULT = "ctrl alt esc tab left down up right home end pgup pgdn t- t/ t| t~ paste kb";

    static List<Def> all() { return new ArrayList<>(BY_ID.values()); }

    static Def byId(String id) { return BY_ID.get(id); }

    /** Ids from a saved setting, without unknown or repeated keys; the default row when nothing valid is left. */
    static List<String> ids(String saved) {
        List<String> out = parse(saved);
        return out.isEmpty() ? parse(DEFAULT) : out;
    }

    private static List<String> parse(String saved) {
        List<String> out = new ArrayList<>();
        if (saved == null) return out;
        for (String s : saved.trim().split("\\s+")) {
            if (!s.isEmpty() && BY_ID.containsKey(s) && !out.contains(s)) out.add(s);
        }
        return out;
    }

    static List<Def> layout(String saved) {
        List<Def> out = new ArrayList<>();
        for (String id : ids(saved)) out.add(BY_ID.get(id));
        return out;
    }

    static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String s : ids) { if (sb.length() > 0) sb.append(' '); sb.append(s); }
        return sb.toString();
    }
}
