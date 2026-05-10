// Aggregator project. Each scenario is an independent KMP build under :scenarioN.
// The KMP build service must be loaded in the root classloader, so plugins are
// declared (apply false) here and applied per scenario.
plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    id("com.android.library") version "8.7.3" apply false
    id("onl.ycode.stormify") version "2.5.2" apply false
}
