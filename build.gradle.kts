val ktorVersion = "2.3.12"
val jsoupVersion = "1.17.2"

plugins {
    kotlin("jvm") version "2.0.0"
}

group = "fr.shikkanime"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp-jvm:$ktorVersion")
    implementation("org.jsoup:jsoup:$jsoupVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}