package dev.tuikio.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Builds and signs an Android APK from Kotlin sources with the Android SDK's own tools
 * (aapt2, d8 or dx, zipalign, apksigner) plus ProGuard for release shrinking.
 *
 * Tuikio uses this instead of the Android Gradle Plugin because AGP and AndroidX are hosted
 * only on Google's Maven repository, which the environment the project was bootstrapped in
 * cannot reach. The source layout (src/main/AndroidManifest.xml, res/, assets/, kotlin/)
 * matches AGP's, so moving to AGP later is a build-file change, not a code change.
 * See docs/BUILD.md.
 */
class AndroidAppPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        val ext = project.extensions.create("tuikioAndroid", TuikioAndroidExtension::class.java)
        val sdk = AndroidSdk.locate(project)

        project.dependencies.add("compileOnly", project.files(sdk.androidJar))

        val build = project.layout.buildDirectory
        val generatedR = build.dir("generated/source/r")

        val compileRes = project.tasks.register("aapt2Compile", Aapt2CompileTask::class.java) {
            val t = this
            t.group = "android"
            t.aapt2.set(sdk.aapt2)
            t.resDir.set(project.file("src/main/res"))
            t.output.set(build.file("intermediates/res/compiled.zip"))
        }
        val mergeAssets = project.tasks.register("mergeAssets", org.gradle.api.tasks.Sync::class.java) {
            val t = this
            t.group = "android"
            t.from(project.file("src/main/assets"))
            t.from(ext.assetDirs)
            t.into(build.dir("intermediates/merged_assets"))
        }
        val linkRes = project.tasks.register("aapt2Link", Aapt2LinkTask::class.java) {
            val t = this
            t.group = "android"
            t.aapt2.set(sdk.aapt2)
            t.frameworkRes.set(sdk.frameworkRes)
            t.manifest.set(project.file("src/main/AndroidManifest.xml"))
            t.compiled.set(compileRes.flatMap { it.output })
            t.dependsOn(mergeAssets)
            t.assetsDir.set(build.dir("intermediates/merged_assets"))
            t.minSdk.set(ext.minSdk)
            t.targetSdk.set(ext.targetSdk)
            t.versionCode.set(ext.versionCode)
            t.versionName.set(ext.versionName)
            t.rJavaDir.set(generatedR)
            t.output.set(build.file("intermediates/res/linked.ap_"))
            t.proguardRules.set(build.file("intermediates/res/aapt-rules.pro"))
        }

        project.extensions.getByType(SourceSetContainer::class.java).getByName("main").java.srcDir(linkRes.flatMap { it.rJavaDir })
        project.tasks.named("compileJava", JavaCompile::class.java) {
            val t = this
            t.dependsOn(linkRes)
            t.options.bootstrapClasspath = project.files(sdk.androidJar)
            t.sourceCompatibility = "1.8"
            t.targetCompatibility = "1.8"
            t.options.compilerArgs.addAll(listOf("-Xlint:-options"))
        }
        project.extensions.getByType(KotlinJvmProjectExtension::class.java).compilerOptions {
            val o = this
            o.jvmTarget.set(JvmTarget.JVM_1_8)
            o.freeCompilerArgs.addAll(
                "-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline",
                "-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions",
                // Compile against Java 8's library surface only (Android API 26 ⊇ it).
                "-Xjdk-release=1.8",
            )
        }
        project.tasks.named("compileKotlin") { dependsOn(linkRes) }

        val proguardCfg = project.configurations.create("proguard")
        project.dependencies.add("proguard", "com.guardsquare:proguard-base:7.10.0")

        val mainOutput = project.files(
            project.provider { project.extensions.getByType(SourceSetContainer::class.java).getByName("main").output.classesDirs },
        )
        val runtimeJars = project.configurations.getByName("runtimeClasspath")

        for (variant in listOf("debug", "release")) {
            val cap = variant.replaceFirstChar { it.uppercase() }
            val release = variant == "release"

            val shrink = if (release) project.tasks.register("proguard$cap", JavaExec::class.java) {
                val t = this
                t.group = "android"
                t.description = "Shrinks, optimizes and obfuscates the release bytecode with ProGuard."
                t.dependsOn("classes", linkRes)
                t.classpath = proguardCfg
                t.mainClass.set("proguard.ProGuard")
                val outJar = build.file("intermediates/proguard/$variant/classes.jar")
                val mapping = build.file("outputs/mapping/$variant/mapping.txt")
                t.inputs.files(mainOutput, runtimeJars, ext.proguardRules)
                t.outputs.file(outJar)
                t.outputs.file(mapping)
                t.doFirst {
                    val args = mutableListOf<String>()
                    mainOutput.files.filter { it.exists() }.forEach { args += listOf("-injars", it.absolutePath) }
                    runtimeJars.files.forEach { args += listOf("-injars", "${it.absolutePath}(!META-INF/versions/**,!META-INF/*.version,!META-INF/MANIFEST.MF,!**.kotlin_builtins)") }
                    args += listOf("-outjars", outJar.get().asFile.absolutePath)
                    args += listOf("-libraryjars", sdk.androidJar.absolutePath)
                    args += listOf("-printmapping", mapping.get().asFile.absolutePath)
                    args += listOf("-include", linkRes.get().proguardRules.get().asFile.absolutePath)
                    ext.proguardRules.files.forEach { args += listOf("-include", it.absolutePath) }
                    mapping.get().asFile.parentFile.mkdirs()
                    t.setArgs(args)
                }
            } else null

            val dex = project.tasks.register("dex$cap", DexTask::class.java) {
                val t = this
                t.group = "android"
                if (shrink != null) {
                    t.dependsOn(shrink)
                    t.inputs_.from(build.file("intermediates/proguard/$variant/classes.jar"))
                } else {
                    t.dependsOn("classes")
                    t.inputs_.from(mainOutput, runtimeJars)
                }
                t.androidJar.set(sdk.androidJar)
                t.minSdk.set(ext.minSdk)
                t.dexer.set(sdk.dexer)
                t.release.set(release)
                t.output.set(build.dir("intermediates/dex/$variant"))
            }

            val pkg = project.tasks.register("package$cap", PackageApkTask::class.java) {
                val t = this
                t.group = "android"
                t.resources.set(linkRes.flatMap { it.output })
                t.dexDir.set(dex.flatMap { it.output })
                t.nativeLibs.set(ext.nativeLibsDir)
                t.abis.set(ext.abis)
                t.output.set(build.file("intermediates/apk/$variant/unaligned.apk"))
            }

            val sign = project.tasks.register("sign$cap", SignApkTask::class.java) {
                val t = this
                t.group = "android"
                t.description = "Aligns and signs the $variant APK, then verifies the signature."
                t.unsigned.set(pkg.flatMap { it.output })
                t.zipalign.set(sdk.zipalign)
                t.apksigner.set(sdk.apksigner)
                t.minSdk.set(ext.minSdk)
                t.variant.set(variant)
                t.signingProperties.set(project.rootProject.file("signing.properties"))
                t.debugKeystore.set(project.rootProject.layout.buildDirectory.file("debug-signing/debug.keystore"))
                t.output.set(build.file("outputs/apk/$variant/tuikio-$variant.apk"))
            }

            project.tasks.register("assemble${cap}Apk") {
                val t = this
                t.group = "android"
                t.description = "Builds the signed $variant APK."
                t.dependsOn(sign)
            }
        }
    }
}

