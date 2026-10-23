package io.maurizio.hyperdesktop;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
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
 * Sul device Xiaomi il pulsante Desktop (etichettato "Computer") esiste ma e'
 * GONE e, a giudicare dal fatto che non risponde al tocco, non gli viene
 * collegato il listener. v0.5: lo rendiamo visibile E gli colleghiamo il
 * listener pescandolo dal campo privato del delegate.
 */
public final class ConnectDialogHooks {

    static final boolean REVEAL_DESKTOP_BUTTON = true;   // mostra il pulsante nascosto
    static final boolean WIRE_DESKTOP_LISTENER = true;   // collega il listener al pulsante
    static final boolean FORCE_EXTENDED_MODE   = false;  // piano B: salta la finestra
    static final boolean DISCOVERY_LOG         = false;  // spento (v0.11)

    private static final String DELEGATE  =
        "com.android.systemui.display.ui.view.ExternalDisplayConnectionDialogDelegate";
    private static final String VIEWMODEL =
        "com.android.systemui.display.ui.viewmodel.ConnectingDisplayViewModel";

    public static void hook(LoadPackageParam lp) {
        hookDelegate(lp);
        hookViewModel(lp);
    }

    // ---------------------------------------------------------------
    // Finestra
    // ---------------------------------------------------------------
    private static void hookDelegate(LoadPackageParam lp) {
        Class<?> cls = XposedHelpers.findClassIfExists(DELEGATE, lp.classLoader);
        if (cls == null) { XLog.i("[dialog] delegate NON trovato"); return; }
        XLog.i("[dialog] trovato " + DELEGATE);
        if (!REVEAL_DESKTOP_BUTTON) return;

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
                XLog.i("[dialog] hook onCreate installato");
            } catch (Throwable t) { XLog.e("[dialog] hook onCreate fallito", t); }
        }
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

        XLog.i("[dialog] pulsante Desktop: visibility=" + btn.getVisibility()
                + " enabled=" + btn.isEnabled()
                + " clickable=" + btn.isClickable()
                + " hasListener=" + btn.hasOnClickListeners()
                + " text='" + textOf(btn) + "'");

        btn.setVisibility(View.VISIBLE);
        btn.setEnabled(true);
        btn.setClickable(true);
        btn.setFocusable(true);
        View v = btn;
        while (v.getParent() instanceof View) {
            v = (View) v.getParent();
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        }

        if (WIRE_DESKTOP_LISTENER) {
            final View.OnClickListener orig = findDesktopListener(delegate);
            if (orig != null) {
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v2) {
                        XLog.i("[dialog] TAP su Desktop -> invoco onStartDesktopClickListener");
                        final Context c = v2.getContext();
                        logMirror(c, "prima del tap");
                        try { orig.onClick(v2); }
                        catch (Throwable t) { XLog.e("[dialog] listener Desktop ha lanciato", t); }
                        Handler h = new Handler(Looper.getMainLooper());
                        for (final int ms : new int[] {300, 1500, 4000}) {
                            h.postDelayed(new Runnable() {
                                @Override public void run() { logMirror(c, "+" + ms + "ms"); }
                            }, ms);
                        }
                    }
                });
                XLog.i("[dialog] listener Desktop collegato (" + orig.getClass().getName() + ")");
            } else {
                XLog.i("[dialog] listener Desktop NON trovato. Campi del delegate:");
                for (Field f : delegate.getClass().getDeclaredFields()) {
                    XLog.i("[dialog]   " + f.getName() + " : " + f.getType().getName());
                }
            }
        }
        XLog.i("[dialog] pulsante Desktop reso visibile e cliccabile");
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

    private static String textOf(View v) {
        try { return String.valueOf(((TextView) v).getText()); }
        catch (Throwable t) { return "?"; }
    }

    /** Legge e logga Settings.Secure.mirror_built_in_display. */
    static void logMirror(Context c, String when) {
        try {
            int v = Settings.Secure.getInt(c.getContentResolver(), "mirror_built_in_display", -1);
            XLog.i("[dialog] mirror_built_in_display " + when + " = " + v);
        } catch (Throwable t) { XLog.e("[dialog] lettura mirror fallita", t); }
    }

    // ---------------------------------------------------------------
    // ViewModel
    // ---------------------------------------------------------------
    private static void hookViewModel(LoadPackageParam lp) {
        Class<?> cls = XposedHelpers.findClassIfExists(VIEWMODEL, lp.classLoader);
        if (cls == null) { XLog.i("[viewmodel] NON trovato"); return; }
        XLog.i("[viewmodel] trovato " + VIEWMODEL);

        for (final Method m : cls.getDeclaredMethods()) {
            final String name = m.getName();

            if (DISCOVERY_LOG) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            XLog.i("[viewmodel] " + name + "(" + argsOf(p.args) + ") = " + p.getResult());
                        }
                    });
                } catch (Throwable ignored) {}
            }

            if (FORCE_EXTENDED_MODE && name.equals("isInExtendedMode")
                    && m.getReturnType() == boolean.class) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true));
                    XLog.i("[viewmodel] override isInExtendedMode() -> true");
                } catch (Throwable t) { XLog.e("hook isInExtendedMode fallito", t); }
            }
        }
    }

    private static String argsOf(Object[] args) {
        if (args == null || args.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            String s = String.valueOf(args[i]);
            if (s.length() > 60) s = s.substring(0, 60) + "…";
            sb.append(s);
        }
        return sb.toString();
    }

    private ConnectDialogHooks() {}
}
