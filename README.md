# TeleQuant

An Xposed/LSPosed module that makes selected apps believe the device is at a
place you choose — not just the GPS coordinate, but the cell towers, Wi-Fi
access points, Bluetooth beacons and satellites that place would have, all
consistent with one another.

Forked from [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter),
then largely rewritten. Package renamed to `com.cysindex.telequant`.

> **Verified on a device** (OnePlus CPH2645, Android 16, Vector) through the
> audit procedure below, with the synthetic profile loaded: position on all
> three providers, cell list and operator identity on both the pull and push
> paths, `ServiceState`, Wi-Fi scan and `NetworkCapabilities.getTransportInfo`,
> BLE scan, and the tile proxy and offline store. Three third-party map apps
> resolve to the spoofed position.
>
> What that does *not* cover: the GNSS callbacks and NMEA, proximity alerts,
> Wi-Fi RTT, and dual-SIM `SubscriptionInfo` — those compile and are hooked but
> have not been watched returning a spoofed value on hardware.

---

## Scope

Hooks are installed on Android framework APIs only — `android.location.*`,
`android.telephony.*`, `android.net.wifi.*`, `android.bluetooth.*`. There is no
per-app targeting and no code aimed at defeating any particular application's
checks. Which apps are affected is chosen by the user in the Xposed manager.

Every hook runs **inside the target app's own process** (`loadApp`), never in
`system_server`. This is why the module's recommended scope (`res/values/arrays.xml`)
is deliberately empty: declaring `android` there would install client-side hooks
into the system server, which is not what any of this code expects.

---

## Architecture

```
app/src/main/java/com/cysindex/telequant/
├── spoof/                    shared by both processes
│   ├── FakeEnvironment.kt      recorded environment + org.json (de)serialisation
│   ├── JitterEngine.kt         the wander, a pure function of the clock (see below)
│   ├── SyntheticEnvironment.kt position-only mode: real operator, fabricated towers
│   └── CoordinateTransform.kt  WGS-84 <-> GCJ-02, with an outOfChina guard
├── xposed/
│   ├── HookEntry.kt            @InjectYukiHookWithXposed entry point
│   ├── core/
│   │   ├── PrefsBridge.kt        XSharedPreferences, cached on hasFileChanged()
│   │   ├── SpoofEngine.kt        THE SINGLE SOURCE OF TRUTH — see below
│   │   ├── LocationFactory.kt    the one place a Location is built or rewritten
│   │   └── LocationDispatcher.kt synthetic updates on a shared scheduler
│   └── hooks/                  one file per signal family
├── record/EnvironmentRecorder.kt   captures the radio environment (never the position)
├── map/                        MapLibre setup, offline regions, Nominatim
├── ui/
│   ├── MapActivity.kt          the screen: selection, favourites, start/stop
│   ├── DeviceLocator.kt        the real position, cached-first with a bounded wait
│   └── map/
│       ├── MapMarkers.kt         the three markers and the jitter ring, as style layers
│       └── OfflineDownloadUi.kt  download / cancel / list / delete offline areas
└── room/, utils/               persistence, preferences

audit/                        a SEPARATE APK: com.cysindex.telequant.audit
└── SignalAuditActivity.kt      coverage checker, one row per hooked path
```

The auditor is its own application rather than a screen inside the module,
because the hook entry calls `loadApp(isExcludeSelf = true)` — the module never
hooks itself, so a self-audit would only ever read real values.

### The invariant

**No hook invents a value or rolls its own dice.** Each one calls
`SpoofEngine.current()` and reads a single field. Position, cells, Wi-Fi,
beacons, satellites, operator identity and time zone all come out of the same
snapshot, so they cannot contradict one another.

Snapshots live for 250 ms: short enough to feel live, long enough that a burst
of hook calls within one fix agrees with itself.

This is the property the original design lacked. GPS ran on one set of random
numbers, cell identity was hardcoded to MCC 460 with every other field zeroed,
and Wi-Fi was blanked to empty strings — three unrelated mechanisms, so the
position said one thing and the radio environment said something incompatible.
`MCC 460 + CID 0 + LAC 0` is a combination the platform never emits (absent
fields are `CellInfo.UNAVAILABLE`, and LAC 0 is reserved under 3GPP).

