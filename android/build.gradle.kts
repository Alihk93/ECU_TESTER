// Root build file. Plugin versions are declared here and applied per-module.
// Pinned deliberately (see android/README.md): AGP 8.5.2 + Kotlin 1.9.24 need
// JDK 17 and Gradle 8.7 — a mismatch is the #1 first-build failure.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
