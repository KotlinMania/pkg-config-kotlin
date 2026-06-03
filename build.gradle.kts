import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import org.gradle.api.GradleException
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec

plugins {
    kotlin("multiplatform") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("com.android.kotlin.multiplatform.library") version "9.2.1"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.github.kotlinmania"
version = "0.1.0"

val androidCommandLineToolsRevision = "14742923"
val projectCompileSdk = "34"
val projectAndroidBuildTools = "36.0.0"
val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")
val androidSdkOsName =
    when {
        isWindowsHost -> "win"
        System.getProperty("os.name").lowercase().contains("mac") -> "mac"
        System.getProperty("os.name").lowercase().contains("linux") -> "linux"
        else -> throw GradleException("Unsupported Android SDK setup OS: ${System.getProperty("os.name")}")
    }
val projectAndroidSdkDir = layout.projectDirectory.dir(".android-sdk").asFile
val androidSdkManager = projectAndroidSdkDir.resolve(
    if (isWindowsHost) {
        "cmdline-tools/latest/bin/sdkmanager.bat"
    } else {
        "cmdline-tools/latest/bin/sdkmanager"
    },
)
val androidSdkInstallMarker = projectAndroidSdkDir.resolve(".install-complete")
val requiredAndroidSdkPackageDirs = listOf(
    projectAndroidSdkDir.resolve("platform-tools"),
    projectAndroidSdkDir.resolve("platforms/android-$projectCompileSdk"),
    projectAndroidSdkDir.resolve("build-tools/$projectAndroidBuildTools"),
)

fun isProjectAndroidSdkInstalled(): Boolean {
    val installed =
        androidSdkInstallMarker.exists() &&
            androidSdkManager.exists() &&
            requiredAndroidSdkPackageDirs.all { it.exists() }
    if (!installed && androidSdkInstallMarker.exists()) {
        androidSdkInstallMarker.delete()
    }
    return installed
}

fun writeAndroidLocalProperties() {
    val sdkDirPropertyValue = projectAndroidSdkDir.absolutePath.replace("\\", "/")
    layout.projectDirectory.file("local.properties").asFile.writeText("sdk.dir=$sdkDirPropertyValue\n")
}

fun sdkManagerCommand(vararg args: String): List<String> =
    if (isWindowsHost) {
        listOf("cmd", "/c", androidSdkManager.absolutePath) + args
    } else {
        listOf(androidSdkManager.absolutePath) + args
    }

fun downloadAndroidCommandLineTools() {
    val zipName = "commandlinetools-$androidSdkOsName-${androidCommandLineToolsRevision}_latest.zip"
    val url = "https://dl.google.com/android/repository/$zipName"
    val tmpDir = projectAndroidSdkDir.resolve(".tmp/commandline-tools")
    val zipFile = tmpDir.resolve(zipName)
    val latestDir = projectAndroidSdkDir.resolve("cmdline-tools/latest")

    println("setup-android-sdk: downloading $url")
    tmpDir.deleteRecursively()
    tmpDir.mkdirs()

    try {
        URI(url).toURL().openStream().use { input ->
            Files.copy(input, zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        latestDir.deleteRecursively()
        latestDir.mkdirs()
        val canonicalLatestDir = latestDir.canonicalFile.toPath()

        ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
            generateSequence { zipInput.nextEntry }.forEach { entry ->
                val relativeName = entry.name.removePrefix("cmdline-tools/").trimStart('/')
                if (relativeName.isNotEmpty()) {
                    val target = latestDir.resolve(relativeName).canonicalFile
                    if (!target.toPath().startsWith(canonicalLatestDir)) {
                        throw GradleException("Refusing to extract Android SDK entry outside $latestDir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile.mkdirs()
                        Files.copy(zipInput, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        if (!isWindowsHost && relativeName.startsWith("bin/")) {
                            target.setExecutable(true)
                        }
                    }
                }
                zipInput.closeEntry()
            }
        }

        if (!isWindowsHost) {
            androidSdkManager.setExecutable(true)
        }
    } finally {
        tmpDir.deleteRecursively()
    }
}

fun installProjectAndroidSdk(execOperations: ExecOperations) {
    if (isProjectAndroidSdkInstalled()) {
        writeAndroidLocalProperties()
        println("setup-android-sdk: SDK already installed at $projectAndroidSdkDir")
        return
    }

    if (!androidSdkManager.exists()) {
        downloadAndroidCommandLineTools()
    }

    println("setup-android-sdk: pre-accepting licenses")
    val licensesDir = projectAndroidSdkDir.resolve("licenses")
    licensesDir.mkdirs()

    val licenseHashes = mapOf(
        "android-sdk-license" to "24333f8a63b6825ea9c5514f83c2829b004d1fee",
        "android-googletv-license" to "601085b94cd77f0b54ff86406957099ebe79c4d6",
        "android-sdk-arm-dbt-license" to "859f317696f67ef3d7f30a50a5560e7834b43903",
        "android-sdk-preview-license" to "84831b9409646a918e30573bab4c9c91346d8abd",
        "google-gdk-license" to "33b6a2b64607f11b759f320ef9dff4ae5c47d97a",
        "intel-android-extra-license" to "d975f751698a77b662f1254ddbeed3901e976f5a",
        "mips-android-sysimage-license" to "e9acab5b5fbb560a72cfaecce8946896ff6aab9d"
    )

    licenseHashes.forEach { (licenseName, hash) ->
        licensesDir.resolve(licenseName).writeText("$hash\n")
    }

    println("setup-android-sdk: installing platform-tools, android-$projectCompileSdk, build-tools;$projectAndroidBuildTools")
    val installLog = projectAndroidSdkDir.resolve("sdkmanager-install.log")
    installLog.parentFile.mkdirs()
    installLog.outputStream().use { output ->
        val installExecResult = execOperations.exec {
            commandLine(
                sdkManagerCommand(
                    "--sdk_root=${projectAndroidSdkDir.absolutePath}",
                    "platform-tools",
                    "platforms;android-$projectCompileSdk",
                    "build-tools;$projectAndroidBuildTools",
                ),
            )
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        if (installExecResult.exitValue != 0) {
            throw GradleException(
                "Android SDK package install failed with exit code ${installExecResult.exitValue}. " +
                    "Install log:\n${installLog.readText()}",
            )
        }
    }
    println("setup-android-sdk: install log at $installLog")

    writeAndroidLocalProperties()
    androidSdkInstallMarker.writeText("")
    println("setup-android-sdk: done")
    println("  SDK at:     $projectAndroidSdkDir")
    println("  configured: local.properties -> $projectAndroidSdkDir")
}

// The Android Gradle plugin resolves the SDK location while Gradle builds the
// task graph, before any task executes, so a project-local Android SDK must
// already be installed by the time configuration reaches the android target.
// This configuration-time installer is idempotent and always writes
// local.properties to this repo's own .android-sdk path.
val androidSdkExecOperations = serviceOf<ExecOperations>()
installProjectAndroidSdk(androidSdkExecOperations)

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("posix") {
                withLinux()
                withApple()
                withAndroidNative()
            }
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    val xcf = XCFramework("PkgConfig")

    macosArm64 {
        binaries.framework { baseName = "PkgConfig"; xcf.add(this) }
    }
    iosArm64 {
        binaries.framework { baseName = "PkgConfig"; xcf.add(this) }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "PkgConfig"
            isStatic = true
            xcf.add(this)
        }
    }
    iosX64 {
        // iOS Simulator targets share an XCFramework "fat" stage that
        // requires every input framework to be either all static or all
        // dynamic. iosSimulatorArm64 is already declared static for the
        // Swift Export SPM bridge, so iosX64 must match.
        binaries.framework {
            baseName = "PkgConfig"
            isStatic = true
            xcf.add(this)
        }
    }

    tvosArm64 {
        binaries.framework { baseName = "PkgConfig"; xcf.add(this) }
    }
    tvosSimulatorArm64 {
        binaries.framework { baseName = "PkgConfig"; xcf.add(this) }
    }

    watchosArm32 {
        binaries.framework { baseName = "PkgConfig"; xcf.add(this) }
    }
    watchosArm64 {
        binaries.framework { baseName = "PkgConfig"; xcf.add(this) }
    }
    watchosDeviceArm64 {
        binaries.framework { baseName = "PkgConfig"; xcf.add(this) }
    }
    watchosSimulatorArm64 {
        binaries.framework { baseName = "PkgConfig"; xcf.add(this) }
    }

    linuxX64()
    linuxArm64()
    mingwX64()

    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    js {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    swiftExport {
        moduleName = "PkgConfig"
        flattenPackage = "io.github.kotlinmania.pkgconfig"
    }

    android {
        namespace = "io.github.kotlinmania.pkgconfig"
        compileSdk = 34
        minSdk = 24
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
    }

    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // The posixMain / posixTest intermediate source sets for the
        // src/posixMain/.../PosixPlatform.kt actual are now automatically
        // configured via applyDefaultHierarchyTemplate above. PR #15 (sync
        // from http-kotlin) dropped these because http-kotlin does not use
        // posix actuals; pkg-config-kotlin's commonMain `internal expect fun
        // envVar / pathExists / pathIsFile / spawnProcess / printStdoutLine /
        // currentTargetOs` are answered by the shared posix actual on every
        // Kotlin/Native target except mingwX64 (which has its own
        // MingwPlatform.kt).
    }
    jvmToolchain(21)
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events(
            TestLogEvent.STARTED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
            TestLogEvent.FAILED,
            TestLogEvent.STANDARD_OUT,
            TestLogEvent.STANDARD_ERROR,
        )
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}

rootProject.extensions.configure<NodeJsEnvSpec>("kotlinNodeJsSpec") {
    version.set("24.15.0")
}

rootProject.extensions.configure<WasmNodeJsEnvSpec>("kotlinWasmNodeJsSpec") {
    version.set("24.15.0")
}

rootProject.extensions.configure<YarnRootEnvSpec>("kotlinYarnSpec") {
    version.set("1.22.22")
}

rootProject.extensions.configure<WasmYarnRootEnvSpec>("kotlinWasmYarnSpec") {
    version.set("1.22.22")
}

rootProject.extensions.configure<YarnRootExtension>("kotlinYarn") {
    resolution("diff", "8.0.3")
    resolution("**/diff", "8.0.3")
    resolution("fast-uri", "3.1.2")
    resolution("**/fast-uri", "3.1.2")
    resolution("serialize-javascript", "7.0.5")
    resolution("**/serialize-javascript", "7.0.5")
    resolution("webpack", "5.106.2")
    resolution("**/webpack", "5.106.2")
    resolution("follow-redirects", "1.16.0")
    resolution("**/follow-redirects", "1.16.0")
    resolution("lodash", "4.18.1")
    resolution("**/lodash", "4.18.1")
    resolution("ajv", "8.20.0")
    resolution("**/ajv", "8.20.0")
    resolution("brace-expansion", "5.0.6")
    resolution("**/brace-expansion", "5.0.6")
    resolution("flatted", "3.4.2")
    resolution("**/flatted", "3.4.2")
    resolution("minimatch", "10.2.5")
    resolution("**/minimatch", "10.2.5")
    resolution("picomatch", "4.0.4")
    resolution("**/picomatch", "4.0.4")
    resolution("qs", "6.15.2")
    resolution("**/qs", "6.15.2")
    resolution("socket.io-parser", "4.2.6")
    resolution("**/socket.io-parser", "4.2.6")
    resolution("ws", "8.20.1")
    resolution("**/ws", "8.20.1")
}


val patchedKarmaWebpackPackage = rootProject.layout.projectDirectory.dir("gradle/npm/karma-webpack").asFile.absolutePath.replace("\\", "/")

rootProject.extensions.configure<NodeJsRootExtension>("kotlinNodeJs") {
    versions.webpack.version = "5.106.2"
    versions.webpackCli.version = "7.0.2"
    versions.karma.version = "npm:karma-maintained@6.4.7"
    versions.karmaWebpack.version = "file:$patchedKarmaWebpackPackage"
    versions.mocha.version = "12.0.0-beta-10"
    versions.kotlinWebHelpers.version = "3.1.0"
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "pkg-config-kotlin", version.toString())

    pom {
        name.set("pkg-config-kotlin")
        description.set("Kotlin Multiplatform port of rust-lang/pkg-config-rs - A library to run the pkg-config system tool at build time in order to be used in Cargo build scripts")
        inceptionYear.set("2026")
        url.set("https://github.com/KotlinMania/pkg-config-kotlin")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("sydneyrenee")
                name.set("Sydney Renee")
                email.set("sydney@solace.ofharmony.ai")
                url.set("https://github.com/sydneyrenee")
            }
        }

        scm {
            url.set("https://github.com/KotlinMania/pkg-config-kotlin")
            connection.set("scm:git:git://github.com/KotlinMania/pkg-config-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/KotlinMania/pkg-config-kotlin.git")
        }
    }
}

