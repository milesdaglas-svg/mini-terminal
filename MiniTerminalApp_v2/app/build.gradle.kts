plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.miniterminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.miniterminal"
        minSdk = 26
        // Deliberately below 29: Android 10+ blocks execve() of any binary
        // downloaded post-install (proot, and everything inside the Alpine
        // rootfs) for apps targeting API 29+ — a real W^X security policy,
        // not a bug. Termux itself relies on exactly this to function.
        // Confirmed directly by Termux maintainers: see
        // https://github.com/termux/termux-app/discussions/3372
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Pure-Java git implementation — no native binary, no bootstrap download.
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r")
    // Pure-Java tar.gz extraction, needed to unpack the Alpine rootfs.
    implementation("org.apache.commons:commons-compress:1.26.0")
}
