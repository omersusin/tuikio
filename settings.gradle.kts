pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "tuikio"

include(":core:terminal")
include(":core:controls")
include(":core:catalog")
include(":core:runtime")
include(":core:community")
include(":app")
include(":backend")
include(":tools:catalog-cli")
include(":third-party:androidx-test")
project(":third-party:androidx-test").projectDir = file("third_party/androidx-test")