**If you add a hook, read from the snapshot.** Do not call `PrefsBridge`
directly and do not generate values locally; that is how the old inconsistency
crept in.

The same rule holds *across* processes. The module is loaded separately into
every hooked app, so any state kept inside `SpoofEngine` is per-app: an earlier
jitter implementation accumulated a random walk there, and two apps asking
where the device was at the same instant got answers a dozen metres apart,
each having started its walk at the exact centre of the circle. The wander is
now a pure function of the wall clock — three sine waves per axis with coprime
periods, mapped onto the disc — so every process computes the same point at
the same moment, an app that launches later joins the path in progress, and
the module app can draw the very fix being handed out.

---

## Signal coverage

### Tier A — position (missing one hands the app a real fix)

| Path | Note |
|---|---|
| `Location.CREATOR.createFromParcel` | The chokepoint every Location crossing a Binder passes through. Rewrites **the object's fields**, so `toString()`, `writeToParcel()`, `distanceTo()` and reflective serialisation agree with the accessors. Hooking only getters left the fields real — that was the original leak. |
| `Location` getters | Belt and braces: lat/lon/accuracy/altitude/speed/bearing/time/elapsedRealtimeNanos, plus the `has*` flags |
| `getLastKnownLocation`, `getCurrentLocation` (API 30+), `requestSingleUpdate`, `requestLocationUpdates` (all overloads) | |
| `PASSIVE_PROVIDER` | Receives fixes *other apps* requested |
| `addProximityAlert` | Evaluated by the system against the real position, so which alert fires reveals where the device actually is. Suppressed. |
| `Location.getExtras()` | Clears `mockLocation`; rewrites `noGPSLocation`, the unshifted WGS-84 fix several Chinese OEM ROMs put there |
| provider state | `isProviderEnabled`, `getProviders`, `isLocationEnabled` |

GMS Fused needs no special handling: its Locations are `android.location.Location`
and pass through the CREATOR hook.

### Tier B — radio environment

**Cell** — `getAllCellInfo` returns a list **built from the recording**, one
`CellInfo` per recorded cell, each bound to its own record: a list where every
tower reports the same identity is not a state a radio produces, and a recording
of five towers must surface five. The push path is hooked alongside it —
`listen()`, `registerTelephonyCallback()` (API 31+) and `requestCellInfoUpdate()`
— since covering only the pull leaves the modern path delivering the real
neighbour set. Plus `getCellLocation`, all `CellIdentity*` and
`CellSignalStrength*` getters, `ServiceState` (registration, roaming, registered
PLMN), `SubscriptionInfo`, and the operator strings so they agree with the
spoofed MCC/MNC.

**Wi-Fi** — `getScanResults`, `getConnectionInfo`, `WifiInfo` getters including
`getWifiSsid` (API 33+), **`NetworkCapabilities.getTransportInfo()`** (the API
31+ path that bypasses `WifiManager` entirely), and `WifiRttManager` (suppressed).

**Bluetooth** — `BluetoothLeScanner.startScan` callbacks, `stopScan` (which
must be translated back to the substituted callback, or the scan never stops),
the `PendingIntent` overload via the scan-result extra, classic discovery, and
the `ACTION_FOUND` broadcast. Unlike Wi-Fi, where the broadcast is only a
trigger and data is fetched afterwards, ACTION_FOUND carries the device and RSSI
in the intent itself, so it must be rewritten rather than passed through.

Beacons are delivered **on a timer**, not by rewriting genuine results as they
arrive: a result only arrives when something real is advertising nearby, so an
app scanning a quiet room would otherwise conclude there are no beacons.

**GNSS** — `registerGnssStatusCallback` (satellites regenerated per callback
with C/N0 drift), `addNmeaListener` (sentences synthesised — NMEA carries the
coordinates as plain text), legacy `GpsStatus`, raw measurements suppressed.

### Tier C — consistency

`elapsedRealtimeNanos` (0 reads as "oldest fix since boot" to anything ageing a
location), speed/bearing derived from jitter displacement, operator identity,
network type vs cell type, Wi-Fi absent-value semantics (`"<unknown ssid>"`, not
`""`), cell absent-value semantics (`UNAVAILABLE`, not 0), RSSI fluctuation.

### Deliberately not done

1. **IP geolocation** — decided server-side from the egress IP; nothing a module
   can do. Needs a device-level VPN/proxy.
