import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("vlc.android.library")
    id("vlc.android.publishing")
}

val libVersion = rootProject.extra["remoteAccessVersion"].toString()
extra["library_version"] = libVersion

fun remoteAccessVersion(): String {
    try {
        val pkgFile = file("remoteaccess/package.json")
        if (pkgFile.exists()) {
            pkgFile.useLines { lines ->
                for (line in lines) {
                    if (line.contains("\"version\"")) {
                        return line.split("\"")[3]
                    }
                }
            }
        }
    } catch (_: Exception) {
    }
    return "unknown"
}

val remoteclientSourceDir = "$projectDir/remoteaccess"
val remoteAccessPackage = file("$remoteclientSourceDir/package.json")
val remoteAccessRevision = if (remoteAccessPackage.exists()) {
    providers.exec {
        workingDir(remoteclientSourceDir)
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
} else {
    "unknown"
}

android {
    namespace = "org.videolan.vlc.remoteaccessclient"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        resValue("string", "build_remote_access_revision", remoteAccessRevision)
        resValue("string", "remote_access_version", remoteAccessVersion())
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("assets")
        }
    }

    libraryVariants.all {
        outputs.all {
            val outputImpl = this as? BaseVariantOutputImpl
            if (outputImpl?.outputFileName?.endsWith(".aar") == true) {
                outputImpl.outputFileName = "vlc-android-remote-access-$libVersion.aar"
            }
        }
    }

    buildFeatures {
        resValues = true
    }
}

val webCopy = tasks.register<Copy>("webCopy") {
    from("remoteaccess/dist")
    into("assets/dist")
}

tasks.named("preBuild") {
    dependsOn(webCopy)
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

if (file("../../buildsystem/publish.gradle.kts").exists()) {
    apply(from = "../../buildsystem/publish.gradle.kts")
}
