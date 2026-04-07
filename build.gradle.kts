plugins {
    java
    application
}

group = "mediaPipeline"
version = "1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "mediaPipeline.PipelineRunner"
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.slf4j:slf4j-api:2.0.13")
}

repositories {
    mavenCentral()
}