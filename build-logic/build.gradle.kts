plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApp") {
            id = "tuikio.android-app"
            implementationClass = "dev.tuikio.build.AndroidAppPlugin"
        }
    }
}
