plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "io.github.imfran.cloudkit.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":cloudkit"))
    implementation(libs.androidx.browser)
}

mavenPublishing {
    coordinates(artifactId = "cloudkit-android")
    publishToMavenCentral()
    signAllPublications()
}
