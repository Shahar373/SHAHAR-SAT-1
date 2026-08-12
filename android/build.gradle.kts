// SHAHAR-SAT 1 Android app — root build file.
// See docs/ARCHITECTURE.md for how this app fits the overall project,
// and docs/BLE_PROTOCOL.md for the wire contract it implements.
//
// Toolchain pin — see ../firmware/README.md-style build notes in the root
// README for the full matrix. AGP 8.7.x requires Gradle 8.9+ (see
// gradle/wrapper/gradle-wrapper.properties) and supports compileSdk up to
// 35 — an earlier version of this file paired AGP 8.5.2 with
// compileSdk/targetSdk 36, which is not a valid combination (AGP 8.5
// tops out at API 34; even 8.7 tops out at 35). Fixed by moving both:
// AGP 8.5.2 -> 8.7.2, compileSdk/targetSdk 36 -> 35.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
