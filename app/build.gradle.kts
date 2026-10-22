plugins {
    id("tuikio.android-app")
}

tuikioAndroid {
    versionCode.set(1)
    versionName.set("0.1.0")
    minSdk.set(26)
    targetSdk.set(36)
    nativeLibsDir.set(rootProject.layout.projectDirectory.dir("native/build/out"))
    abis.set(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
    proguardRules.from("proguard-rules.pro")
}

dependencies {
    implementation(project(":core:runtime"))
    implementation(project(":core:controls"))
    implementation(project(":core:community"))
}

// Catalog snapshot shipped in the APK: validated and assembled by the same tool CI runs.
val catalogCli by configurations.creating
dependencies { catalogCli(project(":tools:catalog-cli")) }

val catalogVersion = 1
val generatedCatalog = layout.buildDirectory.dir("generated/assets/catalog-root")
val assembleCatalog by tasks.registering(JavaExec::class) {
    group = "android"
    description = "Validates catalog/ and writes the bundled catalog snapshot."
    classpath = catalogCli
    mainClass.set("dev.tuikio.catalogcli.MainKt")
    val src = rootProject.file("catalog")
    val out = generatedCatalog.map { it.dir("catalog") }
    inputs.dir(src)
    outputs.dir(generatedCatalog)
    args("assemble", src.absolutePath, out.get().asFile.absolutePath, "--version", catalogVersion.toString())
}

// Game data files (message packs, levels) and the per-ABI executable checksums from native/build.py.
val nativeAssets by tasks.registering(Sync::class) {
    group = "android"
    from(rootProject.file("native/build/out/assets"))
    from(rootProject.file("native/build/out/manifest.json")) { into("native") }
    into(layout.buildDirectory.dir("generated/assets/native-root"))
}

tuikioAndroid {
    assetDirs.from(files(generatedCatalog).builtBy(assembleCatalog))
    assetDirs.from(files(nativeAssets.map { it.destinationDir }).builtBy(nativeAssets))
}