2. **Raw GNSS measurements** (pseudoranges, Doppler, carrier phase) — a
   self-consistent set is not realistically forgeable, and an *inconsistent* one
   is more revealing than no data. Suppressed on purpose.
3. **Server-side correlation** — if a backend cross-checks reported position
   against account history, IP or time zone, the client cannot help.

### Known gaps

- Cell and Wi-Fi objects are **cloned from a real one of the same type** where
  the device reports one, because a clone keeps every field this module does not
  set at whatever the platform put there. Only when there is nothing of that
  type to copy — no service, Wi-Fi off, a radio the device does not have — is one
  built through a hidden constructor. That path is the less robust of the two:
  the signatures differ across API levels and OEM forks, and a type that cannot
  be constructed is dropped from the list with a warning rather than faked as
  some other type.
- In position-only mode the cells and access points are fabricated under the
  subscriber's real operator, so a database lookup resolves nothing and the app
  falls back to GNSS. An app that trusts a tower lookup *and* cannot fall back
  will simply fail to locate rather than being told the chosen place.
- Bonded Bluetooth devices are reported as an empty set.
- `BluetoothDevice.getName()` returns null for any address the recording does
  not contain, rather than the device's real name.

---

## Using it

Three things can be true of a point at once, so the map shows them apart:

| | |
|---|---|
| **Hollow pin** | the selected point — where Start would put you |
| **Filled green dot** | the anchor apps are being told about, with the jitter ring around it. Absent when stopped. |
| **Small green dot** | the exact fix being handed out this instant, wandering inside the ring. Redrawn four times a second from the same function the hooks evaluate, so it is the real value, not a lookalike. |
| **Locate button** | moves the selection to where the device actually is. The module excludes itself from its own hooks, so this reads the real position even mid-simulation. It shows the most recent cached fix at once (fused and network before GPS — indoors, GPS never answers) and upgrades to a fresh one if it arrives within fifteen seconds, without recentring a second time. |

The bottom sheet names the mode that is running — position only, or position
plus the recorded surroundings with their counts — and the drawer header says
whether the framework confirmed the module is active. That check can be a
false negative; it is informational, never blocking.

The map keeps its geographic centre in the middle of what can be seen: camera
padding follows the sheet's top edge, so when the keyboard lifts the sheet the
content shifts rather than the place in view sliding under it, and a recentre
lands the point where it is visible.

A single tap places the selected point. Panning and double-tap zoom do not:
MapLibre reports a *confirmed* tap, which neither produces.

**Searching.** A place name or a coordinate moves the selected point there and
centres the map on it. Nothing is committed by that: the selection is free to
change, and the only irreversible step is Start.

**Saving a place.** The star saves the selected point, and offers to record the
cells, Wi-Fi and beacons around you at the same time. Moving the selected point
by hand drops any recording attached to the previous one; loading a favourite
attaches its own. The recorder never
captures a position — the coordinate is the selected point — so recording works
indoors, where a GPS fix would time out and where a Wi-Fi recording is worth
most. The dialog shows how far the selection is from the device's actual
position rather than refusing: capturing one place's surroundings to replay at
another is a legitimate thing to want.

**Starting.** With a recording attached, Start asks which to use:

- *Position and the recorded surroundings* — replays the whole environment.
- *Position only* — the carrier stays real and the tower and access-point
  identifiers are fabricated, so a lookup finds nothing and the app falls back
  to GNSS. Leaving the radio untouched would let a tower lookup report the real
  city; blanking it produces `MCC 460 + CID 0`, which the platform never emits.

---

## Building

The toolchain is not what a stock machine has. On this workstation it lives at:

- **JDK 17** at `~/.local/jdk-17`, selected via `org.gradle.java.home` in
  `~/.gradle/gradle.properties`. The system JDK is 25, which AGP rejects.
- **Android SDK** at `~/Android/Sdk` with `platforms;android-37.0` and
  `build-tools;36.0.0`. `local.properties` points at it and is gitignored.

```bash
./gradlew assembleDebug
# The single most important check — KSP must generate the Xposed entry point,
# and its absence is silent:
unzip -p app/build/outputs/apk/debug/app-debug.apk assets/xposed_init
# expected: com.cysindex.telequant.xposed.HookEntry_YukiHookXposedInit
```

