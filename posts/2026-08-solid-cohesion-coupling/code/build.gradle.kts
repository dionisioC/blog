plugins {
    kotlin("jvm") version "2.4.20"
    application
    id("dev.detekt") version "2.0.0-alpha.6"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("dev.dionisioc.checkout.app.MainKt")
}

detekt {
    // Start from detekt's recommended rules; generate `detekt.yml` with
    // `gradle detektGenerateConfig` and point `config.setFrom(files("detekt.yml"))` here to tune.
    buildUponDefaultConfig = true
    parallel = true
}

tasks.test {
    useJUnitPlatform()
}
