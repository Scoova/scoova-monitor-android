plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization") version "1.9.22"
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.scoova.monitor.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Strip any leading "v" — JitPack passes the git tag as -Pversion,
        // and the tag is "v1.4.0", but telemetry should report "1.4.0".
        buildConfigField("String", "SDK_VERSION",
            "\"${project.version.toString().removePrefix("v")}\"")
    }

    // Expose a `release` software component for maven-publish. Without
    // singleVariant(), `from(components["release"])` below has nothing to
    // resolve and the publish task fails.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.startup:startup-runtime:1.1.1")
    // Play Install Referrer — auto-fills install_source / install_campaign
    // for Play Store installs. Free, official, no third-party SDK needed.
    implementation("com.android.installreferrer:installreferrer:2.2")
}

// ─── Publishing ───
// Maven Central coordinate: info.scoo-va:scoova-monitor-android
// (groupId is the verified scoo-va.info namespace). JitPack overrides
// the group via -Pgroup at build time, so it is unaffected.
group = "info.scoo-va"
version = "1.5.0"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "info.scoo-va"
                artifactId = "scoova-monitor-android"
                version = project.version.toString()

                pom {
                    name.set("Scoova Monitor Android SDK")
                    description.set("Crash reporting, analytics, performance monitoring, ANR detection, and logging for Android apps.")
                    url.set("https://github.com/Scoova/scoova-monitor-android")

                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("scoova")
                            name.set("Scoova")
                            email.set("dev@scoo-va.info")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/Scoova/scoova-monitor-android.git")
                        developerConnection.set("scm:git:ssh://github.com:Scoova/scoova-monitor-android.git")
                        url.set("https://github.com/Scoova/scoova-monitor-android")
                    }
                }
            }
        }

        repositories {
            // Local staging dir. `publishReleasePublicationToLocalStagingRepository`
            // writes the full signed Maven layout here; it is then zipped into a
            // bundle and uploaded to the Maven Central Portal.
            maven {
                name = "LocalStaging"
                url = uri(layout.buildDirectory.dir("staging-repo"))
            }
        }
    }

    // GPG signing — required by Maven Central. The key is supplied via
    // env (SIGNING_KEY = ASCII-armored private key, SIGNING_PASSWORD).
    // When absent — e.g. a JitPack build — signing is skipped.
    signing {
        val signingKey: String? = System.getenv("SIGNING_KEY")
        val signingPassword: String? = System.getenv("SIGNING_PASSWORD")
        isRequired = signingKey != null
        if (signingKey != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign(publishing.publications["release"])
    }
}
