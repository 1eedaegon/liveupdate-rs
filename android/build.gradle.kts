import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("jacoco")
}

android {
    namespace = "io.github.eedaegon.liveupdate"
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

val buildNativeTest by tasks.registering(Exec::class) {
    val nativeOutput = layout.buildDirectory.dir("native-test-libs")
    inputs.files(
        fileTree("native-test/src"),
        file("native-test/Cargo.toml"),
        file("native-test/Cargo.lock"),
        rootProject.file("Cargo.toml"),
        rootProject.file("Cargo.lock"),
    )
    outputs.dir(nativeOutput)
    commandLine(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        nativeOutput.get().asFile.absolutePath,
        "build",
        "--locked",
        "--manifest-path",
        file("native-test/Cargo.toml").absolutePath,
    )
}

tasks.matching { it.name == "mergeDebugAndroidTestJniLibFolders" }.configureEach {
    dependsOn(buildNativeTest)
}

val coverageClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
val coverageData = fileTree(layout.buildDirectory) {
    include("outputs/unit_test_code_coverage/**/*.exec", "outputs/code_coverage/**/*.ec")
}

tasks.register<JacocoReport>("coverageReport") {
    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")
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
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
