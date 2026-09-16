plugins {
    java
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
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

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")

// dexes and desugars the desktop jar (including the bundled Kotlin stdlib) into an
// Android-compatible jar, using d8 from the Android SDK build-tools.
val jarAndroid = tasks.register("jarAndroid") {
    dependsOn(tasks.jar)

    doLast {
        if (sdkRoot == null || !file(sdkRoot).exists()) {
            throw GradleException("No valid Android SDK found. Ensure that ANDROID_HOME (or ANDROID_SDK_ROOT) is set to your Android SDK directory.")
        }

        val platformRoot = file("$sdkRoot/platforms").listFiles()
            ?.sortedDescending()
            ?.find { file(it).resolve("android.jar").exists() }
            ?: throw GradleException("No android.jar found. Ensure that you have an Android platform installed.")

        val buildToolsDir = file("$sdkRoot/build-tools").listFiles()
            ?.filter { it.name.matches(Regex("""\d+\.\d+\.\d+""")) }
            ?.maxByOrNull { it.name.split(".")[0].toInt() }
            ?: throw GradleException("No Android build-tools found. Install a build-tools version via the Android SDK manager.")

        val d8 = file(buildToolsDir).resolve(if (isWindows) "d8.bat" else "d8")
        if (!d8.exists()) throw GradleException("d8 not found at $d8")

        val classpath = (configurations.compileClasspath.get().files + configurations.runtimeClasspath.get().files + file(platformRoot).resolve("android.jar"))
            .flatMap { listOf("--classpath", it.path) }

        val desktopJar = file("${layout.buildDirectory.get()}/libs/${project.name}Desktop.jar")
        val outputJar = file("${layout.buildDirectory.get()}/libs/${project.name}Android.jar")

        val command = listOf(d8.path) + classpath + listOf("--min-api", "21", "--output", outputJar.path, desktopJar.path)
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        if (process.waitFor() != 0) throw GradleException("d8 failed to dex $desktopJar")
    }
}

// packages the desktop and Android (dexed) classes into a single jar that works on both platforms.
tasks.register<Jar>("deploy") {
    dependsOn(tasks.jar)
    dependsOn(jarAndroid)

    archiveFileName.set("${project.name}.jar")

    from({
        listOf(
            zipTree("${layout.buildDirectory.get()}/libs/${project.name}Desktop.jar"),
            zipTree("${layout.buildDirectory.get()}/libs/${project.name}Android.jar")
        )
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    doLast {
        delete("${layout.buildDirectory.get()}/libs/${project.name}Android.jar")
    }
}
