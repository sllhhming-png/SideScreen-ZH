plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion = rootProject.file("../VERSION").readText().trim()
val versionParts = appVersion.split(".")
val computedVersionCode = versionParts[0].toInt() * 10000 + versionParts[1].toInt() * 100 + versionParts[2].toInt()

android {
    namespace = "com.sidescreen.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sidescreen.app"
        minSdk = 26
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = appVersion
        buildConfigField("boolean", "DIAGNOSTICS", "false")
        buildConfigField("boolean", "HISI_LOW_LATENCY_EXPERIMENT", "false")
    }

    buildTypes {
        create("diagnostic") {
            initWith(getByName("debug"))
            isDebuggable = false
            applicationIdSuffix = ".cn.diag"
            versionNameSuffix = "-cn-diag2"
            resValue("string", "app_name", "Side Screen 中文诊断")
            buildConfigField("boolean", "DIAGNOSTICS", "true")
            buildConfigField("boolean", "HISI_LOW_LATENCY_EXPERIMENT", "true")
            // Review/signing is owned by the controller; never use a private release key here.
            signingConfig = null
            matchingFallbacks += listOf("debug")
        }
        release {
            applicationIdSuffix = ".cn"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Wireless mode (0.8.0)
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    testImplementation("junit:junit:4.13.2")
}

androidComponents {
    onVariants(selector().withBuildType("diagnostic")) { variant ->
        variant.outputs.forEach { it.versionCode.set(1002) }
    }
}
