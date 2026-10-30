// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        // AGP 9 uses built-in Kotlin: this line picks a newer Kotlin version than the bundled one
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
