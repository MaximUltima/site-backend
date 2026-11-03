plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.allopen") version "2.4.0"
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-kotlin")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-smallrye-openapi")

    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // /q/health — для healthcheck в docker-compose
    implementation("io.quarkus:quarkus-smallrye-health")

    implementation("io.quarkus:quarkus-hibernate-reactive-panache-kotlin")
    implementation("io.quarkus:quarkus-reactive-pg-client")
    implementation("io.quarkus:quarkus-smallrye-context-propagation")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("io.quarkus:quarkus-arc")

    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")

    // Liquibase specific dependencies
    implementation("io.quarkus:quarkus-liquibase")

    // JDBC driver dependencies
    implementation("io.quarkus:quarkus-jdbc-postgresql")
}

group = "com.code5150"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        javaParameters = true
    }
}
