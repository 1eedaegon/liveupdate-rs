import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// What tests/android/consumer.sh gives: the AAR file, the folder of the Rust library built for the device,
// and whether this is a watch application that brings the library of the Ongoing Activity.
val aar = providers.gradleProperty("consumer.aar").get()
val jniLibs = providers.gradleProperty("consumer.jniLibs").get()
val wear = providers.gradleProperty("consumer.wear").orNull == "true"
// A version with action targets: the recorder service, which an earlier AAR could not compile.
val targets = providers.gradleProperty("consumer.targets").orNull == "true"

android {
    namespace = "io.github.eedaegon.liveupdate.consumer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.eedaegon.liveupdate.consumer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        // As a host ships: R8 and resource shrinking. Signed with the debug key to be installed.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].jniLibs.srcDir(jniLibs)
    if (targets) sourceSets["main"].java.srcDir("src/targets/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The README's installation, word for word.
    implementation(files(aar))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    if (wear) implementation("androidx.wear:wear-ongoing:1.1.0")
}
