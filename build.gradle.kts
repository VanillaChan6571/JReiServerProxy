plugins {
    kotlin("jvm") version "2.4.10"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "gg.nekohosting.vanilla"

/**
 * One build target per Minecraft version.
 *
 * A single jar cannot cover several versions: the plugin encodes recipes through the server's own
 * classes, and those change shape between releases. RecipeSerializer, for instance, is a class on
 * 26.2 and an interface on 1.21.11 -- the same source compiles against both, then dies with an
 * IncompatibleClassChangeError on the wrong one. So each version gets its own jar.
 *
 * Pick one with -Pminecraft=1.21.11; the default is the newest.
 */
data class Target(val devBundle: String, val apiVersion: String, val pluginVersion: String)

val targets = mapOf(
    "26.2" to Target("26.2.build.121-stable", "26.2", "26.2.0"),
    // Folia's stable line, so this is the build Folia servers want until 26.2 leaves beta.
    "1.21.11" to Target("1.21.11-R0.1-SNAPSHOT", "1.21", "1.21.11.2"),
)

val minecraftVersion = providers.gradleProperty("minecraft").getOrElse("26.2")
val target = targets[minecraftVersion]
    ?: error("Unknown -Pminecraft=$minecraftVersion. Known targets: ${targets.keys.joinToString()}")

// Tracks the Minecraft version this is built against: Mojang's version, then the plugin revision
// for it. The jar only loads on the matching server, so the version is also the compatibility
// statement.
version = target.pluginVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    // Mojang-mapped Paper server. The recipe payloads JEI and REI read are NMS-encoded, so this
    // plugin needs the server internals, not just the Bukkit API.
    paperweight.paperDevBundle(target.devBundle)
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Minecraft 26.x class files are Java 25; 1.21.x needs only 21, and building it on 25 would emit
// class files its server cannot read.
val targetJavaVersion = if (minecraftVersion.startsWith("26.")) 25 else 21

kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(minecraftVersion)
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        val props = mapOf("version" to version, "apiVersion" to target.apiVersion)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
