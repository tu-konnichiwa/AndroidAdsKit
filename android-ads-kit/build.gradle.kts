plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tukonnichiwa.adskit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.play.services.ads)
    implementation(libs.shimmer)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.user.messaging.platform)
    testImplementation(libs.junit)
}
