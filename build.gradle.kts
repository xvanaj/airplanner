plugins {
    id("java")
    id("application")
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Command line front end
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Web front end
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

/** ./gradlew run  -> the CLI. */
application {
    mainClass = "org.example.airplanner.AirPlanner"
    applicationName = "airplanner"
}

/**
 * ./gradlew bootRun -> the web UI on http://localhost:8080.
 * Both entry points have to be named explicitly: with the application plugin present,
 * Spring Boot would otherwise take the CLI's main class for bootRun and bootJar.
 */
val webMainClass = "org.example.airplanner.web.WebApplication"

springBoot {
    mainClass = webMainClass
}

tasks.bootRun {
    mainClass = webMainClass
}

tasks.bootJar {
    mainClass = webMainClass
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    systemProperty("file.encoding", "UTF-8")
}
