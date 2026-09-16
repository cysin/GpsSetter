# TeleQuant

An Xposed/LSPosed module that makes selected apps believe the device is at a
place you choose — not just the GPS coordinate, but the cell towers, Wi-Fi
access points, Bluetooth beacons and satellites that place would have, all
consistent with one another.

Forked from [Android1500/GpsSetter](https://github.com/Android1500/GpsSetter),
then largely rewritten. Package renamed to `com.cysindex.telequant`.

> **Runtime behaviour is not verified.** Everything here compiles, passes lint,
> and produces an APK whose Xposed entry point is correctly generated. None of
> the hooks have been exercised on a device. Treat the hook layer as untested
> until it has been through the audit procedure below.

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
│   ├── JitterEngine.kt         bounded random walk, metre/degree conversion
│   └── CoordinateTransform.kt  WGS-84 <-> GCJ-02, with an outOfChina guard
├── xposed/
│   ├── HookEntry.kt            @InjectYukiHookWithXposed entry point
│   ├── core/
│   │   ├── PrefsBridge.kt        XSharedPreferences, cached on hasFileChanged()
│   │   ├── SpoofEngine.kt        THE SINGLE SOURCE OF TRUTH — see below
│   │   ├── LocationFactory.kt    the one place a Location is built or rewritten
│   │   └── LocationDispatcher.kt synthetic updates on a shared scheduler
│   └── hooks/                  one file per signal family
├── record/EnvironmentRecorder.kt   captures a place's radio environment
├── map/                        MapLibre setup, offline regions, Nominatim
├── ui/, room/, utils/          the module app itself
└── debug/SignalAuditActivity.kt    debug-only coverage checker
```

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

**Cell** — `getAllCellInfo` (via the identity getters), `getCellLocation`,
`PhoneStateListener` / `registerTelephonyCallback`, all `CellIdentity*` and
`CellSignalStrength*` getters, `ServiceState`, `SubscriptionInfo`, and the
operator strings so they agree with the spoofed MCC/MNC.

**Wi-Fi** — `getScanResults`, `getConnectionInfo`, `WifiInfo` getters including
`getWifiSsid` (API 33+), **`NetworkCapabilities.getTransportInfo()`** (the API
31+ path that bypasses `WifiManager` entirely), and `WifiRttManager` (suppressed).

**Bluetooth** — `BluetoothLeScanner.startScan` callbacks, classic discovery, and
the `ACTION_FOUND` broadcast. Unlike Wi-Fi, where the broadcast is only a
trigger and data is fetched afterwards, ACTION_FOUND carries the device and RSSI
in the intent itself, so it must be rewritten rather than passed through.

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

- Cell and Wi-Fi objects are **rewritten in place** rather than constructed.
  When the real list is empty (no service, Wi-Fi off) there is nothing to
  rewrite and that signal passes through. This is a deliberate trade for
  cross-version robustness: building `CellInfo` means hidden constructors whose
  signatures differ across API levels and OEM forks.
- With no recorded environment, cell/Wi-Fi/Bluetooth are not spoofed at all —
  only the GPS path works. Satellites are synthesised.
- Bonded Bluetooth devices are reported as an empty set.

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

1. `./gradlew assembleDebug lintDebug` — lint must stay at zero errors.
2. Confirm `assets/xposed_init` is present in the APK (above).
3. Install the **debug** variant, add it to the module's own Xposed scope, and
   run the coverage checker:
   ```
   adb shell am start -n com.cysindex.telequant/.debug.SignalAuditActivity
   ```
   It calls every covered path and prints what came back, so a path still
   returning real data is visible at a glance. It prints `getLastKnownLocation`
   through both the getter and `toString()`, since those disagreeing is exactly
   the class of bug it exists to catch.
4. Cross-check with third-party diagnostics — Network Cell Info, GPSTest,
   nRF Connect — because the audit page can be wrong too.
5. Leave it running for five minutes: the track should be a continuous wander
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

**Tile proxy** — off-by-default host/port, defaulting to `127.0.0.1:33009`.
MapLibre fetches through OkHttp, so the proxy is installed by replacing its
client (`HttpRequestUtil.setOkHttpClient`), not through any osmdroid-style
configuration. It must be an **HTTP** proxy supporting CONNECT: the tile
endpoints are HTTPS and the hop to the proxy itself is plaintext, which is why
the setting has no scheme field.

**Geocoding** is Nominatim, not `android.location.Geocoder`. The platform
geocoder is backed by Play Services and returns nothing without them, so on a
GMS-free device — including most in mainland China — place search silently never
worked. Coordinate input (`39.9042,116.4074`) is parsed locally and never
touches the network.

---

## Settings

`darkTheme`, `accuracy_settings`, `jitter_radius` (metres; **0 pins the position
exactly**), `jitter_mode` (STATIONARY/WALKING/DRIVING), `spoof_cell`,
`spoof_wifi`, `spoof_bluetooth`, `gcj02_output`, `spoof_timezone`,
`tile_proxy_enabled`, `tile_proxy_host`, `tile_proxy_port`, `map_style`,
`offline_map`.

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
module's scope manually.

---

## Credits and licence

Original work by [Android1500](https://github.com/Android1500/GpsSetter).

The repository ships a GPL v3 `LICENSE` while the upstream README asserted "ALL
COPYRIGHTS RESERVED" — a contradiction inherited from upstream, not resolved
here. Anyone intending to redistribute should settle that first.

This module changes what applications are told about their surroundings. Use it
on devices and against applications where you have the standing to do so.
