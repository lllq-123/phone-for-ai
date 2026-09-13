plugins { id("com.android.application") }

android {
    namespace = "dev.phoneforai.companion"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.phoneforai.companion"
        minSdk = 34
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
