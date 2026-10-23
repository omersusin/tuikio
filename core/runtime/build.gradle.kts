plugins {
    id("tuikio.kotlin-library")
}

dependencies {
    api(project(":core:terminal"))
    api(project(":core:catalog"))
}

// Host-side end-to-end tests run the real game executables built by native/build.py.
tasks.withType<Test>().configureEach {
    systemProperty("tuikio.nativeOut", rootProject.file("native/build/out").absolutePath)
    systemProperty("tuikio.requireNative", providers.gradleProperty("requireNative").isPresent.toString())
    inputs.dir(rootProject.file("native/build/out")).optional().withPropertyName("nativeOut")
    // The play scripts and the manifests (terminal sizes, args) decide what the tests see.
    inputs.dir(rootProject.file("native/tests")).withPropertyName("playScripts")
    inputs.dir(rootProject.file("catalog/games")).withPropertyName("manifests")
}

// Host screenshots of real games (see GameShot): ./gradlew :core:runtime:shot -Pgame=2048
tasks.register<JavaExec>("shot") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.tuikio.runtime.GameShot")
    systemProperty("java.awt.headless", "true")
    systemProperty("tuikio.nativeOut", rootProject.file("native/build/out").absolutePath)
    args(
        providers.gradleProperty("game").getOrElse("all"),
        providers.gradleProperty("script").getOrElse(""),
        providers.gradleProperty("out").getOrElse(""),
    )
}
