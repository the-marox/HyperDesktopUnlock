package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Tracciatore + blocco degli enforcer sui settings del desktop.
 *
 * Sorveglia le scritture di:
 *   Secure.mirror_built_in_display                 (mirroring)
 *   Global.force_desktop_mode_on_external_displays (finestre libere su display esterni)
 *   Global.enable_freeform_support                 (supporto freeform, letto al boot)
 * e in system_server BLOCCA:
 *   - mirror_built_in_display = 1  (l'enforcer del mirroring)
 *   - i due Global riportati a 0   (reset del desktop mode)
 * Le scritture da altri processi (SystemUI, Impostazioni, shell) restano libere.
 */
public final class SettingsTraceHooks {

    static final boolean ENABLED               = true;
    static final boolean BLOCK_MIRROR_ENFORCER = true;
    static final boolean BLOCK_WM_RESET        = true;

    static final String KEY_MIRROR   = "mirror_built_in_display";
    static final String KEY_FORCE    = "force_desktop_mode_on_external_displays";
    static final String KEY_FREEFORM = "enable_freeform_support";
    private static final String[] KEYS = { KEY_MIRROR, KEY_FORCE, KEY_FREEFORM };

    public static void hook(LoadPackageParam lp, final String where) {
        if (!ENABLED) return;
        hookTable(lp, where, "android.provider.Settings$Secure");
        hookTable(lp, where, "android.provider.Settings$Global");
    }

    private static void hookTable(LoadPackageParam lp, final String where, String table) {
        Class<?> cls = XposedHelpers.findClassIfExists(table, lp.classLoader);
        if (cls == null) { XLog.i("[trace " + where + "] " + table + " non trovata"); return; }
        final boolean isSystemServer = "system_server".equals(where);
        final String tname = table.substring(table.indexOf('$') + 1);

        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            String nm = m.getName();
            if (!(nm.startsWith("putString") || nm.startsWith("putInt"))) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        int ki = indexOfKey(p.args);
                        if (ki < 0) return;
                        String key = String.valueOf(p.args[ki]);
                        Object val = (ki + 1 < p.args.length) ? p.args[ki + 1] : null;
                        String sval = String.valueOf(val);
                        XLog.i("[trace " + where + "] SCRITTURA " + tname + "." + key + " = " + sval
                                + " args=" + Arrays.toString(p.args));
                        logStack(where);
                        if (!isSystemServer) return;
                        if (BLOCK_MIRROR_ENFORCER && KEY_MIRROR.equals(key) && "1".equals(sval)) {
                            XLog.i("[trace " + where + "] >>> BLOCCATA " + key + "=1 (enforcer mirroring)");
                            p.setResult(Boolean.TRUE);
                        } else if (BLOCK_WM_RESET
                                && (KEY_FORCE.equals(key) || KEY_FREEFORM.equals(key))
                                && "0".equals(sval)) {
                            XLog.i("[trace " + where + "] >>> BLOCCATA " + key + "=0 (reset desktop)");
                            p.setResult(Boolean.TRUE);
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {}
        }
        XLog.i("[trace " + where + "] hook su " + n + " metodi put* di " + tname);
    }

    private static int indexOfKey(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            for (String k : KEYS) if (k.equals(args[i])) return i;
        }
        return -1;
    }

    private static void logStack(String where) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        int shown = 0;
        for (StackTraceElement e : st) {
            String s = e.toString();
            if (s.contains("io.maurizio.hyperdesktop") || s.contains("XposedBridge")
                    || s.contains("LSPosed") || s.contains("de.robv")) continue;
            XLog.i("[trace " + where + "]   at " + s);
            if (++shown >= 12) break;
        }
    }

    private SettingsTraceHooks() {}
}
