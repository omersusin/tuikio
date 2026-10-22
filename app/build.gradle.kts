plugins {
    id("tuikio.android-app")
}

tuikioAndroid {
    versionCode.set(3)
    versionName.set("0.1.2")
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

// ---------------------------------------------------------------------------------------------
// UI tests on the JVM with Robolectric: real Activities, lifecycle, rotation and views, driving
// the real x86_64 game binaries. They cover what unit tests can't (code that only runs on
// Android) without needing a device or emulator. ./gradlew :app:test
val robolectricRuntime by configurations.creating
configurations.named("testCompileOnly") { extendsFrom(configurations.getByName("compileOnly")) }
// As with AGP's mockable android.jar: JUnit reflects on test methods (whose signatures mention
// Android types) before Robolectric's sandbox takes over.
configurations.named("testRuntimeOnly") { extendsFrom(configurations.getByName("compileOnly")) }

configurations.matching { it.name.startsWith("test") }.configureEach {
    // Published only on Google's Maven; built from source in third_party/androidx-test instead.
    exclude(group = "androidx.test", module = "monitor")
    exclude(group = "androidx.test.espresso", module = "espresso-idling-resource")
}

dependencies {
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(project(":third-party:androidx-test"))
    testImplementation("junit:junit:4.13.2")
    // Robolectric's Android 16 framework, resolved by Gradle and used offline at test time.
    robolectricRuntime("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
}

val robolectricDeps by tasks.registering(Sync::class) {
    from(robolectricRuntime)
    into(layout.buildDirectory.dir("robolectric-deps"))
}

// Points Robolectric at what the custom build produced (AGP would generate this file).
val robolectricConfig by tasks.registering {
    val out = layout.buildDirectory.dir("generated/robolectric")
    dependsOn("aapt2Link", "mergeAssets")
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/android/tools/test_config.properties").asFile
        f.parentFile.mkdirs()
        f.writeText(
            listOf(
                "android_merged_manifest=" + file("src/main/AndroidManifest.xml").absolutePath,
                "android_resource_apk=" + layout.buildDirectory.file("intermediates/res/linked.ap_").get().asFile.absolutePath,
                "android_merged_assets=" + layout.buildDirectory.dir("intermediates/merged_assets").get().asFile.absolutePath,
                "android_custom_package=dev.tuikio.app",
            ).joinToString("\n") + "\n",
        )
    }
}
sourceSets.named("test") { resources.srcDir(robolectricConfig) }

tasks.named<Test>("test") {
    useJUnit()
    dependsOn(robolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-deps").get().asFile.absolutePath)
    systemProperty("tuikio.nativeOut", rootProject.file("native/build/out/x86_64").absolutePath)
    maxHeapSize = "2g"
    // Robolectric reaches into these JDK internals (AGP passes the same flags).
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
    )
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Tests run on a desktop JDK, not on Android: they may target a newer JVM than the app's Java 8.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    // The app's -Xjdk-release=1.8 (which keeps main code to Android-available JDK APIs) doesn't apply here.
    compilerOptions.freeCompilerArgs.set(compilerOptions.freeCompilerArgs.get().filterNot { it.startsWith("-Xjdk-release") })
}
tasks.named<JavaCompile>("compileTestJava") {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
