import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adamcarr.hondashlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adamcarr.hondashlauncher"
        minSdk = 33
        targetSdk = 34
        versionCode = 19
        versionName = "1.18"
    }

    // Release signing details live in keystore.properties at the project root, which is
    // not committed. Without it, builds are signed with the standard debug key instead.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val hasReleaseKey = keystoreProps.getProperty("storeFile") != null
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    // Kotlin metadata files aren't needed at runtime.
    packaging { resources.excludes += setOf("kotlin/**", "kotlin-tooling-metadata.json", "META-INF/**") }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
