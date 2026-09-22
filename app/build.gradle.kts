plugins {
    id("com.android.application")
}

android {
    namespace = "org.orynnx.hypercontrol"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "org.orynnx.hypercontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation(files("libs/libxposed-service-102.0.0.jar"))
    implementation(files("libs/libxposed-service-interfaces-102.0.0.jar"))
    compileOnly(files("libs/libxposed-api-102.0.0.jar"))
}
