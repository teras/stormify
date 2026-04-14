# Stormify iOS Example

A minimal iOS app that demonstrates how to use **Stormify** on iOS with SQLite
via Kotlin Multiplatform. The Kotlin shared module contains the entities and
demo logic; the Swift UI layer calls into it through the generated framework.

## What it shows

- Using `KdbcDataSource` with SQLite on iOS (platform SQLite via C interop).
- The KSP `annproc` processor generating entity metadata at compile time
  (mandatory on native — reflection is not available).
- CRUD operations, transactions with rollback, and entity references.
- Kotlin/Native framework consumed by a SwiftUI app.

## Layout

```
shared/src/
├── commonMain/kotlin/demo/
│   ├── Demo.kt              # Shared demo logic (CRUD, transactions)
│   ├── User.kt, Task.kt     # Entity classes
│   └── Priority.kt          # Enum with custom DB values
└── iosMain/kotlin/demo/
    └── IosDemo.kt            # iOS entry point (creates SQLite DataSource)

iosApp/iosApp/
├── StormifyDemoApp.swift     # SwiftUI app entry point
└── ContentView.swift         # UI: button to run demo, log output
```

## Build & run

The example consumes Stormify from the **local Maven cache**, so you must
publish the libraries first:

```bash
# from the stormify project root
gradle publishToMavenLocal
```

Then open the Xcode project and run on a simulator:

```bash
cd examples/ios/iosApp
open iosApp.xcodeproj
```

Or build the shared framework from the command line:

```bash
cd examples/ios
gradle :shared:linkDebugFrameworkIosSimulatorArm64
```

## Notes

- Requires macOS with Xcode installed.
- Targets iOS 16+ (iosArm64 for device, iosSimulatorArm64 for simulator).
- The SQLite database is created in the app's temporary directory.
