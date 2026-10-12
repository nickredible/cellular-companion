// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP compiles Kotlin itself; declaring the Kotlin plugin here only pins the Kotlin version it uses
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
