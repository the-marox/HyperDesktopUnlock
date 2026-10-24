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
- With a dock app: the dock starts on the monitor by itself, the phone screen
  turns off, and Alt+Tab, monitor screenshots and other desktop keyboard
  shortcuts work. All of it can be changed in the module's settings app.
- Optional: the phone becomes a **touchpad** for the monitor (its screen stays
  black), with a real system pointer, tap to click, two-finger scroll and right click.
  Without a physical keyboard, the on-screen **keyboard appears on the phone** below the touchpad.
- **Quick Settings tiles**: *Monitor* (Computer ↔ Mirroring without unplugging) and *Touchpad*.
- A **diagnostics report** in the settings app, ready to paste into a GitHub issue.

Pair it with a secondary-display launcher such as
[SmartDock](https://github.com/axel358/smartdock) (recommended, works very
well) or [Taskbar](https://github.com/farmerbb/Taskbar) to get a start menu,
taskbar, app switching and Back/Home buttons on the monitor.

## What you don't get (yet)

- **AOSP desktop window decorations** (title bar, free resizing, snapping).
  Xiaomi's SystemUI does not expose them, and forcing the framework gates
  crashed SystemUI on HyperOS 4 beta with no benefit.
- **Resizable windows.** HyperOS can only turn apps into its fixed-size,
  phone-shaped "floating windows", so apps run full screen on the monitor.
  (The experimental code for both is in the project history, tag `v1.4.1`.)
- A launcher on the monitor. HyperOS Home is single-display; use SmartDock/Taskbar.

---

## How it works

Everything needed is already in the firmware; Xiaomi disables it in four
places. The module hooks each one (see `app/src/main/java/.../`):

| Hook | Process | What Xiaomi did | What the module does |
|---|---|---|---|
| `ConnectDialogHooks` | SystemUI | In `ExternalDisplayConnectionDialogDelegate` the Desktop button (`R.id.start_desktop_mode`) is set `GONE` **and its click listener is never attached** | Makes it visible, clickable, and wires the original `onStartDesktopClickListener` from the ViewModel → `enableForDesktop()` |
| `SettingsGuardHooks` | system_server | 3 ms after SystemUI writes `Settings.Secure.mirror_built_in_display = 0`, the framework writes it back to `1` | Blocks writes of `1` coming from system_server (user choices from SystemUI/Settings still work); also protects `force_desktop_mode_on_external_displays` / `enable_freeform_support` from being reset |
| `MirrorPolicyHooks` | system_server | `com.android.server.display.SecondaryDisplayPolicy` (not AOSP) forces mirroring from `DisplayManagerService$SettingsObserver.onChange` | `forceEnableMirrorBuiltInDisplaySettingIfNeeded()` and `enableMirrorBuiltInDisplaySetting()` become no-ops |
| `DesktopModeHooks` | SystemUI + system_server | `DesktopModeStatus.isDesktopModeSupported/canEnterDesktopMode` are `false`; `DesktopModeFlags.getToggleOverride()` is `OVERRIDE_UNSET` (the "Enable desktop experience" developer option is hidden) | Forces them to `true` / `OVERRIDE_ON`; per-display checks (`…OnDisplay(displayId)`) only for displays other than the phone's. The framework's global `DesktopModeHelper` gates are left alone |
| `LauncherHooks` | SystemUI | HyperOS Home is single-display, so the monitor stays empty | When the Desktop choice is applied (`applyConnectionChoice(enableMirroring=false)`, dialog or *Don't ask again*), launches the dock's desktop activity (`SECONDARY_HOME`, e.g. SmartDock `SecondaryLauncherActivity`) on the external display via `ActivityOptions.setLaunchDisplayId`, then any extra apps you listed in the settings. Dock package from the settings (default `cu.axel.smartdock`) |
| `PhoneScreen` (+ `ExternalDisplayGate`) | system_server | Pressing power puts the whole device to sleep, monitor included | ~2 s after an external **physical** display becomes a desktop display, turns off only the phone's panel (`SurfaceControl.setDisplayPowerMode(internalToken, OFF)`, same technique as scrcpy `--turn-screen-off`), or, with the touchpad option, keeps it on but black (see `Touchpad`); waits for unlock if the phone is locked; restores it when the cable is unplugged, on Win+P and on an incoming call. Once per connection: lock + wake the phone to get its screen back. Virtual displays (screen recording, cast) are ignored |
| `ModuleControl` | system_server + SystemUI | — | Answers the settings app and the Quick Settings tiles: a signature-permission ordered broadcast, targeted at `android` and then `com.android.systemui`, returns status, the `[diag]` line and recent log lines kept in memory (`XLog`); also switches Computer ↔ Mirroring (`mirror_built_in_display`, bypassing the module's own guard for that write only) and the touchpad |
| `Touchpad` + `VirtualMouse` | system_server | With the panel powered off the touch sensor sleeps too, and injected mouse events reach apps but don't move the visible pointer | Keeps the panel on under an opaque black, non-focusable `TYPE_SYSTEM_ERROR` window (above status/navigation bars; on OLED black pixels are off at any brightness, and forcing brightness to 0 would put the touch sensor in low-power mode). Touches are read **directly from the touchscreen driver** (`/dev/input/eventN`, path and raw axes taken from InputReader's state; `RawTouch`), because HyperOS cancels the touch stream at the window as soon as the virtual mouse moves; the window's own touches are only a fallback. They drive a **virtual HID mouse** created through `/dev/uhid` (like scrcpy `--mouse=uhid`) and tied to the monitor's display (`addUniqueIdAssociationByPort`), so the real system pointer moves, with the system pointer speed. Falls back to powering the panel off if `/dev/uhid` is unavailable. While it is shown, the monitor's IME policy (`DisplayContent.getImePolicy()` → `FALLBACK_DISPLAY`) and the input-method service's display choice (`computeImeDisplayIdForTarget`) send the on-screen keyboard to the phone, in memory only and only when no physical keyboard is connected (otherwise every hardware key would go through an IME hidden behind the black screen); the overlay finds the visible `TYPE_INPUT_METHOD` window among the phone's windows (`forAllWindows`), shrinks above it, and the driver reader ignores fingers that land on the keyboard |
| `DockAccessibility` | system_server | Enabled accessibility services always start at boot, so the dock would also show up on the phone | Enables the dock's accessibility service (found dynamically in the dock package, e.g. SmartDock `DockService`) when the desktop starts, disables it when the cable is unplugged, and after boot if no monitor is attached. Also re-enables it if HyperOS turned it off |
| `DesktopSession` | system_server | The monitor keeps its default density (tiny UI on a 4K screen), can run at the wrong resolution/refresh, and the screen timeout can turn it off while you read | On connect, applies the monitor **density** (`setForcedDisplayDensityForUser`, like `wm density`), **logical resolution** (`setForcedDisplaySize`, like `wm size`) and, best-effort, **refresh rate** (`Display.setUserPreferredDisplayMode`, only choosing a mode the monitor actually reports — never a made-up one, so it can't blank the screen) chosen in the settings, each only if it differs from the current value; and, if enabled, holds a screen wake lock while the desktop is active. Changing any of them in the settings app triggers a signature-protected broadcast so system_server applies it at once |
| `AltTabHooks` | system_server | Alt+Tab is handled by the HyperOS launcher, which lives only on the phone: on the monitor it does nothing | Intercepts Alt+Tab at `InputManagerService.interceptKeyBeforeQueueing` **only** while a desktop display is active and has focus. While you hold Alt, a Windows-style preview bar of the open apps is drawn on the monitor (`SwitcherOverlay`, added from system_server); Tab moves the highlight, Alt+Shift+Tab the other way, and the app is switched when you release Alt. The bar can be turned off, then each Tab switches instantly. If the bar can't be drawn the module falls back to instant switching. On the phone, Alt+Tab is untouched |
| `DesktopShortcuts` | system_server | HyperOS screenshots only capture the phone display; no way to move apps between displays from the keyboard | More keyboard shortcuts at the same input hook as Alt+Tab, only while a desktop display is active (see *Keyboard shortcuts*). Monitor screenshots use `DisplayManagerInternal.systemScreenshot(displayId)` and are handed over a pipe to the module app (`ShotProvider`, protected by `INTERACT_ACROSS_USERS_FULL`), which saves them through MediaStore to `Pictures/Screenshots` (MediaProvider refuses to open files for writing to system_server). When a Win combination is consumed, the Win key-up is consumed too, so the system/dock does not treat it as "Win pressed alone" |
| `SettingsHooks` | Settings | The hidden AOSP *External displays* page crashes in `DisplayTopologyPreferenceController` (lateinit `paneContent` never set by the miuix adapter) | Marks that controller unavailable so the page renders |

Settings are written by the module app (`SettingsActivity`) and read by the
hooks through LSPosed's *new XSharedPreferences* (`xposedsharedprefs`), at
the moment they are used, so changes need no reboot.

Logs go to logcat with the tag `HyperDesktop` (`adb logcat -s HyperDesktop`)
and to LSPosed's log. At boot each process prints one summary line:

```
[diag] system_server v1.5.0: gate OK, desktop-flags OK, mirror-guard OK, mirror-policy OK, keys OK | settings: saved
```

`MISSING` there means a hook did not attach (usually because a firmware
update renamed something) — please include that line in bug reports.

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
5. Optional: open **HyperDesktop Unlock** from the app drawer (or the module's
   settings in LSPosed) to change the settings.

## Use

1. Connect the monitor. The dialog now shows **Computer** above *Mirroring*.
2. Tap **Computer** (tick *Don't ask again* to make it automatic).
3. The monitor becomes a second display with its own status bar and cursor.
   If [SmartDock](https://github.com/axel358/smartdock) (or the dock set in the
   settings) is installed, the module starts it on the monitor automatically.
   Otherwise launch apps from a shell:
   ```
   am start --display 2 -n com.android.settings/.Settings
   ```
   (`dumpsys display | grep "Display [0-9]*:"` lists display ids.)
4. The phone's own screen turns off automatically a couple of seconds later; the phone stays awake.
   Need the phone screen? Press power twice (lock + wake), or **Win+P**: it comes back and stays on
   until the next connection. It also comes back by itself for an incoming call.
   With **Use the phone as a touchpad** on, the screen stays black instead and works as a touchpad
   (see below).
5. Everything too small or too big on the monitor? Set **Monitor density** in the settings
   (4K monitors usually look right between 240 and 360 dpi); it applies immediately.
6. **Back** on the monitor: mouse side button, `Esc` on a keyboard, the
   launcher's Back button, or `input -d 2 keyevent KEYCODE_BACK`.

### Settings

| Setting | Default | |
|---|---|---|
| Start the dock on the monitor | on | Launches the dock's desktop on the monitor when you choose Computer |
| Dock app (package name) | `cu.axel.smartdock` | e.g. `com.farmerbb.taskbar` for Taskbar |
| Also open on the monitor | none | Extra apps (package names) launched on the monitor after the dock, on connect |
| Manage the dock's accessibility service | on | On in desktop mode, off when unplugged and after boot |
| Turn off the phone screen in desktop mode | on | Power twice or Win+P brings it back |
| Use the phone as a touchpad | off | The phone screen stays black instead of off and works as a touchpad for the monitor |
| Touchpad sensitivity | 6 of 20 | Slider; higher = the pointer travels further. The system pointer speed applies on top |
| Keyboard on the phone | on | With the touchpad on and no physical keyboard, the on-screen keyboard opens on the phone, below the touchpad |
| Keep the monitor on | off | Ignores the screen timeout in desktop mode; the power button still works |
| Monitor density (dpi) | not set | 100–800, or 0 for the monitor's default. Never touched until you set it; clear the field and Apply to stop changing it |
| Monitor resolution | not set | Picked from the sizes that fit the connected monitor, or Native. Lower = bigger UI, lighter on the GPU |
| Monitor refresh rate (Hz) | not set | Picked from the rates the monitor reports, or Native |

### Touchpad (phone screen black, touch on)

| Gesture | Action |
|---|---|
| One finger | Move the pointer on the monitor |
| Tap | Click |
| Tap, then touch and move | Drag |
| Two fingers | Scroll (natural direction, like on the phone) |
| Two-finger tap | Right click |
| Three fingers | Phone back to normal (so does Win+P) |

Typing: tap a text field on the monitor; if no physical keyboard is connected,
the on-screen keyboard opens on the phone and the touchpad shrinks above it.

Pointer speed: *Touchpad sensitivity* in the module's settings, on top of the
system's pointer speed. The black screen shows a hint of the gestures for
15 seconds when it appears, then nothing.
| Desktop keyboard shortcuts | on | Alt+Tab and all the shortcuts below |
| Alt+Tab preview bar | on | Hold Alt for a Windows-style bar of the open apps; off = switch instantly on each Tab |

### Keyboard shortcuts (physical keyboard, desktop mode only)

| Keys | Action |
|---|---|
| **Alt+Tab** | Switch apps on the monitor, with a preview bar while you hold Alt (can be turned off); keep Alt held and press Tab again to go further back, **Alt+Shift+Tab** for the other direction |
| **Print Screen** or **Win+Shift+S** | Screenshot of the monitor → `Pictures/Screenshots` (with focus on the phone: normal HyperOS screenshot) |
| **Win+Shift+Arrow** | Move the foreground app monitor → phone, or phone → monitor |
| **Alt+F4** | Close the foreground app on the monitor |
| **Win+D** | Show the dock's desktop on the monitor (starts it if it was closed) |
| **Win+Backspace** | Back, on the monitor |
| **Win+1 … 9** | Bring the Nth app open on the monitor to the front |
| **Win+P** | Turn the phone screen on/off |

To go back to mirroring just pick *Mirroring* in the dialog (or clear the
saved choice in Settings → *External displays*, reachable via
`am start -n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.connecteddevice.display.ExternalDisplayPreferenceFragment`).

### Quick Settings tiles

Edit the tiles in the notification shade and add:

- **Monitor**: switches the connected monitor between *Computer* (desktop) and
  *Mirroring* without unplugging; going back to Computer starts the dock again.
- **Touchpad**: turns the phone into the touchpad right away (and back), and
  remembers the choice for the next connection.

Both need the phone unlocked.

## If something goes wrong

- Open the module's settings → **Diagnostics**. The status line says whether the
  module is running in the system, whether a reboot is needed, and which parts
  did not attach. **Create diagnostic report** collects the phone model, Android
  and HyperOS version, the settings and the module's recent log lines from the
  system and SystemUI; copy it, share it, or tap *Report on GitHub* and paste it.
- Or check the `[diag]` lines: `adb logcat -s HyperDesktop | grep diag`.
- LSPosed disables a module automatically after repeated system crashes.
- Otherwise, with the phone plugged into a PC while booting:
  ```
  adb wait-for-device
  adb shell pm uninstall io.maurizio.hyperdesktop
  ```
  Uninstalling removes the module; nothing else is modified (no system
  partition changes). The persistent side effects are
  `Settings.Secure.mirror_built_in_display = 0` (set it back to `1` to restore
  the stock behaviour without the module) and, if you set one, the monitor
  density (`adb shell wm density reset -d <id>`).

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

If your device differs, the `[diag]` line tells you which hook did not attach.
The discovery logging used above (every call to the hooked classes with
arguments and results, stack traces of settings writes) is in the project
history up to tag `v1.4.1`.

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
[SmartDock](https://github.com/axel358/smartdock): il modulo lo avvia da solo
sul monitor, spegne lo schermo del telefono e aggiunge Alt+Tab, screenshot del
monitor e altre scorciatoie da tastiera. Dalla 1.5.0 c'è un'app di
impostazioni (in italiano) per scegliere il dock, lo spegnimento del telefono,
la densità, risoluzione e frequenza del monitor, "tieni acceso" e le
scorciatoie. Dalla 1.9 il telefono può fare da touchpad: lo schermo resta
nero, un dito muove il puntatore, un tocco fa clic, due dita scorrono. Dalla
1.10 senza tastiera fisica la tastiera a schermo compare sul telefono, ci sono
i riquadri rapidi *Monitor* (Computer ↔ Mirroring) e *Touchpad*, e nelle
impostazioni c'è un report diagnostico da allegare alle segnalazioni. Le finestre
ridimensionabili in stile AOSP non sono disponibili su questa build: Xiaomi
non le espone e le finestre "mobili" di HyperOS hanno dimensione fissa.
