plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.courseprojectv3"
    // 使用普通整数形式的 SDK 配置，Android Studio 的 XML Preview 更容易识别和渲染。
    // 之前的 release(36) + minorApiLevel 写法可以编译，但 Layout Editor 兼容性较差。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.courseprojectv3"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(libs.androidx.junit)
}
