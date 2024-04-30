val ktorVersion = "2.3.10"
val jsoupVersion = "1.17.2"
val gsonVersion = "2.10.1"
val angusMailVersion = "2.0.3"

plugins {
    kotlin("jvm") version "1.9.23"
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
    implementation("com.google.code.gson:gson:$gsonVersion")
    implementation("org.eclipse.angus:angus-mail:$angusMailVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}