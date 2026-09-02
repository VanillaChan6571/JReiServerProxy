plugins {
    kotlin("jvm") version "2.4.10"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "com.xinian.jreiproxyserver"
// Tracks the Minecraft version this is built against: the first two components are Mojang's,
// the third is the plugin revision for that version. The jar only loads on the matching server.
version = "26.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    // Mojang-mapped Paper server. The recipe payloads JEI and REI read are NMS-encoded, so this
    // plugin needs the server internals, not just the Bukkit API.
    paperweight.paperDevBundle("26.2.build.121-stable")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}

// Minecraft 26.x class files are Java 25.
val targetJavaVersion = 25

kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("26.2")
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
