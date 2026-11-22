plugins {
    java
}

group = "dev.nora"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// One jar runs on both Folia and Paper (Paper ships the same region schedulers).
// Build against Folia by default, or against Paper with:  ./gradlew build -Pplatform=paper
val platform = (findProperty("platform") as String?) ?: "folia"

dependencies {
    if (platform == "paper") {
        compileOnly("io.papermc.paper:paper-api:26.3.build.242-stable")
    } else {
        // Newest Folia API published so far is 26.2 (beta); it matches everything used here.
        // Switch to "26.3.build.<n>-..." once Folia publishes a 26.3 build.
        compileOnly("dev.folia:folia-api:26.2.build.4-beta")
    }
}

java {
    // Minecraft 26.x servers run on Java 25.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}