abstract class TuikioAndroidExtension {
    abstract val minSdk: Property<Int>
    abstract val targetSdk: Property<Int>
    abstract val versionCode: Property<Int>
    abstract val versionName: Property<String>
    abstract val nativeLibsDir: DirectoryProperty
    abstract val abis: ListProperty<String>
    abstract val proguardRules: ConfigurableFileCollection

    /** Generated asset directories merged with src/main/assets (catalog snapshot, game data). */
    abstract val assetDirs: ConfigurableFileCollection

    init {
        minSdk.convention(26)
        targetSdk.convention(36)
        abis.convention(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
    }
}

/** Where the SDK tools live; every path can be overridden in local.properties (see docs/BUILD.md). */
class AndroidSdk(
    val androidJar: File,
    val frameworkRes: File,
    val aapt2: String,
    val zipalign: String,
    val apksigner: String,
    val dexer: String,
) {
    companion object {
        fun locate(project: Project): AndroidSdk {
            val props = Properties()
            val local = project.rootProject.file("local.properties")
            if (local.exists()) local.inputStream().use { props.load(it) }
            fun prop(key: String): String? =
                (project.findProperty(key) as String?) ?: props.getProperty(key)
            val sdkDir = prop("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
                ?: throw GradleException("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME (docs/BUILD.md)")
            val compileSdk = prop("tuikio.compileSdk") ?: "36"
            val androidJar = File(sdkDir, "platforms/android-$compileSdk/android.jar")
            val buildTools = File(sdkDir, "build-tools").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
            fun tool(key: String, name: String): String = prop(key) ?: buildTools?.let { File(it, name) }?.takeIf { it.exists() }?.absolutePath ?: name
            val d8 = buildTools?.let { File(it, "lib/d8.jar") }?.takeIf { it.exists() }
            val dexer = prop("tuikio.dexer") ?: d8?.let { "d8:${it.absolutePath}" }
                ?: prop("tuikio.dxJar")?.let { "dx:$it" }
                ?: throw GradleException("No dexer: install build-tools with d8, or set tuikio.dxJar in local.properties")
            return AndroidSdk(
                androidJar = androidJar,
                frameworkRes = prop("tuikio.frameworkRes")?.let { File(it) } ?: androidJar,
                aapt2 = tool("tuikio.aapt2", "aapt2"),
                zipalign = tool("tuikio.zipalign", "zipalign"),
                apksigner = tool("tuikio.apksigner", "apksigner"),
                dexer = dexer,
            )
        }
    }
}

abstract class Aapt2CompileTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val aapt2: Property<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val resDir: DirectoryProperty
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        output.get().asFile.parentFile.mkdirs()
        exec.exec { commandLine(aapt2.get(), "compile", "--dir", resDir.get().asFile.absolutePath, "-o", output.get().asFile.absolutePath) }
    }
}

