/*
 * HyperDesktop Unlock
 * Copyright (c) 2026 Maurizio Laschi (the-marox)
 * SPDX-License-Identifier: MIT
 */
package io.maurizio.hyperdesktop;

import android.app.Dialog;
import android.content.Context;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Hook sulla finestra "Connettiti a un display esterno" di SystemUI.
 *
 * In AOSP (ExternalDisplayConnectionDialogDelegate, layout connected_display_dialog_2)
 * ci sono sempre due pulsanti:
 *   R.id.start_desktop_mode -> onStartDesktopClickListener  -> enableForDesktop()
 *   R.id.start_mirroring    -> onStartMirroringClickListener -> enableForMirroring()
 *
 * Su HyperOS il pulsante Desktop (etichettato "Computer") esiste ma e' GONE e
 * non gli viene collegato il listener: lo rendiamo visibile e gli colleghiamo
 * il listener originale, pescato dal campo privato del delegate.
 */
public final class ConnectDialogHooks {

    private static final String DELEGATE =
        "com.android.systemui.display.ui.view.ExternalDisplayConnectionDialogDelegate";

    public static void hook(LoadPackageParam lp) {
        Class<?> cls = XposedHelpers.findClassIfExists(DELEGATE, lp.classLoader);
        if (cls == null) { XLog.i("[dialog] delegate NON trovato"); Diag.mark("dialog", false); return; }

        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals("onCreate")) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length > 0 && p.args[0] instanceof Dialog) {
                                reveal((Dialog) p.args[0], p.thisObject);
                            }
                        } catch (Throwable t) { XLog.e("[dialog] reveal fallito", t); }
                    }
                });
                n++;
            } catch (Throwable t) { XLog.e("[dialog] hook onCreate fallito", t); }
        }
        Diag.mark("dialog", n > 0);
    }

    private static void reveal(Dialog dialog, Object delegate) {
        Context ctx = dialog.getContext();
        View btn = null;
        int id = ctx.getResources().getIdentifier("start_desktop_mode", "id", "com.android.systemui");
        if (id != 0) btn = dialog.findViewById(id);
        if (btn == null) {
            try { btn = (View) XposedHelpers.getObjectField(delegate, "desktopButton"); }
            catch (Throwable ignored) {}
        }
        if (btn == null) {
            XLog.i("[dialog] pulsante Desktop NON trovato (id=" + id + ")");
            return;
        }

        btn.setVisibility(View.VISIBLE);
        btn.setEnabled(true);
        btn.setClickable(true);
        btn.setFocusable(true);
        View v = btn;
        while (v.getParent() instanceof View) {
            v = (View) v.getParent();
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        }

        final View.OnClickListener orig = findDesktopListener(delegate);
        if (orig == null) {
            StringBuilder fields = new StringBuilder();
            for (Field f : delegate.getClass().getDeclaredFields()) fields.append(' ').append(f.getName());
            XLog.i("[dialog] listener Desktop NON trovato; campi del delegate:" + fields);
            return;
        }
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v2) {
                XLog.i("[dialog] tap su Computer");
                try { orig.onClick(v2); }
                catch (Throwable t) { XLog.e("[dialog] listener Desktop ha lanciato", t); }
            }
        });
        XLog.i("[dialog] pulsante Computer visibile e collegato");
    }

    private static View.OnClickListener findDesktopListener(Object delegate) {
        for (String name : new String[] {"onStartDesktopClickListener", "onStartDesktop"}) {
            try {
                Object o = XposedHelpers.getObjectField(delegate, name);
                if (o instanceof View.OnClickListener) return (View.OnClickListener) o;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private ConnectDialogHooks() {}
}
