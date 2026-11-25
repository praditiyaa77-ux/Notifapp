plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "id.emypedia.emycash"
    compileSdk = 34
    defaultConfig {
        applicationId = "id.emypedia.emycash"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    // Tanda tangan tetap (opsional): isi secret KEYSTORE_B64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD di GitHub.
    // Tanpa itu APK memakai kunci debug runner, dan pembaruan di atas versi lama gagal dipasang (tanda tangan beda).
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrEmpty() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            val hasKs = !System.getenv("KEYSTORE_FILE").isNullOrEmpty()
            signingConfig = if (hasKs) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
