import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("jacoco")
}

android {
    namespace = "io.github.gon.liveupdate"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        debug {
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = true
        }
    }

    sourceSets.getByName("androidTest") {
        jniLibs.srcDir(layout.buildDirectory.dir("native-test-libs"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

// The Rust library of the instrumentation tests (android/native-test). Options:
//   -Pliveupdate.nativeAbis=x86_64        the ABIs to build (default: arm64-v8a,x86_64)
//   -Pliveupdate.nativeCoverage=true      build it with -C instrument-coverage (tests/android/run.sh)
val nativeAbis = providers.gradleProperty("liveupdate.nativeAbis").orElse("arm64-v8a,x86_64")
val nativeCoverage = providers.gradleProperty("liveupdate.nativeCoverage").orElse("false")
val nativeTargets = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")

val buildNativeTest by tasks.registering(Exec::class) {
    val nativeOutput = layout.buildDirectory.dir("native-test-libs")
    val cargoTarget = layout.buildDirectory.dir("native-test-target")
    val abis = nativeAbis.get().split(",").map(String::trim).filter(String::isNotEmpty)
    val coverage = nativeCoverage.get().toBoolean()
    require(abis.isNotEmpty() && abis.all(nativeTargets::containsKey)) { "unknown ABI in ${nativeAbis.get()}" }
    inputs.files(
        fileTree("native-test/src"),
        file("native-test/Cargo.toml"),
        file("native-test/Cargo.lock"),
        rootProject.fileTree("src"),
        rootProject.file("Cargo.toml"),
        rootProject.file("Cargo.lock"),
    )
    inputs.property("abis", abis)
    inputs.property("coverage", coverage)
    outputs.dir(nativeOutput)
    val output = nativeOutput.get().asFile
    doFirst { output.deleteRecursively() }
    environment("CARGO_TARGET_DIR", cargoTarget.get().asFile.absolutePath)
    if (coverage) {
        for (abi in abis) {
            val triple = nativeTargets.getValue(abi).uppercase().replace("-", "_")
            environment("CARGO_TARGET_${triple}_RUSTFLAGS", "-C instrument-coverage")
        }
    }
    commandLine(
        listOf("cargo", "ndk") +
            abis.flatMap { listOf("-t", it) } +
            listOf("-o", nativeOutput.get().asFile.absolutePath, "build", "--locked") +
            (if (coverage) listOf("--features", "coverage") else emptyList()) +
            listOf("--manifest-path", file("native-test/Cargo.toml").absolutePath),
    )
}

tasks.matching { it.name == "mergeDebugAndroidTestJniLibFolders" }.configureEach {
    dependsOn(buildNativeTest)
}

// Kotlin coverage of the unit tests and of the instrumentation run on a device. tests/android/run.sh
// runs the instrumentation and pulls its execution data to build/outputs/code_coverage/device/.
val coverageClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
val deviceCoverage = layout.buildDirectory.dir("outputs/code_coverage/device")
val coverageData = fileTree(layout.buildDirectory) {
    include("outputs/unit_test_code_coverage/**/*.exec", "outputs/code_coverage/device/*.ec")
}

tasks.register<JacocoReport>("coverageReport") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(coverageData)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("coverageVerification") {
    dependsOn("coverageReport")
    classDirectories.setFrom(coverageClasses)
    executionData.setFrom(coverageData)
    val device = deviceCoverage
    doFirst {
        val pulled = device.get().asFile.listFiles { file -> file.extension == "ec" }.orEmpty()
        check(pulled.isNotEmpty()) {
            "No instrumentation coverage in ${device.get().asFile}: run tests/android/run.sh on an emulator first"
        }
    }
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}
