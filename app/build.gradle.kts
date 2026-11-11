import java.util.Properties

plugins {
    id("com.android.application")
}

// Ключ подписи хранится вне репозитория. Путь к файлу настроек можно переопределить
// свойством -Piaxphone.signing=...; по умолчанию ~/.android-keys/iaxphone-release.properties.
val signingProps = Properties().apply {
    val path = (findProperty("iaxphone.signing") as String?)
        ?: "${System.getProperty("user.home")}/.android-keys/iaxphone-release.properties"
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.glaude.iaxphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.glaude.iaxphone"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Без файла с ключом release-APK собирается неподписанным.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}
