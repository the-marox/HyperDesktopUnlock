package io.maurizio.hyperdesktop;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Hook sull'app Impostazioni.
 *
 * La pagina "Display esterni" (ExternalDisplayPreferenceFragment, raggiungibile
 * solo via `am start`) crasha appena colleghi un monitor con:
 *
 *   UninitializedPropertyAccessException: lateinit property paneContent
 *   has not been initialized
 *   in DisplayTopologyPreferenceController.getPaneContent()
 *
 * Il colpevole e' il riquadro "topologia" (lo schema per disporre i due schermi):
 * l'adapter di preferenze miuix di Xiaomi non inizializza il layout AOSP, e il
 * campo lateinit resta vuoto. Fix: segnaliamo quel controller come NON disponibile
 * (getAvailabilityStatus -> 3 = UNSUPPORTED_ON_DEVICE), cosi' il riquadro non
 * viene nemmeno costruito e il resto della pagina (incluso l'interruttore
 * MirrorPreference) puo' comparire.
 *
 * ATTENZIONE: questo rende la pagina usabile, ma l'interruttore mirroring
 * compare solo se il framework considera il desktop "supportato". Quindi la
 * leva vera resta DesktopModeHooks; questo e' il fix di contorno per non
 * perdere la pagina per strada.
 */
public final class SettingsHooks {

    static final boolean HIDE_TOPOLOGY_CRASH = true;
    static final boolean DISCOVERY_LOG       = false;  // spento: i metodi sono gia' noti

    // BasePreferenceController: AVAILABLE=0, AVAILABLE_UNSEARCHABLE=1,
    // CONDITIONALLY_UNAVAILABLE=2, UNSUPPORTED_ON_DEVICE=3, DISABLED_FOR_USER=4
    private static final int UNSUPPORTED_ON_DEVICE = 3;

    public static void hook(LoadPackageParam lpparam) {
        if (HIDE_TOPOLOGY_CRASH) {
            Class<?> ctrl = XposedHelpers.findClassIfExists(
                "com.android.settings.connecteddevice.display.DisplayTopologyPreferenceController",
                lpparam.classLoader);
            if (ctrl != null) {
                boolean hooked = false;
                for (Method m : ctrl.getDeclaredMethods()) {
                    if (m.getName().equals("getAvailabilityStatus")
                            && m.getParameterTypes().length == 0
                            && m.getReturnType() == int.class) {
                        try {
                            XposedBridge.hookMethod(m,
                                XC_MethodReplacement.returnConstant(UNSUPPORTED_ON_DEVICE));
                            XLog.i("[settings] topology disattivata (getAvailabilityStatus -> 3)");
                            hooked = true;
                        } catch (Throwable t) {
                            XLog.e("[settings] hook topology fallito", t);
                        }
                    }
                }
                if (!hooked) {
                    XLog.i("[settings] getAvailabilityStatus non trovato: controlla la firma col log discovery");
                    if (DISCOVERY_LOG) logMethods(ctrl, "DisplayTopologyPreferenceController");
                }
            } else {
                XLog.i("[settings] DisplayTopologyPreferenceController non trovato");
            }
        }

        if (DISCOVERY_LOG) {
            // Utile per capire come e' esposto il flag della topologia / la MirrorPreference
            logPresence(lpparam,
                "com.android.settings.connecteddevice.display.DesktopExperienceFlags");
            logPresence(lpparam,
                "com.android.settings.connecteddevice.display.MirrorPreference");
        }
    }

    private static void logPresence(LoadPackageParam lpparam, String cn) {
        Class<?> c = XposedHelpers.findClassIfExists(cn, lpparam.classLoader);
        XLog.i("[settings] " + cn + " -> " + (c != null ? "presente" : "assente"));
    }

    private static void logMethods(Class<?> c, String label) {
        for (Method m : c.getDeclaredMethods()) {
            XLog.i("[settings] " + label + "." + m.getName()
                    + " params=" + m.getParameterTypes().length
                    + " ret=" + m.getReturnType().getSimpleName());
        }
    }

    private SettingsHooks() {}
}
