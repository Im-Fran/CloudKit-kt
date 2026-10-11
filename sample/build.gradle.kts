import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Put CLOUDKIT_CONTAINER and CLOUDKIT_API_TOKEN in local.properties to try the sample.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "io.github.imfran.cloudkit.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.imfran.cloudkit.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["cloudkitAuthScheme"] = "cloudkit-sample"
        buildConfigField("String", "CLOUDKIT_CONTAINER", "\"${local.getProperty("CLOUDKIT_CONTAINER", "iCloud.com.example.app")}\"")
        buildConfigField("String", "CLOUDKIT_API_TOKEN", "\"${local.getProperty("CLOUDKIT_API_TOKEN", "")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":cloudkit-android"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
}
