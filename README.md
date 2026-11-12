# Forge

A personal, strength-first training app for Wear OS + Android, built for one user:
the Galaxy Watch runs the workout in the gym, the phone holds the plan, the
history, the nutrition and the AI coach. No account, no subscription, no cloud in
v1 — data lives on the devices and completed workouts are written to Health
Connect so they show up in Samsung Health.

The full product blueprint is `~/workspace/Fitness_App_Blueprint_2026-11-10.md`
(Sections 3.0–3.16, architecture in §4, data model in §5, phases in §6). This
repo is the **Phase 1 scaffold**: the architecture proven end to end — `:core`
domain logic with real unit tests, a phone onboarding gate, a watch set logger
and rest timer — with deeper features (Room storage, sync, AI coach, visual
assessment) arriving in their phases behind clear `TODO` markers.

## Modules

| Module | What it is |
|---|---|
| `:core` | Pure Kotlin/JVM, **no Android dependencies** — so all workout/plan logic is unit-testable anywhere. Data models, onboarding gate, plate calculator, 1RM/PR detection, progressive overload, rest-timer state machine, sample data. |
| `:mobile` | The phone app (`applicationId com.forge.fitness`): Compose Material 3, dark theme with the amber accent (#f2a93b) from the design mockups. Onboarding flow, Today, History, Health Connect writer. |
| `:wear` | The watch app (same `applicationId` — required for the Wearable Data Layer to pair the two): Compose for Wear OS + Horologist. Runs today's plan or a freeform session: set logger with ghost targets and big steppers, rest timer that buzzes, pain/undo/skip/add, summary with PRs. |

Phone is the source of truth; the watch caches the active plan and the in-progress
session, then syncs completed sets back over the Data Layer (blueprint §4).

## Phone ↔ watch sync

Both directions use the Wearable Data Layer. The wire format is one tested codec
in `core/.../sync/ForgeWire.kt`: versioned JSON, with unknown fields ignored.

- **Plans, phone → watch** (`/forge/plans`). The phone builds a week of plans
  (`WatchPlanner`) from the same read-models it shows: progression weights,
  ghost targets and PRs. It also sends a freeform menu of the exercises your
  limitations allow. The onboarding gate and limitations are applied on the
  phone, so the watch never sees the health profile and can't skip a
  limitation. If plans are blocked (onboarding unfinished, clearance pending),
  the watch is told why and offers nothing. Plans are re-sent when the app
  opens, when onboarding finishes, and whenever a session arrives. The Data
  Layer keeps the latest copy on the watch, so it works in the gym without the
  phone.
- **Sessions, watch → phone** (`/forge/session/<id>`). While a session runs,
  the watch saves every action to a journal (`SessionJournal`) that replays
  through `SessionEngine`. A workout survives the app being killed mid-session.
  On finish, the session goes to an outbox on disk, then to the Data Layer. The
  phone stores it in Room, writes it to Health Connect (once permitted from
  Home), and deletes the item as its receipt. Anything the phone can't store
  yet is left in place, never dropped. Storing is idempotent and the Health
  Connect record has a stable client id, so a repeated delivery is harmless.

## Phase 1 ↔ blueprint map

| Blueprint | Where in this repo |
|---|---|
| §5 data model (Exercise, Routine, WorkoutSession, SetEntry, PR, BodyEntry, profiles) | `core/.../model/` |
| 3.0 onboarding gate — red-flag blocking, functional constraints, HR cap that only scales down | `core/.../onboarding/OnboardingGate.kt`, `mobile/.../ui/OnboardingScreen.kt` |
| Plate calculator | `core/.../training/PlateCalculator.kt` |
| Epley 1RM + PR detection | `core/.../training/OneRepMax.kt` (`OneRepMax`, `PrDetector`) |
| Progressive overload (+2.5 kg upper / +5 kg lower) | `core/.../training/ProgressiveOverload.kt` |
| Rest timer (per-exercise default, +15s/skip) | `core/.../training/RestTimer.kt`, `wear/.../ui/RestTimerScreen.kt` |
| 3.1 watch set logger | `wear/.../ui/SetLoggerScreen.kt` |
| 3.3 phone history | `mobile/.../ui/HistoryScreen.kt`, `mobile/.../data/ForgeRepository.kt` |
| 3.6 Health Connect write of completed workouts | `mobile/.../health/HealthConnectWriter.kt` |
| 3.6 phone ↔ watch sync (plans out, sessions back) | `core/.../sync/`, `mobile/.../sync/`, `wear/.../sync/`, `wear/.../workout/` |
| Sample plan (Push A / Pull A / Legs A) | `core/.../data/SampleData.kt` |

Every `:core` behaviour above has JUnit 5 tests under `core/src/test/` — they are
Phase 1's verification (run them with `:core:test`).

## Known Phase 1 simplifications (all marked with TODOs in code)

- **Storage is on the phone only.** `ForgeRepository` persists to Room
  (`mobile/.../data/db/`; phone = source of truth): sessions, sets, the goal
  profile, weigh-ins and body-composition inputs. The health profile is not in
  the database: it is an AES-256-GCM file whose key lives in the Android
  Keystore, kept out of backups (`mobile/.../data/secure/`). History fills
  from sessions synced from the watch, so it starts empty. The demo seed is gone.
- **Health Connect** writes finished workouts once permission is granted from
  Home. Reads (sleep/HRV for readiness) are Phase 2.
- **Watch session**: no live heart rate yet (Health Services), and no ongoing
  activity, so between sets the app can drop to the watch face. The journal
  means reopening it resumes the session. Weights show in kg.

## Building

Requirements: JDK 17 and Gradle 8.11.1+ (needed by AGP 8.9), plus the Android SDK
(compileSdk 36, which Health Connect 1.1.0 requires) for the two app modules. `:core`
needs only a JDK.

```bash
gradle :core:test                 # unit tests — the Phase 1 verification
gradle :mobile:testDebugUnitTest  # storage tests (Room + encrypted store, on Robolectric)
gradle :mobile:assembleDebug      # phone APK
gradle :wear:assembleDebug        # watch APK
```

(If a Gradle wrapper is present, `./gradlew` works identically.)

CI (`.github/workflows/build.yml`) runs exactly the commands above on every push
and pull request.

The Room schema is exported to `mobile/schemas/` and committed. Any schema change
bumps the database version and ships a tested migration; destructive fallback is
never enabled.

## Release signing

Phone and watch APKs are signed with one permanent release key. The Wearable
Data Layer pairs the two apps only when applicationId and signing key match,
and Android installs an update only over an app signed with the same key, so
losing the key means uninstalling, which wipes on-device data. The key is never
committed. CI reads it from three repository secrets (Settings → Secrets and
variables → Actions):

| Secret | Value |
|---|---|
| `FORGE_KEYSTORE_BASE64` | the PKCS12 keystore, base64-encoded |
| `FORGE_KEYSTORE_PASSWORD` | the keystore password (also the key password) |
| `FORGE_KEY_ALIAS` | the key alias |

With the secrets set, every CI run also builds signed release APKs for both
apps, checks they share one certificate, and attaches them to the run as the
`forge-apks-<run number>` artifact. The run number is the versionCode, so each
build installs as an update over the last. Without the secrets, those steps
are skipped. To sign locally, export `FORGE_KEYSTORE_FILE` (a path),
`FORGE_KEYSTORE_PASSWORD` and `FORGE_KEY_ALIAS`, then run
`gradle :mobile:assembleRelease :wear:assembleRelease`.

## Deployment (developer mode, no Play Store)

The watch APK is sideloaded over Wi-Fi with `adb` after a one-time Wireless
Debugging pairing from the Galaxy Watch's developer options; the phone APK
installs directly. Details in blueprint §7.
