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
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
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
group = "com.scoova.monitor"
version = "1.4.0"

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.scoova.monitor"
                artifactId = "sdk"
                version = project.version.toString()

                pom {
                    name.set("Scoova Monitor Android SDK")
                    description.set("Crash reporting, analytics, performance monitoring, and AI-powered fix suggestions for Android apps.")
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
            // GitHub Packages (works immediately)
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Scoova/scoova-monitor-android")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as? String ?: ""
                    password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.key") as? String ?: ""
                }
            }

            // Maven Central (when Sonatype account is ready)
            maven {
                name = "MavenCentral"
                val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                credentials {
                    username = System.getenv("OSSRH_USERNAME") ?: project.findProperty("ossrh.username") as? String ?: ""
                    password = System.getenv("OSSRH_PASSWORD") ?: project.findProperty("ossrh.password") as? String ?: ""
                }
            }
        }
    }

    // GPG signing (required for Maven Central)
    signing {
        isRequired = gradle.taskGraph.hasTask("publishReleasePublicationToMavenCentralRepository")
        sign(publishing.publications["release"])
    }
}