abstract class Aapt2LinkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val aapt2: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val frameworkRes: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val manifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val compiled: RegularFileProperty
    @get:InputDirectory @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val assetsDir: DirectoryProperty
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targetSdk: Property<Int>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:OutputDirectory abstract val rJavaDir: DirectoryProperty
    @get:OutputFile abstract val output: RegularFileProperty
    @get:OutputFile abstract val proguardRules: RegularFileProperty

    @TaskAction
    fun run() {
        val r = rJavaDir.get().asFile
        r.deleteRecursively()
        r.mkdirs()
        val args = mutableListOf(
            aapt2.get(), "link",
            "-I", frameworkRes.get().asFile.absolutePath,
            "--manifest", manifest.get().asFile.absolutePath,
            "--min-sdk-version", minSdk.get().toString(),
            "--target-sdk-version", targetSdk.get().toString(),
            "--version-code", versionCode.get().toString(),
            "--version-name", versionName.get(),
            "--replace-version",
            "--java", r.absolutePath,
            "--proguard", proguardRules.get().asFile.absolutePath,
            "--auto-add-overlay",
            "--no-version-vectors",
            "-o", output.get().asFile.absolutePath,
        )
        val assets = assetsDir.orNull?.asFile
        if (assets != null && assets.isDirectory) args += listOf("-A", assets.absolutePath)
        args += compiled.get().asFile.absolutePath
        exec.exec { commandLine(args) }
    }
}

abstract class DexTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Classpath abstract val inputs_: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidJar: RegularFileProperty
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val dexer: Property<String>
    @get:Input abstract val release: Property<Boolean>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val out = output.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        // Strip multi-release and Kotlin metadata entries that old dexers reject.
        val staging = File(temporaryDir, "classes")
        staging.deleteRecursively()
        staging.mkdirs()
        for (f in inputs_.files.filter { it.exists() }) {
            if (f.isDirectory) {
                f.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach {
                    val dst = File(staging, it.relativeTo(f).path)
                    dst.parentFile.mkdirs()
                    it.copyTo(dst, overwrite = true)
                }
            } else {
                ZipFile(f).use { z ->
                    for (e in z.entries()) {
                        if (e.isDirectory || !e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
                        val dst = File(staging, e.name)
                        dst.parentFile.mkdirs()
                        z.getInputStream(e).use { input -> dst.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
        }
        val (kind, jar) = dexer.get().split(":", limit = 2).let { it[0] to it[1] }
        exec.javaexec {
            val spec = this
            spec.classpath(jar)
            if (kind == "d8") {
                spec.mainClass.set("com.android.tools.r8.D8")
                spec.args(
                    listOf(if (release.get()) "--release" else "--debug", "--min-api", minSdk.get().toString(),
                        "--lib", androidJar.get().asFile.absolutePath, "--output", out.absolutePath) +
                        staging.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList(),
                )
            } else {
                spec.mainClass.set("com.android.dx.command.Main")
                spec.maxHeapSize = "2g"
                spec.args("--dex", "--min-sdk-version=${minSdk.get()}", "--output=${out.absolutePath}", staging.absolutePath)
            }
        }
    }
}

abstract class PackageApkTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val resources: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val dexDir: DirectoryProperty
    @get:InputDirectory @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val nativeLibs: DirectoryProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val out = output.get().asFile
        out.parentFile.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            ZipFile(resources.get().asFile).use { res ->
                for (e in res.entries()) {
                    val data = res.getInputStream(e).use { it.readBytes() }
                    // resources.arsc must stay uncompressed (required for targetSdk >= 30).
                    put(zip, e.name, data, stored = e.method == ZipEntry.STORED || e.name == "resources.arsc")
                }
            }
            dexDir.get().asFile.listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }.forEach {
                put(zip, it.name, it.readBytes(), stored = false)
            }
            val libs = nativeLibs.orNull?.asFile
            if (libs == null || !libs.isDirectory) {
                logger.warn("No native executables found at $libs; the APK will contain no games. Run python3 native/build.py")
            } else {
                for (abi in abis.get()) {
                    val dir = File(libs, abi)
                    if (!dir.isDirectory) throw GradleException("Missing native build for $abi in $libs (run python3 native/build.py)")
                    dir.listFiles { f -> f.name.startsWith("lib") && f.name.endsWith(".so") }!!.sortedBy { it.name }.forEach {
                        put(zip, "lib/$abi/${it.name}", it.readBytes(), stored = false)
                    }
                }
            }
        }
    }

    private fun put(zip: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        e.time = 1199145600000L // fixed timestamp keeps the build reproducible
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = data.size.toLong()
            e.crc = CRC32().apply { update(data) }.value
        }
        zip.putNextEntry(e)
        zip.write(data)
        zip.closeEntry()
    }
}

