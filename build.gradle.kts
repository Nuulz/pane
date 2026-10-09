plugins {
    `java-library`
    `maven-publish`
}

group = "dev.nuulz"
version = "0.1.1"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

// java-cef (BSD, see jcef/LICENSE.txt) is compiled in as is.
sourceSets.main { java.srcDir("jcef") }

repositories {
    mavenCentral()
}

dependencies {
    // Whoever uses pane brings LWJGL (Minecraft and most GLFW apps already do).
    compileOnly("org.lwjgl:lwjgl:3.3.3")
    compileOnly("org.lwjgl:lwjgl-glfw:3.3.3")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-removal")
}

tasks.jar {
    from(files("LICENSE", "jcef/LICENSE.txt")) { into("META-INF/pane") }
}

tasks.test { useJUnitPlatform() }

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}
