plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.yg.adskip"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.yg.adskip"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Xposed module: no launcher activity, no runtime deps beyond the framework-provided API
    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }
}

// 源码含中文注释，固定 UTF-8，避免 Windows 默认 GBK 解码报 unmappable character
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    // 框架在运行时注入 de.robv.android.xposed.*，只能 compileOnly，绝不能打进 APK
    compileOnly(files("libs/api-82.jar"))
}
