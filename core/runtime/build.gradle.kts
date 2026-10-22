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
    systemProperty("tuikio.nativeCache", rootProject.file("native/.cache").absolutePath)
    inputs.dir(rootProject.file("native/build/out")).optional().withPropertyName("nativeOut")
}