### Traps that cost time to rediscover

- **`compileSdk` must be 37 or higher.** YukiHookAPI 1.3.2 and its BetterAndroid
  transitives refuse to be consumed by a project compiling against 36.
- **Never declare KavaRef explicitly.** YukiHookAPI's own docs recommend it, but
  `yukihookapi:api:1.3.2` already imports `kavaref-bom:1.0.3`. Adding KavaRef
  1.1.0 yourself pulls `kotlin-stdlib:2.4.0`, whose metadata AGP 9.4's built-in
  Kotlin compiler (2.2.0, reads up to 2.3.0) cannot parse — producing a wall of
  errors across every KSP-generated file whose real cause is one dependency line.
- **Pin MapLibre explicitly.** Maven's `release` marker points at `13.6.1-pre0`,
  a pre-release.
- **R8 stays off.** The framework finds this module through the class *named* in
  `assets/xposed_init`, and several hooks reach hidden platform methods
  (`ScanRecord.parseFromBytes`, `WifiSsid.fromBytes`) by reflection. Obfuscation
  breaks both, silently — it compiles, installs, and does nothing.
- **`abiFilters` is `arm64-v8a` only.** MapLibre's four native renderers would
  add ~20 MB, and a module requiring Android 11+ with Zygisk is not running on
  32-bit.

### Release signing

Credentials come from Gradle properties (`TELEQUANT_STORE_FILE`,
`TELEQUANT_STORE_PASSWORD`, `TELEQUANT_KEY_ALIAS`, `TELEQUANT_KEY_PASSWORD`) set
in `~/.gradle/gradle.properties`, so nothing secret is committed. Without them
the release variant still builds, unsigned.

v1 signing is off (pointless at minSdk 30); v3 is on, so the key can be rotated
later without orphaning installs. **Losing the keystore means never being able
to ship an update** — Android refuses an update signed with a different key.

---

## Verifying a change

Compile checks catch very little here; almost every failure mode is a hook that
silently does nothing.

1. `./gradlew testDebugUnitTest` — 42 tests: the wander (bounded however
   coarsely sampled, identical across callers at one instant, never starting on
   the anchor), the GCJ-02 transform, the environment's JSON round trip, the
   synthetic profile and position-only mode. No device needed.
2. `./gradlew assembleDebug lintDebug` — lint must stay at zero errors.
3. Confirm `assets/xposed_init` is present in the APK (above).
4. Install the auditor, add **it** to the module's Xposed scope (not the module),
   and run it:
   ```
   ./gradlew :audit:assembleDebug
   adb install -r audit/build/outputs/apk/debug/audit-debug.apk
   adb shell am start -n com.cysindex.telequant.audit/.SignalAuditActivity
   ```
   It calls every covered path and prints what came back, so a path still
   returning real data is visible at a glance. It prints `getLastKnownLocation`
   through both the getter and `toString()`, since those disagreeing is exactly
   the class of bug it exists to catch.
5. **Load the synthetic profile first** (Favourites → *Load test environment*;
   it is saved as a place and replays like any other).
   Auditing against a recording made on the same phone proves nothing: the
   recording holds that phone's real towers, so a hooked read and an unhooked
   one return the same values. The synthetic profile cannot be confused with
   the device's own.
6. After reinstalling the module, wait for Vector to rescan before launching a
   target app. An app started during the replacement loads with no hooks at all
   and every reading comes back real, which looks exactly like a broken hook.
7. Cross-check with third-party diagnostics — Network Cell Info, GPSTest,
   nRF Connect — because the audit page can be wrong too.
8. Leave it running for five minutes: the track should be a continuous wander
   inside the radius with no teleporting, and RSSI/C-N0 should fluctuate rather
   than sit still.

---

## Map

