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
        // MuPDF (PDF engine) is published here by Artifex
        maven { url = uri("https://maven.ghostscript.com") }
    }
}
rootProject.name = "LilOne"
include(":app")
