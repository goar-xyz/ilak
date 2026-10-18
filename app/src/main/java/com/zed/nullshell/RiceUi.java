package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** The dialogs and background work behind the Rices screen in Settings. Logic lives in {@link RiceManager}. */
final class RiceUi {
    private final Activity a;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final int reqExport, reqImport;
    private final AtomicBoolean busy = new AtomicBoolean();
    private File pendingExport;

    RiceUi(Activity a, int reqExport, int reqImport, Runnable changed) {
        this.a = a;
        this.reqExport = reqExport;
        this.reqImport = reqImport;
        this.changed = changed;
    }

    // ------------------------------------------------------------------ small helpers

    private boolean gone() { return a.isFinishing() || a.isDestroyed(); }

    private void toast(String s) { if (!gone()) Toast.makeText(a, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Ui.dp(a, v); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(a);
        c.setText(label);
        c.setTextColor(Ui.TEXT);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.setChecked(on);
        c.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
        return c;
    }

    private LinearLayout box() {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(22), dp(10), dp(22), dp(4));
        return b;
    }

    private ScrollView scroll(LinearLayout inner) {
        ScrollView s = new ScrollView(a);
        s.addView(inner, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private static String size(long b) { return RootfsManager.formatBytes(b); }

    private boolean ready() {
        if (!RootfsManager.isInstalled(a)) { toast("Install Kali first (Settings > Kali rootfs)."); return false; }
        return true;
    }

    private AlertDialog working(String title, String message) {
        LinearLayout b = box();
        b.addView(text(message, 14, Ui.TEXT));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.addView(bar, lp);
        return new Dlg(a).setTitle(title).setView(b).setCancelable(false).show();
    }

    // ------------------------------------------------------------------ save

    void save() {
        if (!ready() || !busy.compareAndSet(false, true)) { if (busy.get()) toast("Another rice job is running."); return; }
        final android.content.Context app = a.getApplicationContext();
        final AlertDialog wait = working("Looking at your setup", "Counting what can be saved…");
        new Thread(() -> {
            Set<String> all = new LinkedHashSet<>();
            for (RiceManager.Cat c : RiceManager.CATS) all.add(c.id);
            final RiceManager.Plan plan = RiceManager.plan(RiceManager.home(app), all, RiceManager.homeRelWallpaper(app));
            main.post(() -> {
                busy.set(false);
                wait.dismiss();
                if (!gone()) showSaveDialog(plan);
            });
        }, "rice-plan").start();
    }

    private void showSaveDialog(final RiceManager.Plan plan) {
        LinearLayout b = box();
        b.addView(text("Saves how your desktop looks right now. Only these folders are ever read, so keys and history stay out.", 12, Ui.DIM));
        final EditText name = new EditText(a);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.HINT);
        name.setHint("Name, for example Nord Openbox");
        Ui.tint(name);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(8);
        b.addView(name, np);

        final List<RiceManager.Cat> cats = new ArrayList<>();
        final List<CheckBox> boxes = new ArrayList<>();
        final TextView total = text("", 12, Ui.DIM);
        final Runnable update = () -> {
            long bytes = 0;
            for (int i = 0; i < cats.size(); i++) {
                long[] s = plan.perCat.get(cats.get(i).id);
                if (boxes.get(i).isChecked() && s != null) bytes += s[1];
            }
            total.setText("About " + size(bytes) + " before compression" + (bytes > 200L * 1024 * 1024 ? "  ·  big, consider unticking icons or fonts" : ""));
        };
        for (RiceManager.Cat c : RiceManager.CATS) {
            long[] s = plan.perCat.get(c.id);
            boolean has = s != null && (s[0] > 0);
            String label = c.label + (has ? "  ·  " + s[0] + (s[0] == 1 ? " item, " : " items, ") + size(s[1]) : "  ·  nothing found");
            CheckBox cb = check(label, has && c.byDefault);
            cb.setEnabled(has);
            cb.setOnCheckedChangeListener((v, on) -> update.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            b.addView(cb, lp);
            cats.add(c);
            boxes.add(cb);
        }
        update.run();
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(8);
        b.addView(total, tp);

        final AlertDialog d = new Dlg(a).setTitle("Save current setup").setView(scroll(b))
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (!RiceManager.validName(n)) { toast("Use letters, numbers, spaces, dots, dashes (up to 48), starting with a letter or number."); return; }
            final Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < cats.size(); i++) if (boxes.get(i).isChecked()) ids.add(cats.get(i).id);
            if (ids.isEmpty()) { toast("Tick at least one thing to save."); return; }
            final File target = RiceManager.fileFor(a, n);
            if (n.equals(RiceManager.BACKUP_NAME)) { toast("That name is used for the automatic backup. Pick another."); return; }
            d.dismiss();
            if (target.exists()) {
                new Dlg(a).setTitle("Replace \"" + n + "\"?").setMessage("A rice with this name is already saved.")
                        .setPositiveButton("Replace", (x, w) -> checkSecretsThenSave(n, ids))
                        .setNegativeButton("Cancel", null).show();
            } else {
                checkSecretsThenSave(n, ids);
            }
        });
    }

    private void checkSecretsThenSave(final String name, final Set<String> ids) {
        if (!busy.compareAndSet(false, true)) { toast("Another rice job is running."); return; }
        final android.content.Context app = a.getApplicationContext();
        final AlertDialog wait = working("Saving \"" + name + "\"", "Checking the files…");
        new Thread(() -> {
            final File home = RiceManager.home(app);
            final RiceManager.Plan plan = RiceManager.plan(home, ids, RiceManager.homeRelWallpaper(app));
            final List<String> secrets = RiceManager.secretLooking(home, plan);
            main.post(() -> {
                wait.dismiss();
                if (gone()) { busy.set(false); return; }
                if (secrets.isEmpty()) { busy.set(false); doSave(name, ids, null); return; }
                StringBuilder s = new StringBuilder("These files look like they contain a key, token or password. A rice is made to be shared, so check before keeping them:\n");
                for (String f : secrets) s.append("\n  ~/").append(f);
                new Dlg(a).setTitle("Private-looking files").setMessage(s.toString())
                        .setPositiveButton("Leave them out", (x, w) -> { busy.set(false); doSave(name, ids, new LinkedHashSet<>(secrets)); })
                        .setNeutralButton("Keep them", (x, w) -> { busy.set(false); doSave(name, ids, null); })
                        .setNegativeButton("Cancel", (x, w) -> busy.set(false))
                        .setOnCancelListener(x -> busy.set(false)).show();
            });
        }, "rice-scan").start();
    }

    private void doSave(final String name, final Set<String> ids, final Set<String> skip) {
        if (!busy.compareAndSet(false, true)) { toast("Another rice job is running."); return; }
        final android.content.Context app = a.getApplicationContext();
        final AlertDialog wait = working("Saving \"" + name + "\"", "Packing your setup…");
        new Thread(() -> {
            String err = null;
            try { RiceManager.saveCurrent(app, name, ids, skip); } catch (IOException | RuntimeException e) { err = RootfsManager.message(e); }
            final String e = err;
            final long bytes = RiceManager.fileFor(app, name).length();
            main.post(() -> {
                busy.set(false);
                wait.dismiss();
                if (e != null) toast("Save failed: " + e);
                else toast("Saved \"" + name + "\" (" + size(bytes) + ")");
                if (!gone()) changed.run();
            });
        }, "rice-save").start();
    }

    // ------------------------------------------------------------------ apply

    void apply(final File rice) {
        if (!ready() || !busy.compareAndSet(false, true)) { if (busy.get()) toast("Another rice job is running."); return; }
        final android.content.Context app = a.getApplicationContext();
        final AlertDialog wait = working("Opening rice", "Reading the file…");
        new Thread(() -> {
            final RiceManager.Info info = RiceManager.readInfo(rice);
            final List<String> missing = info.problem == null ? RiceManager.missingPackages(app, info) : new ArrayList<String>();
            main.post(() -> {
                busy.set(false);
                wait.dismiss();
                if (!gone()) showApplyDialog(rice, info, missing);
            });
        }, "rice-read").start();
    }

    private void showApplyDialog(final File rice, final RiceManager.Info info, List<String> missing) {
        final String title = info.name.isEmpty() ? RiceManager.nameOf(rice) : info.name;
        if (info.problem != null) {
            new Dlg(a).setTitle(title).setMessage(info.problem).setPositiveButton("OK", null).show();
            return;
        }
        LinearLayout b = box();
        String wm = info.prefs.get("wm");
        StringBuilder head = new StringBuilder();
        if (wm != null) head.append("Desktop: ").append(Prefs.WM_OPENBOX.equals(wm) ? "Openbox" : "IceWM").append('\n');
        head.append("Your current settings are saved first as \"").append(RiceManager.BACKUP_NAME).append("\" so you can go back. ");
        head.append("Ticked settings replace what is there; themes, icons, fonts and wallpapers are added next to yours. Stop the desktop first if it is running.");
        b.addView(text(head.toString(), 12, Ui.DIM));

        final List<String> ids = new ArrayList<>();
        final List<CheckBox> boxes = new ArrayList<>();
        for (RiceManager.Cat c : RiceManager.CATS) {
            if (!info.cats.contains(c.id)) continue;
            long[] s = info.perCat.get(c.id);
            String label = c.label + (s == null ? "" : "  ·  " + s[0] + (s[0] == 1 ? " item, " : " items, ") + size(s[1]));
            CheckBox cb = check(label, c.byDefault);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            b.addView(cb, lp);
            ids.add(c.id);
            boxes.add(cb);
        }
        if (!info.startup.isEmpty() || info.execCount > 0) {
            StringBuilder w = new StringBuilder("This rice starts programs with the desktop");
            if (!info.startup.isEmpty()) {
                w.append(":");
                for (String s : info.startup) w.append("\n  ").append(s);
            } else {
                w.append(".");
            }
            if (info.execCount > 0) w.append("\n\nIt also holds ").append(info.execCount).append(" runnable script").append(info.execCount == 1 ? "" : "s").append('.');
            w.append("\nOnly apply rices from people you trust.");
            TextView t = text(w.toString(), 12, Ui.TEXT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            b.addView(t, lp);
        }
        if (!missing.isEmpty()) {
            TextView t = text("Not installed in Kali yet: " + String.join(", ", missing) + ". You can install them after applying.", 12, Ui.DIM);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            b.addView(t, lp);
        }
        new Dlg(a).setTitle("Apply \"" + title + "\"").setView(scroll(b))
                .setPositiveButton("Apply", (d, w) -> {
                    Set<String> chosen = new LinkedHashSet<>();
                    for (int i = 0; i < ids.size(); i++) if (boxes.get(i).isChecked()) chosen.add(ids.get(i));
                    if (chosen.isEmpty()) { toast("Nothing was ticked."); return; }
                    doApply(rice, title, chosen);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void doApply(final File rice, final String title, final Set<String> chosen) {
        if (!busy.compareAndSet(false, true)) { toast("Another rice job is running."); return; }
        final android.content.Context app = a.getApplicationContext();
        final AlertDialog wait = working("Applying \"" + title + "\"", "Writing files into Kali…");
        new Thread(() -> {
            RiceManager.Result res = null;
            String err = null;
            List<String> missing = new ArrayList<>();
            try {
                res = RiceManager.applySaved(app, rice, chosen);
                missing = RiceManager.missingPackages(app, RiceManager.readInfo(rice));
            } catch (IOException | RuntimeException e) {
                err = RootfsManager.message(e);
            }
            final RiceManager.Result r = res;
            final String e = err;
            final List<String> miss = missing;
            main.post(() -> {
                busy.set(false);
                wait.dismiss();
                if (gone()) return;
                changed.run();
                if (e != null) {
                    new Dlg(a).setTitle("Could not apply").setMessage(e + "\n\nYour previous settings were backed up as \"" + RiceManager.BACKUP_NAME + "\" if the backup step finished.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                StringBuilder s = new StringBuilder("Applied: " + r.files + " files" + (r.links > 0 ? ", " + r.links + " links" : "") + ".");
                if (r.skipped > 0) s.append(" ").append(r.skipped).append(" entries were skipped because they are not allowed or clash with existing folders.");
                s.append("\n\nRestart the desktop to see it (VNC server screen, Restart).");
                if (!VncManager.isInstalled(a)) s.append("\n\nThe desktop itself is not installed yet: open the VNC server screen and tap Install.");
                AlertDialog.Builder db = new Dlg(a).setTitle("Applied").setMessage(s.toString()).setPositiveButton("OK", null);
                if (!miss.isEmpty()) {
                    final String cmd = RiceManager.installCommand(miss);
                    db.setMessage(s + "\n\nStill missing in Kali: " + String.join(", ", miss) + ".")
                            .setNeutralButton("Install them", (x, w) -> MainActivity.runInTerminal(a, cmd));
                }
                db.show();
            });
        }, "rice-apply").start();
    }

    // ------------------------------------------------------------------ export / import / delete

    void export(File rice) {
        pendingExport = rice;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, rice.getName());
        a.startActivityForResult(i, reqExport);
    }

    void importStart() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        a.startActivityForResult(i, reqImport);
    }

    /** Call from onActivityResult; true when the result was ours. */
    boolean handle(int req, int res, Intent data) {
        if (req != reqExport && req != reqImport) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) { pendingExport = null; return true; }
        Uri uri = data.getData();
        if (req == reqExport) exportTo(uri); else importFrom(uri);
        return true;
    }

    private void exportTo(final Uri uri) {
        final File src = pendingExport;
        pendingExport = null;
        if (src == null || !src.isFile()) { toast("Nothing to export."); return; }
        final ContentResolver cr = a.getContentResolver();
        new Thread(() -> {
            String err = null;
            try (InputStream in = Files.newInputStream(src.toPath()); OutputStream out = cr.openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("The destination could not be opened");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (IOException | RuntimeException e) {
                err = RootfsManager.message(e);
            }
            final String e = err;
            main.post(() -> toast(e == null ? "Exported \"" + RiceManager.nameOf(src) + "\"" : "Export failed: " + e));
        }, "rice-export").start();
    }

    private static final long MAX_IMPORT = 1L << 30;

    private String displayName(Uri uri) {
        try (android.database.Cursor c = a.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) { }
        return null;
    }

    private void importFrom(final Uri uri) {
        if (!busy.compareAndSet(false, true)) { toast("Another rice job is running."); return; }
        final android.content.Context app = a.getApplicationContext();
        final String shown = displayName(uri);
        final AlertDialog wait = working("Importing", "Copying and checking the file…");
        new Thread(() -> {
            File lib = RiceManager.library(app);
            //noinspection ResultOfMethodCallIgnored
            lib.mkdirs();
            File tmp = new File(lib, ".import.tmp");
            String err = null;
            File result = null;
            String finalName = null;
            try {
                try (InputStream in = app.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) throw new IOException("The file could not be opened");
                    byte[] buf = new byte[64 * 1024];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_IMPORT) throw new IOException("The file is bigger than 1 GB");
                        out.write(buf, 0, n);
                    }
                }
                RiceManager.Info info = RiceManager.readInfo(tmp);
                if (info.problem != null) throw new IOException(info.problem);
                String base = info.name;
                if (!RiceManager.validName(base) && shown != null) {
                    base = shown.endsWith(RiceManager.EXT) ? shown.substring(0, shown.length() - RiceManager.EXT.length()) : shown;
                }
                if (!RiceManager.validName(base)) base = "Imported rice";
                if (base.equals(RiceManager.BACKUP_NAME)) base = "Imported backup";
                String candidate = base;
                for (int i = 2; RiceManager.fileFor(app, candidate).exists() && i < 100; i++) candidate = base + " (" + i + ")";
                result = RiceManager.fileFor(app, candidate);
                Files.move(tmp.toPath(), result.toPath(), StandardCopyOption.REPLACE_EXISTING);
                finalName = candidate;
            } catch (IOException | RuntimeException e) {
                err = RootfsManager.message(e);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            final String e = err;
            final File f = result;
            final String nm = finalName;
            main.post(() -> {
                busy.set(false);
                wait.dismiss();
                if (gone()) return;
                if (e != null) {
                    new Dlg(a).setTitle("Import failed").setMessage(e).setPositiveButton("OK", null).show();
                    return;
                }
                changed.run();
                new Dlg(a).setTitle("Imported \"" + nm + "\"").setMessage("It is in your saved rices now. Apply it?")
                        .setPositiveButton("Apply…", (x, w) -> apply(f))
                        .setNegativeButton("Later", null).show();
            });
        }, "rice-import").start();
    }

    void delete(final File rice) {
        new Dlg(a).setTitle("Delete \"" + RiceManager.nameOf(rice) + "\"?")
                .setMessage("This removes the saved file. If you exported it, that copy is not affected.")
                .setPositiveButton("Delete", (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    rice.delete();
                    changed.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    void installTools() {
        if (!ready()) return;
        final String cmd = "apt update && apt install -y " + RiceManager.TOOLS;
        new Dlg(a).setTitle("Install ricing tools?")
                .setMessage("lxappearance (GTK themes, icons, cursors, fonts), obconf (Openbox themes and behaviour), rofi, feh (wallpapers), "
                        + "tint2 (panel) and xdotool.\n\nThis runs in a terminal tab:\n\n" + cmd)
                .setPositiveButton("Install", (d, w) -> MainActivity.runInTerminal(a, cmd))
                .setNegativeButton("Cancel", null).show();
    }

    /** One line of facts for a saved rice, read from the file. */
    static String summary(File rice, RiceManager.Info info) {
        if (info.problem != null) return info.problem;
        StringBuilder s = new StringBuilder(RiceManager.describe(info));
        s.append("  ·  ").append(size(rice.length()));
        String wm = info.prefs.get("wm");
        if (wm != null) s.append("  ·  ").append(Prefs.WM_OPENBOX.equals(wm) ? "Openbox" : "IceWM");
        return s.toString();
    }

}
