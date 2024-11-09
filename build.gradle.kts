// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    dependencies {
        classpath(libs.gradle)
        // Add the AppGallery Connect plugin configuration. Please refer to AppGallery Connect Plugin Dependency to select a proper plugin version.
        classpath(libs.agcp)
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}