MapLibre Native, with tiles from [OpenFreeMap](https://openfreemap.org).

That source was chosen for its terms, not its looks: it requires no API key,
states there are "no limits on the number of map views or requests", and permits
commercial use. This matters because of the offline feature — the OSM
Foundation's tile policy bans pre-fetching from `tile.openstreetmap.org`
outright ("Offline use is not permitted"), and the earlier osmdroid
implementation downloaded regions from exactly there, which would have got the
client blocked rather than producing an offline map.

Offline regions are downloaded through MapLibre's own `OfflineManager`. Because
online and offline are the same renderer reading the same vector data through
the same style, a downloaded region is indistinguishable from the live map.

Downloaded areas can be listed and deleted from the download dialog, and a
download in progress can be cancelled (the partial region is deleted with it).

**Proxy** — one host/port, defaulting to `127.0.0.1:33009`, with a switch per
destination. Which hosts a network blocks is a property of the network, and the
two destinations measured differently on the same connection: the tile host
answered directly in about 0.7 s and through the proxy in about 1.2 s, while
`nominatim.openstreetmap.org` did not answer directly at all. So the defaults
are **tiles direct, search proxied**, and both are the user's to change.
MapLibre fetches through OkHttp, so its proxy is installed by replacing the
client (`HttpRequestUtil.setOkHttpClient`); both clients are rebuilt when the
setting changes, so it takes effect without a restart. The proxy must be an
**HTTP** proxy supporting CONNECT: the endpoints are HTTPS and the hop to the
proxy itself is plaintext, which is why the setting has no scheme field.

A search that cannot reach the geocoder says so — naming the proxy and its
state — rather than reporting "address not found", which sends the user
hunting for a spelling mistake.

**Geocoding** is Nominatim, not `android.location.Geocoder`. The platform
geocoder is backed by Play Services and returns nothing without them, so on a
GMS-free device — including most in mainland China — place search silently never
worked. Coordinate input (`39.9042,116.4074`, or space-separated, or with the
full-width comma a Chinese IME produces) is parsed locally and never touches
the network.

**Keyboard.** `setDecorFitsSystemWindows` is off, so the window does not resize
for the IME; the activity asks for `adjustNothing` so the system does not pan
it either; and `BottomSheetBehavior` places the sheet by its parent's *height*
(`parentHeight = parent.getHeight()` in its source — it neither reads
`Type.ime()` nor touches `translationY`). The sheet is therefore lifted by
giving its parent a bottom margin equal to the IME inset, which is the one
thing that behavior honours. Padding the parent moves nothing, and a
translation on the sheet is undone by the next layout pass. The search
container must not have `animateLayoutChanges`: a `LayoutTransition` suppresses
parent layout while it runs, and the sheet ended up at the top of the screen on
the first character typed.

---

## Settings

`darkTheme`, `accuracy_settings`, `jitter_radius` (metres; **0 pins the position
exactly**), `jitter_mode` (STATIONARY/WALKING/DRIVING), `spoof_cell`,
`spoof_wifi`, `spoof_bluetooth`, `gcj02_output`, `spoof_timezone`,
`proxy_tiles_enabled` (default off), `geocoder_proxy_enabled` (default on),
`tile_proxy_host`, `tile_proxy_port` (the host/port keys keep their old names
so an already-configured proxy survives the split), `map_style`, `offline_map`.

`accuracy_settings` is what every fix *claims* as its horizontal accuracy;
`jitter_radius` is how far the fix actually moves. They are independent.

Keys are the contract between `PrefManager` (module app) and `PrefsBridge`
(hooked process); the two read the same world-readable file and must stay in
step. The per-signal switches are folded into the snapshot rather than read
individually by each hook, so a hook cannot act on a stale toggle while the rest
of the snapshot reflects a newer one.

**GCJ-02 is an output transform only**, applied last and guarded by
`outOfChina()`. Everything stored — the map, the anchor, recorded environments —
is WGS-84.

---

## Requirements

Android 11+ (`minSdk 30`), `targetSdk 36`, `compileSdk 37`. Magisk or KernelSU
with Zygisk, plus [Vector](https://github.com/JingMatrix/Vector) or another
framework implementing the legacy Xposed API. Target apps must be added to the
module's scope manually. TeleQuant itself does not need to be in its own scope:
hooks are installed with `loadApp(isExcludeSelf = true)`, and Vector loads the
module into its own process for the activation check regardless.

---

## Credits and licence

Original work by [Android1500](https://github.com/Android1500/GpsSetter).

The repository ships a GPL v3 `LICENSE` while the upstream README asserted "ALL
COPYRIGHTS RESERVED" — a contradiction inherited from upstream, not resolved
here. Anyone intending to redistribute should settle that first.

This module changes what applications are told about their surroundings. Use it
on devices and against applications where you have the standing to do so.
