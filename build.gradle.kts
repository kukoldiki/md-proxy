plugins {
    java
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.0"
}

version = "1.0"

val javaVersion = 25

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
}

kotlin {
    jvmToolchain(javaVersion)
}

sourceSets.main {
    java.srcDirs("src")
}

repositories {
    mavenCentral()

    maven("https://maven.xpdustry.com/releases")

    ivy {
        url = uri("https://github.com/")
        patternLayout {
            artifact("/[organisation]/[module]/releases/download/[revision]/dependencies.jar")
        }
        metadataSources {
            artifact()
        }
    }

    ivy {
        url = uri("https://github.com/")
        patternLayout {
            artifact("/[organisation]/[module]/releases/download/master/[revision].jar")
        }
        metadataSources {
            artifact()
        }
    }
}

val mindustryVersion = "v159"
val jabelVersion = "93fde537c7"
var nohornyVersion = "4.0.0-beta.7"

val useLatest = false

dependencies {
    compileOnly(
        if (useLatest)
            "Anuken:MindustryBuilds:latest"
        else
            "Anuken:Mindustry:$mindustryVersion"
    )
    implementation(kotlin("stdlib"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(javaVersion.toString()))
    }
}

tasks.jar {

    archiveFileName.set("${project.name}Desktop.jar")

    from({
        configurations.runtimeClasspath.get()
            .map { if (it.isDirectory) it else zipTree(it) }
    })

    from(rootDir) {
        include("mod.hjson")
    }

    from("assets/"){
        include("**")
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
