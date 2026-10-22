// AndroidX Test "monitor" 1.8.0 and "espresso-idling-resource" 3.7.0, built from source for the
// Robolectric UI tests (Robolectric needs them at runtime; their binaries are only published on
// Google's Maven repository). Test-only: never part of the app. See README.md for provenance.
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Brings in the Kotlin plugin; its strict settings are relaxed below for vendored code.
    id("tuikio.kotlin-library")
}

val sdkDir: String = run {
    val p = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
    p.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: throw GradleException("Android SDK not found (docs/BUILD.md)")
}

// Upstream compiles against a compile-time copy of ExposedInstrumentationApi that declares hidden
// Instrumentation methods (Bazel "neverlink") and ships an empty runtime copy. Same here:
// "hidden" is compile-only, "shim" is what goes into the jar.
val hidden: SourceSet = sourceSets.create("hidden") { java.srcDirs("hidden/java", "stubs/java") }
val shim: SourceSet = sourceSets.create("shim") { java.srcDirs("shim/java") }
sourceSets.named("main") {
    java.srcDirs("src/main/java", "stubs/java")
    compileClasspath += hidden.output
}
tasks.named<Jar>("jar") { from(shim.output) }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        allWarningsAsErrors.set(false)
        suppressWarnings.set(true)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-nowarn", "-Xlint:none"))
}

dependencies {
    val androidJar = files("$sdkDir/platforms/android-36/android.jar")
    compileOnly(androidJar)
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    "hiddenCompileOnly"(androidJar)
    "shimCompileOnly"(androidJar)
    "shimCompileOnly"(sourceSets["main"].output)
}
