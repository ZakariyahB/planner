# Contract change requests

The contracts are the files marked "Contracts (Stream 0)" in `android/engine`, `android/calendar` and `android/app/.../di`.
A stream that needs a different type or signature does not edit them: it appends an entry here on its branch, and the
master agent applies accepted changes when merging.

Format:

```
## YYYY-MM-DD · Stream X · short title
- What:
- Why:
- Affects: streams
- Proposed change: (code or signature)
- Status: proposed | accepted | rejected (by master)
```

---

## 2026-10-31 · Stream F · Instrumented-test setup so the store contract runs against Room
- What: in `android/app/build.gradle.kts`, add `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` to `defaultConfig`, and `androidTestImplementation` of `androidx.test:runner:1.6.2` and `androidx.test.ext:junit:1.2.1`. Then add `android/app/src/androidTest/kotlin/app/planner/store/RoomStoreContractTest.kt` (the text is in `docs/redesign/notes/F.md`).
- Why: `StoreContract` (`engine/codec`) is the shared behaviour of the storage ports. It runs against the in-memory fakes on the JVM. Running it against `PlannerStoreDatabase` needs an Android runtime (Room's SQLite), and the app has no instrumented-test dependencies. Gradle files and `src/androidTest` are outside Stream F's paths.
- Affects: master only. CI does not run instrumented tests; this is for a device or emulator (`./gradlew :app:connectedDebugAndroidTest`).
- Proposed change: as above.
- Status: proposed
