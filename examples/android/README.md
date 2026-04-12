# Stormify Android Example

A minimal Android app that demonstrates how to use **Stormify** with the
platform's built-in SQLite database. The whole sample is two entities, one
ViewModel, and one Compose screen — small enough to read in five minutes.

## What it shows

- Wiring `Stormify` to Android's `SQLiteDatabase` via the
  `Stormify(SQLiteDatabase)` convenience constructor.
- The KSP `annproc` processor generating `TableInfo` registration code at
  compile time (mandatory on Android — full reflection isn't available).
- CRUD operations through the Stormify ORM API: `create`, `findAll`,
  `update`, `delete`, plus a transaction with rollback on failure.
- Lazy reference loading via `AutoTable` (a `Task` lazily resolves its `User`).
- Enum-as-int storage via `DbValue` so enum order changes don't corrupt data.

## Layout

```
app/src/main/kotlin/demo/android/
├── MainActivity.kt          # Compose entry point
├── db/
│   ├── Database.kt          # opens SQLiteDatabase, builds Stormify, seeds data
│   └── Entities.kt          # User, Task, Priority
└── ui/
    ├── TasksViewModel.kt    # Stormify calls live here, dispatched to IO
    └── TasksScreen.kt       # the list + the "new task" dialog
```

## Build & run

The example consumes Stormify from the **local Maven cache**, so you must
publish the libraries first:

```bash
# from the stormify project root
gradle :kdbc:publishToMavenLocal :stormify:publishToMavenLocal \
       :logger:publishToMavenLocal :annproc:publishToMavenLocal
```

Then build the app:

```bash
cd examples/android
gradle :app:assembleDebug
```

To install on a connected emulator or device:

```bash
gradle :app:installDebug
adb shell am start -n demo.android/.MainActivity
```

## Notes

- `minSdk = 21`, `compileSdk = 34` — same floor as the Stormify Android library.
- `local.properties` points at `~/Android/Sdk`. Edit it if your SDK lives
  elsewhere.
- The database file is created at
  `/data/data/demo.android/databases/stormify-demo.db`. Uninstall the app to
  start from a clean seed.
