import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

fun getGitCommitHash(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        standardOutput = stdout
    }
    return stdout.toString().trim()
}

fun getGitCommitDate(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "show", "-s", "--format=%cI", "HEAD")
        standardOutput = stdout
    }
    return stdout.toString().trim()
}

fun getFullGitCommitHash(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "rev-parse", "HEAD")
        standardOutput = stdout
    }
    return stdout.toString().trim()
}


android {
    namespace = "com.qali.aterm"
    android.buildFeatures.buildConfig = true
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            buildConfigField("String", "GIT_COMMIT_HASH", "\"${getFullGitCommitHash()}\"")
            buildConfigField("String", "GIT_SHORT_COMMIT_HASH", "\"${getGitCommitHash()}\"")
            buildConfigField("String", "GIT_COMMIT_DATE", "\"${getGitCommitDate()}\"")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        debug{
            buildConfigField("String", "GIT_COMMIT_HASH", "\"${getFullGitCommitHash()}\"")
            buildConfigField("String", "GIT_SHORT_COMMIT_HASH", "\"${getGitCommitHash()}\"")
            buildConfigField("String", "GIT_COMMIT_DATE", "\"${getGitCommitDate()}\"")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

}

dependencies {
    api(libs.appcompat)
    api(libs.material)
    api(libs.constraintlayout)
    api(libs.navigation.fragment)
    api(libs.navigation.ui)
    api(libs.asynclayoutinflater)
    api(libs.navigation.fragment.ktx)
    api(libs.navigation.ui.ktx)
    api(libs.activity)
    api(libs.lifecycle.livedata.ktx)
    api(libs.lifecycle.viewmodel.ktx)
    api(libs.lifecycle.runtime.ktx)
    api(libs.activity.compose)
    api(platform(libs.compose.bom))
    api(libs.ui)
    api(libs.ui.graphics)
    api(libs.material3)
    api(libs.material.icons.extended)
    api(libs.navigation.compose)
    api(project(":core:terminal-view"))
    api(project(":core:terminal-emulator"))
    api(libs.utilcode)
    //api(libs.commons.net)
    api(libs.okhttp)
    api(libs.anrwatchdog)
    api(libs.androidx.palette)
    api(libs.accompanist.systemuicontroller)
    api(libs.gson)
//    api(libs.termux.shared)

    // ONNX Runtime for CodeBERT
    api("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    api(project(":core:resources"))
    api(project(":core:components"))
    
    // YAML parsing for PPE scripts
    api(libs.snakeyaml.engine)
}
