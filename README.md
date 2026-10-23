# HyperDesktop Unlock

An LSPosed module that turns an external monitor into a **separate display**
(not a mirror) on Xiaomi phones running **HyperOS 4 (Android 17)**, where
Xiaomi ships all of Android's connected-display / desktop code but keeps it
disabled.

Tested on **Xiaomi 15 (`dada`)**, HyperOS 4 beta based on Android 17, with a
Dell S2725QC over USB-C (DisplayPort Alt Mode).

> *Italiano in fondo.*

---

## What you get

- The native Android 17 **"Connect to external display"** dialog gains back its
  hidden **Desktop** button (Xiaomi labels it *"Computer"*).
- Choosing it makes the monitor a **real second display**: its own status bar,
  mouse cursor, apps launched on it stay on it. **No more 1:1 mirroring.**
- Xiaomi's enforcer that silently re-enables mirroring is neutralised.
- Tick *"Don't ask again"* and the phone goes straight to desktop on every connect.

Pair it with a secondary-display launcher such as
[SmartDock](https://github.com/axel358/smartdock) (recommended, works very
well) or [Taskbar](https://github.com/farmerbb/Taskbar) to get a start menu,
taskbar, app switching and Back/Home buttons on the monitor.

## What you don't get (yet)

- **AOSP desktop window decorations** (title bar, free resizing, snapping).
  Xiaomi's SystemUI does not expose them; forcing the framework gates crashes
  SystemUI with no benefit, so that path is disabled by default
  (`FORCE_FRAMEWORK_WHEN_EXTERNAL`).
- Windows can be made *freeform* (`WmHooks.ENABLED = true`), but then they are
  HyperOS "floating windows": **fixed size**, phone-shaped. Most people prefer
  full-screen apps on the monitor, which is the default.
- A launcher on the monitor. HyperOS Home is single-display; use SmartDock/Taskbar.
- A wallpaper on the monitor (it is black). Cosmetic.

---

## How it works

Everything needed is already in the firmware; Xiaomi disables it in four
places. The module hooks each one (see `app/src/main/java/.../`):

| Hook | Process | What Xiaomi did | What the module does |
|---|---|---|---|
| `ConnectDialogHooks` | SystemUI | In `ExternalDisplayConnectionDialogDelegate` the Desktop button (`R.id.start_desktop_mode`) is set `GONE` **and its click listener is never attached** | Makes it visible, clickable, and wires the original `onStartDesktopClickListener` from the ViewModel → `enableForDesktop()` |
| `SettingsTraceHooks` | system_server | 3 ms after SystemUI writes `Settings.Secure.mirror_built_in_display = 0`, the framework writes it back to `1` | Blocks writes of `1` coming from system_server (user choices from SystemUI/Settings still work); also protects `force_desktop_mode_on_external_displays` / `enable_freeform_support` from being reset |
| `MirrorPolicyHooks` | system_server | `com.android.server.display.SecondaryDisplayPolicy` (not AOSP) forces mirroring from `DisplayManagerService$SettingsObserver.onChange` | `forceEnableMirrorBuiltInDisplaySettingIfNeeded()` and `enableMirrorBuiltInDisplaySetting()` become no-ops |
| `DesktopModeHooks` | SystemUI + system_server | `DesktopModeStatus.isDesktopModeSupported/canEnterDesktopMode` are `false`; `DesktopModeFlags.getToggleOverride()` is `OVERRIDE_UNSET` (the "Enable desktop experience" developer option is hidden) | Forces them to `true` / `OVERRIDE_ON` in WMShell. `com.android.server.wm.DesktopModeHelper` is left alone (see above) |
| `LauncherHooks` | SystemUI | HyperOS Home is single-display, so the monitor stays empty | When the Desktop choice is applied (`applyConnectionChoice(enableMirroring=false)`, dialog or *Don't ask again*), launches a launcher on the external display via `ActivityOptions.setLaunchDisplayId`. Default package: `cu.axel.smartdock` (SmartDock); change `LAUNCHER_PACKAGE` for Taskbar etc. |
| `PhoneScreen` (+ `ExternalDisplayGate`) | system_server | Pressing power puts the whole device to sleep, monitor included | ~2 s after an external **physical** display becomes a desktop display, turns off only the phone's panel (`SurfaceControl.setDisplayPowerMode(internalToken, OFF)`, same technique as scrcpy `--turn-screen-off`); waits for unlock if the phone is locked; turns it back on when the cable is unplugged. Once per connection: lock + wake the phone to get its screen back. Virtual displays (screen recording, cast) are ignored. Toggle: `PhoneScreen.TURN_OFF_ON_DESKTOP` |
| `DockAccessibility` | system_server | Enabled accessibility services always start at boot, so the dock would also show up on the phone | Enables the dock's accessibility service (found dynamically in `LAUNCHER_PACKAGE`, e.g. SmartDock `DockService`) when the desktop starts, disables it when the cable is unplugged, and after boot if no monitor is attached. Also re-enables it if HyperOS turned it off. Toggle: `DockAccessibility.MANAGE` |
| `AltTabHooks` | system_server | Alt+Tab is handled by the HyperOS launcher, which lives only on the phone: on the monitor it does nothing | Intercepts Alt+Tab at `InputManagerService.interceptKeyBeforeQueueing` **only** while a desktop display is active and has focus. Alt+Tab switches to the previous app on the monitor; keep Alt held and press Tab again to go further back; Alt+Shift+Tab goes the other way. No preview overlay. On the phone, Alt+Tab is untouched. Toggle: `AltTabHooks.ENABLED` |
| `DesktopShortcuts` | system_server | HyperOS screenshots only capture the phone display; no way to move apps between displays from the keyboard | More keyboard shortcuts at the same input hook as Alt+Tab, only while a desktop display is active (see *Keyboard shortcuts*). Monitor screenshots use `DisplayManagerInternal.systemScreenshot(displayId)` and are saved through MediaStore to `Pictures/Screenshots`. When a Win combination is consumed, the Win key-up is consumed too, so the system/dock does not treat it as "Win pressed alone". Toggle: `DesktopShortcuts.ENABLED` |
| `SettingsHooks` | Settings | The hidden AOSP *External displays* page crashes in `DisplayTopologyPreferenceController` (lateinit `paneContent` never set by the miuix adapter) | Marks that controller unavailable so the page renders |

Optional, off by default:

| Toggle | File | Effect |
|---|---|---|
| `WmHooks.ENABLED` | `WmHooks.java` | Freeform windowing mode on non-default displays (`DisplayWindowSettings.getWindowingModeLocked` → `FREEFORM`, `DisplayContent.forceDesktopMode` → `true`, `ATMS.mSupportsFreeformWindowManagement` → `true`) |
| `DesktopModeHooks.FORCE_FRAMEWORK_WHEN_EXTERNAL` | `DesktopModeHooks.java` | Forces the framework `DesktopModeHelper` gates to `true` **only while an external display is attached** (`ExternalDisplayGate`), so boot is never affected. Crashes SystemUI on HyperOS 4 beta; kept for experimentation |
| `*.DISCOVERY_LOG` | each file | Logs every call to the hooked classes with arguments/results — how all of the above was found |
| `DesktopModeHooks.FORCE_DISABLE_RESTRICTIONS` | `DesktopModeHooks.java` | `enforceDeviceRestrictions()` → `false`. **Known to bootloop** when done through the global property; leave off |

All logs go through `XposedBridge.log` with the prefix `HyperDesktop:` —
`adb logcat | grep -i HyperDesktop`, or the LSPosed *Log* page.

---

## Requirements

- Xiaomi phone with **DisplayPort Alt Mode** over USB-C (Xiaomi 15 has it;
  most Redmi/POCO do not).
- **HyperOS 4 / Android 17** (the Android 17 connected-display code must be
  present; HyperOS 3 / Android 16 was not tested).
- Root (KernelSU/Magisk) with Zygisk and **LSPosed**.

## Install

1. Grab the APK from [Releases](../../releases) (or from the [`releases/`](releases/) folder) and install it.
2. LSPosed → Modules → enable **HyperDesktop Unlock**.
3. Scope: **System Framework**, **System UI**, **Settings**.
4. Reboot.

## Use

1. Connect the monitor. The dialog now shows **Computer** above *Mirroring*.
2. Tap **Computer** (tick *Don't ask again* to make it automatic).
3. The monitor becomes a second display with its own status bar and cursor.
   If [SmartDock](https://github.com/axel358/smartdock) is installed, the module
   starts it on the monitor automatically (1.1.0+). Otherwise launch apps from a shell:
   ```
   am start --display 2 -n com.android.settings/.Settings
   ```
   (`dumpsys display | grep "Display [0-9]*:"` lists display ids.)
4. The phone's own screen turns off automatically a couple of seconds later (1.2.0+); the phone stays awake
   and can be used as a touchpad by dock apps that support it. Need the phone screen? Press power twice
   (lock + wake): it comes back and stays on until the next connection.
5. **Alt+Tab** on a physical keyboard switches between the apps open on the monitor (1.3.0+): hold Alt and
   press Tab repeatedly to go further back, Alt+Shift+Tab to go the other way.
6. **Back** on the monitor: mouse side button, `Esc` on a keyboard, the
   launcher's Back button, or `input -d 2 keyevent KEYCODE_BACK`.

### Keyboard shortcuts (physical keyboard, desktop mode only)

| Keys | Action |
|---|---|
| **Alt+Tab** | Previous app on the monitor; hold Alt and press Tab again to go further back, **Alt+Shift+Tab** for the other direction |
| **Print Screen** or **Win+Shift+S** | Screenshot of the monitor → `Pictures/Screenshots` (with focus on the phone: normal HyperOS screenshot) |
| **Win+Shift+Arrow** | Move the foreground app monitor → phone, or phone → monitor |
| **Alt+F4** | Close the foreground app on the monitor |
| **Win+P** | Turn the phone screen on/off |

To go back to mirroring just pick *Mirroring* in the dialog (or clear the
saved choice in Settings → *External displays*, reachable via
`am start -n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.connecteddevice.display.ExternalDisplayPreferenceFragment`).

## If something goes wrong

- LSPosed disables a module automatically after repeated system crashes.
- Otherwise, with the phone plugged into a PC while booting:
  ```
  adb wait-for-device
  adb shell pm uninstall io.maurizio.hyperdesktop
  ```
  Uninstalling removes the module; nothing else is modified (no system
  partition changes). The only persistent side effect is
  `Settings.Secure.mirror_built_in_display = 0`; set it back to `1` to restore
  the stock behaviour without the module.

## Build

Android Studio, or:

```
gradle assembleDebug        # debug-signed
gradle assembleRelease      # signed with keystore.properties if present
```

Create `keystore.properties` (git-ignored) next to `settings.gradle`:

```
storeFile=hyperdesktop-release.jks
storePassword=...
keyAlias=hyperdesktop
keyPassword=...
```

## How it was found

Short version, in case you want to port it to another Xiaomi device:

1. `aflags list` showed every Android 17 desktop flag `enabled` but read-only.
2. The AOSP Settings page for external displays existed in `Settings.apk`
   (`grep -ao 'connecteddevice/display/[A-Za-z$]*'`) and opened via `am start`.
3. Hooking `DesktopModeStatus`/`DesktopModeFlags` with discovery logging showed
   that the connect decision never consulted them.
4. The AOSP source of `ExternalDisplayConnectionDialogDelegate` proves the
   dialog always has two buttons → the Desktop button was hidden on purpose;
   `hasOnClickListeners()` was `false` → re-wire it.
5. A `Settings.Secure.put*` tracer with stack traces caught
   `SecondaryDisplayPolicy` re-enabling mirroring 3 ms after the desktop choice.
6. Blocking that write and neutralising the policy gave the separate display.

If your device differs, enable `DISCOVERY_LOG` and look at the log while
connecting the monitor; the class and method names you need are usually there.

## Credits

Investigation and testing by Maurizio Laschi ([@the-marox](https://github.com/the-marox)) on a
Xiaomi 15; module written with the help of Claude (Anthropic).
Built on [LSPosed](https://github.com/LSPosed/LSPosed) and the classic Xposed API.

## Disclaimer

This modifies system behaviour at runtime on a rooted device. It works on the
firmware it was tested on and may break on others. You are responsible for
what you install. No warranty.

## License

Copyright © 2026 Maurizio Laschi ([@the-marox](https://github.com/the-marox)).
Released under the MIT License — see [LICENSE](LICENSE).

---

## 🇮🇹 In breve

Modulo LSPosed per Xiaomi con HyperOS 4 (Android 17): ridà alla finestra
"Connettiti a un display esterno" il pulsante **Computer** che Xiaomi
nasconde, e neutralizza il meccanismo (`SecondaryDisplayPolicy`) che riporta
il monitor in mirroring. Risultato: il monitor diventa un **secondo display
indipendente**, con barra di stato, mouse e app a schermo intero. Per avere
start menu, barra e cambio app sul monitor, abbina
[SmartDock](https://github.com/axel358/smartdock). Le finestre
ridimensionabili in stile AOSP non sono disponibili su questa build (Xiaomi
non le espone); le finestre "mobili" di HyperOS si possono attivare
(`WmHooks.ENABLED`) ma hanno dimensione fissa.
