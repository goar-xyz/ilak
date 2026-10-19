package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Dialog for choosing which keys the extra-keys row shows and in what order. */
final class ExtraKeysUi {
    private ExtraKeysUi() { }

    static void show(Activity a, Runnable saved) {
        final List<String> ids = new ArrayList<>(ExtraKeys.ids(Prefs.extraKeysLayout(a)));
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        final int pad = Ui.dp(a, 16);
        list.setPadding(pad, Ui.dp(a, 8), pad, 0);

        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 360)));

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < ids.size(); i++) {
                final int idx = i;
                final ExtraKeys.Def d = ExtraKeys.byId(ids.get(i));
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView pill = Ui.key(a, d.label);
                row.addView(pill);
                TextView name = new TextView(a);
                name.setText(d.name);
                name.setTextColor(Ui.TEXT);
                name.setTextSize(13);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                nlp.leftMargin = Ui.dp(a, 10);
                row.addView(name, nlp);
                row.addView(action(a, "▲", idx > 0, () -> { swap(ids, idx, idx - 1); render[0].run(); }));
                row.addView(action(a, "▼", idx < ids.size() - 1, () -> { swap(ids, idx, idx + 1); render[0].run(); }));
                row.addView(action(a, "✕", ids.size() > 1, () -> { ids.remove(idx); render[0].run(); }));
                list.addView(row);
            }
        };
        render[0].run();

        final AlertDialog dialog = new Dlg(a).setTitle("Extra keys").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Prefs.sp(a).edit().putString(Prefs.EXTRA_KEYS_LAYOUT, ExtraKeys.join(ids)).apply();
                    if (saved != null) saved.run();
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Add key…", null)
                .create();
        dialog.show();
        // the Add button must not close the dialog, so it is wired after show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> pickToAdd(a, ids, render[0]));
        Dlg.style(dialog);
        TextView reset = Ui.button(a, "Reset to default", Ui.SECONDARY);
        reset.setOnClickListener(v -> { ids.clear(); ids.addAll(ExtraKeys.ids("")); render[0].run(); });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(pad, Ui.dp(a, 8), pad, 0);
        box.addView(reset, rlp);
    }

    private static TextView action(Activity a, String glyph, boolean enabled, Runnable r) {
        TextView t = new TextView(a);
        t.setText(glyph);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(enabled ? Ui.TEXT : Ui.DIM);
        t.setAlpha(enabled ? 1f : 0.35f);
        t.setMinWidth(Ui.dp(a, 40));
        t.setMinHeight(Ui.dp(a, 40));
        if (enabled) t.setOnClickListener(v -> r.run());
        return t;
    }

    private static void swap(List<String> l, int i, int j) {
        String t = l.get(i); l.set(i, l.get(j)); l.set(j, t);
    }

    private static void pickToAdd(Activity a, List<String> ids, Runnable changed) {
        final List<ExtraKeys.Def> free = new ArrayList<>();
        for (ExtraKeys.Def d : ExtraKeys.all()) if (!ids.contains(d.id)) free.add(d);
        if (free.isEmpty()) return;
        final String[] names = new String[free.size()];
        for (int i = 0; i < names.length; i++) names[i] = free.get(i).label + "   " + free.get(i).name;
        final boolean[] on = new boolean[names.length];
        new Dlg(a).setTitle("Add keys").setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Add", (d, w) -> {
                    for (int i = 0; i < on.length; i++) if (on[i]) ids.add(free.get(i).id);
                    changed.run();
                })
                .setNegativeButton("Cancel", null).show();
    }
}
