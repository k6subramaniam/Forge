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
| `:wear` | The watch app (same `applicationId` — required for the Wearable Data Layer to pair the two): Compose for Wear OS + Horologist. Set logger with ghost targets and big steppers, rest timer screen. |

Phone is the source of truth; the watch caches the active plan and the in-progress
session, then syncs completed sets back over the Data Layer (blueprint §4).

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
| Sample plan (Push A / Pull A / Legs A) | `core/.../data/SampleData.kt` |

Every `:core` behaviour above has JUnit 5 tests under `core/src/test/` — they are
Phase 1's verification (run them with `:core:test`).

## Known Phase 1 simplifications (all marked with TODOs in code)

- **Storage is in-memory stubs.** `ForgeRepository` seeds demo data; Room
  (phone = source of truth, watch cache), on-device encryption of the health
  profile, and Data Layer sync are the next build step.
- **Health Connect** write path is implemented against the stable 1.1.0 API;
  the permission-request UX and the call site land with real session flow.
  Reads (sleep/HRV for readiness) are Phase 2.
- **The watch demo flow** logs sets for one exercise (Push A opener) with a
  hard-coded ghost target; full session flow follows with Data Layer sync.

## Building

Requirements: JDK 17 and Gradle 8.10.2+, plus the Android SDK (compileSdk 35)
for the two app modules. `:core` needs only a JDK.

```bash
gradle :core:test                 # unit tests — the Phase 1 verification
gradle :mobile:assembleDebug      # phone APK
gradle :wear:assembleDebug        # watch APK
```

(If a Gradle wrapper is present, `./gradlew` works identically.)

CI (`.github/workflows/build.yml`) runs exactly the three commands above on every
push and pull request.

## Deployment (developer mode, no Play Store)

The watch APK is sideloaded over Wi-Fi with `adb` after a one-time Wireless
Debugging pairing from the Galaxy Watch's developer options; the phone APK
installs directly. Details in blueprint §7.
