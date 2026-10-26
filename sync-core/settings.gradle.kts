// sync-core is a standalone Gradle build so that each app can consume it the
// same way: Diligent (Android) through `includeBuild`, the desktop app through
// its own composite build, and the iPhone app through the framework that the
// iOS targets produce.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "sync-core"
