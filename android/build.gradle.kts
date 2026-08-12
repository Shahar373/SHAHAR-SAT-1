// SHAHAR-SAT 1 Android app — root build file.
// See docs/ARCHITECTURE.md for how this app fits the overall project,
// and docs/BLE_PROTOCOL.md for the wire contract it implements.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
