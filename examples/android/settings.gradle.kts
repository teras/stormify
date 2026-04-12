pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Resolve the Stormify libraries from the local Maven cache populated by
        // `gradle publishToMavenLocal` in the parent project. This avoids needing
        // a published artifact while iterating on the example app.
        mavenLocal()
    }
}

rootProject.name = "stormify-android-demo"
include(":app")