// ---------------------------------------------------------------------------
// CodeQL Java/Kotlin extraction task
//
// .github/workflows/codeql.yml invokes `./gradlew codeqlCompileJvm` to feed
// kotlinc-compiled commonMain through the CodeQL Java agent.
val codeqlKotlinc: Configuration by configurations.creating {
    description = "Kotlin compiler (CodeQL extraction target only - not published)"
    isCanBeResolved = true
    isCanBeConsumed = false
}

val codeqlSourceClasspath: Configuration by configurations.creating {
    description = "Runtime classpath for CodeQL extraction of commonMain sources"
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    codeqlKotlinc("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.0")
    codeqlSourceClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.8.0")
    codeqlSourceClasspath("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.4.0")
}

val codeqlCompileJvm = tasks.register<JavaExec>("codeqlCompileJvm") {
    description =
        "Compile commonMain Kotlin sources with kotlinc 2.3.21 for CodeQL Java/Kotlin extraction."
    group = "verification"

    classpath(codeqlKotlinc)
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")

    val outDir = layout.buildDirectory.dir("classes/kotlin/codeql-jvm")
    val sources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
    val sentinelDir = layout.buildDirectory.dir("generated/codeql-empty-source")
    inputs.files(sources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(codeqlSourceClasspath).withNormalizer(ClasspathNormalizer::class.java)
    outputs.dir(outDir)
    outputs.dir(sentinelDir)

    doFirst {
        outDir.get().asFile.mkdirs()
        val fullClasspath =
            codeqlSourceClasspath.resolve()
                .joinToString(File.pathSeparator) { it.absolutePath }
        val sourceFiles = sources.files.toMutableList()
        if (sourceFiles.isEmpty()) {
            val sentinelFile = sentinelDir.get().asFile.resolve("io/github/kotlinmania/pkgconfig/CodeqlEmptySentinel.kt")
            sentinelFile.parentFile.mkdirs()
            sentinelFile.writeText(
                """
                // Auto-generated. Present so codeqlCompileJvm has at least
                // one Kotlin source to feed kotlinc; replaced by real
                // commonMain content once porting begins.
                package io.github.kotlinmania.pkgconfig

                private object CodeqlEmptySentinel
                """.trimIndent(),
            )
            sourceFiles += sentinelFile
        }
        args = listOf(
            "-d", outDir.get().asFile.absolutePath,
            "-classpath", fullClasspath,
            "-jvm-target", "21",
            "-no-stdlib",
            "-no-reflect",
            "-language-version", "2.3",
            "-api-version", "2.3",
            "-Xexpect-actual-classes",
            "-opt-in", "kotlin.time.ExperimentalTime",
            "-opt-in", "kotlin.concurrent.atomics.ExperimentalAtomicApi",
        ) + sourceFiles.map { it.absolutePath }
    }
}

tasks.register("setupAndroidSdk") {
    group = "setup"
    description = "Downloads and configures the project-local Android SDK."
    doLast {
        installProjectAndroidSdk(androidSdkExecOperations)
    }
}

tasks.register("test") {
    group = "verification"
    description =
        "Runs the host-portable test suite (macOS + JS + WasmJS + Android unit). " +
        "Non-host native targets (mingwX64, linuxX64) only run on their own host."

    val defaultTestTasks = listOf(
        "macosArm64Test",
        "jvmTest",
        "jsNodeTest",
        "wasmJsNodeTest",
        "compileAndroidMain",
        "assembleUnitTest",
    )

    dependsOn(defaultTestTasks.mapNotNull { taskName -> tasks.findByName(taskName) })
}

// The generated Wasm-WASI Node test runner cannot see the filesystem unless
// the project directory is preopened. Patch the runner before wasmWasiNodeTest.
val patchWasmWasiNodePreopens = tasks.register("patchWasmWasiNodePreopens") {
    description = "Preopen the project directory for the generated Wasm-WASI Node test runner."
    group = "verification"
    dependsOn("compileTestDevelopmentExecutableKotlinWasmWasi")
    outputs.upToDateWhen { false }

    doLast {
        val runnerFile = layout.buildDirectory.file(
            "compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/${rootProject.name}-test.mjs",
        ).get().asFile
        if (!runnerFile.exists()) {
            // No Wasm-WASI test runner was generated (the repo has no
            // wasmWasi test sources), so there is nothing to preopen.
            return@doLast
        }
        val text = runnerFile.readText()
        val withCwdImport = text.replace(
            "import { argv, env } from 'node:process';",
            "import { argv, env, cwd } from 'node:process';",
        )
        val patched = withCwdImport.replace(
            "const wasi = new WASI({ version: 'preview1', args: argv, env, });",
            "const wasi = new WASI({ version: 'preview1', args: argv, env, preopens: { '/': cwd() }, });",
        )
        runnerFile.writeText(patched)
    }
}

tasks.named("wasmWasiNodeTest") {
    dependsOn(patchWasmWasiNodePreopens)
}

val xcodeSwiftExportEnvironmentNames = listOf(
    "SDK_NAME",
    "CONFIGURATION",
    "TARGET_BUILD_DIR",
    "BUILT_PRODUCTS_DIR",
    "ARCHS",
    "FRAMEWORKS_FOLDER_PATH",
    "DEPLOYMENT_TARGET_SETTING_NAME",
)

fun hasXcodeSwiftExportEnvironment(): Boolean {
    if (!xcodeSwiftExportEnvironmentNames.all { !System.getenv(it).isNullOrBlank() }) {
        return false
    }

    val deploymentTargetSettingName = System.getenv("DEPLOYMENT_TARGET_SETTING_NAME")
    return !System.getenv(deploymentTargetSettingName).isNullOrBlank()
}

val swiftExportTaskDirectlyRequested =
    gradle.startParameter.taskNames.any { it == "embedSwiftExportForXcode" || it.endsWith(":embedSwiftExportForXcode") }

tasks.matching { it.name == "embedSwiftExportForXcode" }.configureEach {
    onlyIf {
        val hasXcodeEnvironment = hasXcodeSwiftExportEnvironment()
        if (!hasXcodeEnvironment && !swiftExportTaskDirectlyRequested) {
            logger.lifecycle("embedSwiftExportForXcode: skipped because Xcode environment variables are not present")
        }
        hasXcodeEnvironment || swiftExportTaskDirectlyRequested
    }
}

val fullTargetBuildTasks = listOf(
    "compileAndroidMain",
    "compileAndroidHostTest",
    "compileAndroidDeviceTest",
    "assembleAndroidMain",
    "assembleAndroidHostTest",
    "assembleAndroidDeviceTest",
    "assembleUnitTest",
    "assembleAndroidTest",
    "testAndroidHostTest",
    "jvmMainClasses",
    "jvmTestClasses",
    "jvmTest",
    "jsMainClasses",
    "jsTestClasses",
    "jsBrowserTest",
    "jsNodeTest",
    "jsTest",
    "wasmJsMainClasses",
    "wasmJsTestClasses",
    "wasmJsBrowserTest",
    "wasmJsNodeTest",
    "wasmJsTest",
    "wasmWasiMainClasses",
    "wasmWasiTestClasses",
    "wasmWasiNodeTest",
    "wasmWasiTest",
    "androidNativeArm32Binaries",
    "androidNativeArm32TestBinaries",
    "androidNativeArm64Binaries",
    "androidNativeArm64TestBinaries",
    "androidNativeX64Binaries",
    "androidNativeX64TestBinaries",
    "androidNativeX86Binaries",
    "androidNativeX86TestBinaries",
    "iosArm64Binaries",
    "iosArm64TestBinaries",
    "iosSimulatorArm64Binaries",
    "iosSimulatorArm64TestBinaries",
    "iosX64Binaries",
    "iosX64TestBinaries",
    "linuxArm64Binaries",
    "linuxArm64TestBinaries",
    "linuxX64Binaries",
    "linuxX64TestBinaries",
    "linuxX64Test",
    "macosArm64Binaries",
    "macosArm64TestBinaries",
    "macosArm64Test",
    "mingwX64Binaries",
    "mingwX64TestBinaries",
    "mingwX64Test",
    "tvosArm64Binaries",
    "tvosArm64TestBinaries",
    "tvosSimulatorArm64Binaries",
    "tvosSimulatorArm64TestBinaries",
    "watchosArm32Binaries",
    "watchosArm32TestBinaries",
    "watchosArm64Binaries",
    "watchosArm64TestBinaries",
    "watchosDeviceArm64Binaries",
    "watchosDeviceArm64TestBinaries",
    "watchosSimulatorArm64Binaries",
    "watchosSimulatorArm64TestBinaries",
    "embedSwiftExportForXcode",
    "assemblePkgConfigXCFramework",
    "assemblePkgConfigDebugXCFramework",
    "assemblePkgConfigReleaseXCFramework",
    "assembleDebugIosFatFrameworkForPkgConfigXCFramework",
    "assembleReleaseIosFatFrameworkForPkgConfigXCFramework",
    "assembleDebugIosSimulatorFatFrameworkForPkgConfigXCFramework",
    "assembleReleaseIosSimulatorFatFrameworkForPkgConfigXCFramework",
    "assembleDebugMacosFatFrameworkForPkgConfigXCFramework",
    "assembleReleaseMacosFatFrameworkForPkgConfigXCFramework",
    "assembleDebugTvosFatFrameworkForPkgConfigXCFramework",
    "assembleReleaseTvosFatFrameworkForPkgConfigXCFramework",
    "assembleDebugTvosSimulatorFatFrameworkForPkgConfigXCFramework",
    "assembleReleaseTvosSimulatorFatFrameworkForPkgConfigXCFramework",
    "assembleDebugWatchosFatFrameworkForPkgConfigXCFramework",
    "assembleReleaseWatchosFatFrameworkForPkgConfigXCFramework",
    "assembleDebugWatchosSimulatorFatFrameworkForPkgConfigXCFramework",
    "assembleReleaseWatchosSimulatorFatFrameworkForPkgConfigXCFramework",
    "exportCommonSourceSetsMetadataLocationsForMetadataApiElements",
    "exportRootPublicationCoordinatesForMetadataApiElements",
    "exportCrossCompilationMetadataForAndroidNativeArm32ApiElements",
    "exportCrossCompilationMetadataForAndroidNativeArm64ApiElements",
    "exportCrossCompilationMetadataForAndroidNativeX64ApiElements",
    "exportCrossCompilationMetadataForAndroidNativeX86ApiElements",
    "exportCrossCompilationMetadataForIosArm64ApiElements",
    "exportCrossCompilationMetadataForIosSimulatorArm64ApiElements",
    "exportCrossCompilationMetadataForIosX64ApiElements",
    "exportCrossCompilationMetadataForLinuxArm64ApiElements",
    "exportCrossCompilationMetadataForLinuxX64ApiElements",
    "exportCrossCompilationMetadataForMacosArm64ApiElements",
    "exportCrossCompilationMetadataForMingwX64ApiElements",
    "exportCrossCompilationMetadataForTvosArm64ApiElements",
    "exportCrossCompilationMetadataForTvosSimulatorArm64ApiElements",
    "exportCrossCompilationMetadataForWatchosArm32ApiElements",
    "exportCrossCompilationMetadataForWatchosArm64ApiElements",
    "exportCrossCompilationMetadataForWatchosDeviceArm64ApiElements",
    "exportCrossCompilationMetadataForWatchosSimulatorArm64ApiElements",
    "exportTargetPublicationCoordinatesForAndroidApiElements",
    "exportTargetPublicationCoordinatesForAndroidNativeArm32ApiElements",
    "exportTargetPublicationCoordinatesForAndroidNativeArm64ApiElements",
    "exportTargetPublicationCoordinatesForAndroidNativeX64ApiElements",
    "exportTargetPublicationCoordinatesForAndroidNativeX86ApiElements",
    "exportTargetPublicationCoordinatesForAndroidRuntimeElements",
    "exportTargetPublicationCoordinatesForIosArm64ApiElements",
    "exportTargetPublicationCoordinatesForIosSimulatorArm64ApiElements",
    "exportTargetPublicationCoordinatesForIosX64ApiElements",
    "exportTargetPublicationCoordinatesForJsApiElements",
    "exportTargetPublicationCoordinatesForJsRuntimeElements",
    "exportTargetPublicationCoordinatesForJvmApiElements",
    "exportTargetPublicationCoordinatesForJvmRuntimeElements",
    "exportTargetPublicationCoordinatesForLinuxArm64ApiElements",
    "exportTargetPublicationCoordinatesForLinuxX64ApiElements",
    "exportTargetPublicationCoordinatesForMacosArm64ApiElements",
    "exportTargetPublicationCoordinatesForMingwX64ApiElements",
    "exportTargetPublicationCoordinatesForTvosArm64ApiElements",
    "exportTargetPublicationCoordinatesForTvosSimulatorArm64ApiElements",
    "exportTargetPublicationCoordinatesForWasmJsApiElements",
    "exportTargetPublicationCoordinatesForWasmJsRuntimeElements",
    "exportTargetPublicationCoordinatesForWasmWasiApiElements",
    "exportTargetPublicationCoordinatesForWasmWasiRuntimeElements",
    "exportTargetPublicationCoordinatesForWatchosArm32ApiElements",
    "exportTargetPublicationCoordinatesForWatchosArm64ApiElements",
    "exportTargetPublicationCoordinatesForWatchosDeviceArm64ApiElements",
    "exportTargetPublicationCoordinatesForWatchosSimulatorArm64ApiElements",
)

tasks.named("build") {
    dependsOn(fullTargetBuildTasks)
}

afterEvaluate {
    tasks.named("build") {
        dependsOn(
            tasks.matching {
                name.endsWith("MainClasses") ||
                    name.endsWith("TestClasses") ||
                    name.endsWith("Binaries") ||
                    name.endsWith("XCFramework") ||
                    name == "embedSwiftExportForXcode" ||
                    name.startsWith("exportCommonSourceSetsMetadataLocationsFor") ||
                    name.startsWith("exportRootPublicationCoordinatesFor") ||
                    name.startsWith("exportCrossCompilationMetadataFor") ||
                    name.startsWith("exportTargetPublicationCoordinatesFor")
            },
        )
    }
}