abstract class SignApkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val unsigned: RegularFileProperty
    @get:Input abstract val zipalign: Property<String>
    @get:Input abstract val apksigner: Property<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val variant: Property<String>
    @get:org.gradle.api.tasks.Internal abstract val signingProperties: RegularFileProperty
    @get:org.gradle.api.tasks.Internal abstract val debugKeystore: RegularFileProperty
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val out = output.get().asFile
        out.parentFile.mkdirs()
        val aligned = File(temporaryDir, "aligned.apk")
        exec.exec { commandLine(zipalign.get(), "-f", "-p", "4", unsigned.get().asFile.absolutePath, aligned.absolutePath) }

        val key = signingKey()
        if (key == null) {
            aligned.copyTo(File(out.parentFile, out.name.replace(".apk", "-unsigned.apk")), overwrite = true)
            out.delete()
            logger.warn(
                "Release signing is not configured (no signing.properties / TUIKIO_KEYSTORE). " +
                    "Wrote an UNSIGNED APK next to ${out.name}; see docs/RELEASE.md.",
            )
            return
        }
        val signArgs = mutableListOf(
            apksigner.get(), "sign",
            "--ks", key.keystore, "--ks-key-alias", key.alias,
            "--ks-pass", "file:${key.storePassFile}",
        )
        // apksigner reads successive lines when both passwords name the same file; a PKCS12
        // key shares the store password, so only pass --key-pass when it really differs.
        if (key.keyPassFile != key.storePassFile) signArgs += listOf("--key-pass", "file:${key.keyPassFile}")
        signArgs += listOf(
            "--min-sdk-version", minSdk.get().toString(),
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
            "--out", out.absolutePath, aligned.absolutePath,
        )
        exec.exec { commandLine(signArgs) }
        val report = ByteArrayOutputStream()
        exec.exec {
            commandLine(apksigner.get(), "verify", "--min-sdk-version", minSdk.get().toString(), "--verbose", "--print-certs", out.absolutePath)
            standardOutput = report
        }
        val text = report.toString(Charsets.UTF_8)
        File(out.parentFile, "${out.nameWithoutExtension}-verify.txt").writeText(text)
        if (!text.contains("Verified using v2 scheme (APK Signature Scheme v2): true")) {
            throw GradleException("apksigner did not verify the v2 signature:\n$text")
        }
        logger.lifecycle("Signed and verified ${out.absolutePath}")
    }

    private class Key(val keystore: String, val alias: String, val storePassFile: String, val keyPassFile: String)

    private fun signingKey(): Key? {
        if (variant.get() == "debug") return debugKey()
        val f = signingProperties.get().asFile
        val p = Properties()
        if (f.exists()) f.inputStream().use { p.load(it) }
        val keystore = p.getProperty("storeFile") ?: System.getenv("TUIKIO_KEYSTORE") ?: return null
        val alias = p.getProperty("keyAlias") ?: System.getenv("TUIKIO_KEY_ALIAS") ?: return null
        val storePass = p.getProperty("storePasswordFile") ?: System.getenv("TUIKIO_KEYSTORE_PASSWORD_FILE") ?: return null
        val keyPass = p.getProperty("keyPasswordFile") ?: System.getenv("TUIKIO_KEY_PASSWORD_FILE") ?: storePass
        return Key(keystore, alias, storePass, keyPass)
    }

    /** A per-checkout debug key, generated on first use and never committed. */
    private fun debugKey(): Key {
        val ks = debugKeystore.get().asFile
        val pass = File(ks.parentFile, "debug.pass")
        if (!ks.exists()) {
            ks.parentFile.mkdirs()
            pass.writeText("android")
            exec.exec {
                commandLine(
                    "keytool", "-genkeypair", "-keystore", ks.absolutePath, "-storetype", "PKCS12",
                    "-storepass", "android", "-alias", "tuikiodebug", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-validity", "10000", "-dname", "CN=Tuikio Debug,O=Tuikio,C=XX",
                )
            }
        }
        return Key(ks.absolutePath, "tuikiodebug", pass.absolutePath, pass.absolutePath)
    }
}
