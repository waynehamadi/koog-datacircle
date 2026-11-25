import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
    `java-library`
    `maven-publish`
}

group = "com.github.waynehamadi"
version = "0.1.0"

// The tools compile against the oldest Koog they support and are tested on the Koog given by -Pkoog.
val koogMin = "1.0.0"
val koogTest = providers.gradleProperty("koog").getOrElse("1.3.0")

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("ai.koog:agents-tools:$koogMin")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly(kotlin("stdlib"))

    testImplementation("ai.koog:koog-agents:$koogTest")
    testImplementation("ai.koog:agents-test:$koogTest")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
    environment("DATACIRCLE_LIVE", System.getenv("DATACIRCLE_LIVE") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "koog-datacircle"
            from(components["java"])
            pom {
                name.set("Datacircle tools for Koog")
                description.set(
                    "Four Koog tools on Datacircle's open API, with no key: every GTM API, who is best for each, " +
                        "plan search and a provider's ranks."
                )
                url.set("https://datacircle.dev")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/waynehamadi/koog-datacircle")
                }
            }
        }
    }
}
