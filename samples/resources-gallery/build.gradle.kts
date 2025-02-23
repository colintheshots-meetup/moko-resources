/*
 * Copyright 2023 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        gradlePluginPortal()
    }
    dependencies {
        classpath(moko.resourcesGradlePlugin)
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10")
        classpath("com.android.tools.build:gradle:7.4.2")
        classpath("org.jetbrains.compose:compose-gradle-plugin:1.3.1")
    }
}

subprojects {
    this.plugins.withType<com.android.build.gradle.BasePlugin> {
        configure<BaseExtension> {
            compileSdkVersion(33)

            defaultConfig {
                minSdkVersion(16)
                targetSdkVersion(33)
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }
    }
}
