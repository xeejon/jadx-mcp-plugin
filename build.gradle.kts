import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Locale

plugins {
    `java-library`

    id("com.github.johnrengelman.shadow") version "8.1.1"

	// auto update dependencies with 'useLatestVersions' task
	id("se.patrikerdes.use-latest-versions") version "0.2.18"
	id("com.github.ben-manes.versions") version "0.52.0"
}


dependencies {
	val jadxVersion = "1.5.3"
	val isJadxSnapshot = jadxVersion.endsWith("-SNAPSHOT")

	// use compile only scope to exclude jadx-core and its dependencies from result jar
	compileOnly("io.github.skylot:jadx-core:$jadxVersion") {
        isChanging = isJadxSnapshot
    }
	compileOnly("io.github.skylot:jadx-gui:$jadxVersion") {
        isChanging = isJadxSnapshot
    }

	testImplementation("io.github.skylot:jadx-core:$jadxVersion") {
		isChanging = isJadxSnapshot
	}
	testImplementation("io.github.skylot:jadx-gui:$jadxVersion") {
		isChanging = isJadxSnapshot
	}
	testImplementation("io.github.skylot:jadx-smali-input:$jadxVersion") {
        isChanging = isJadxSnapshot
    }
	testImplementation("ch.qos.logback:logback-classic:1.5.18")
	testImplementation("org.assertj:assertj-core:3.27.3")
	testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")



	implementation("ch.qos.logback:logback-classic:1.5.18")

    	// MCP Server dependencies
	implementation("io.javalin:javalin:6.7.0")
	implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
	implementation("io.github.oshai:kotlin-logging-jvm:7.0.13")

	implementation("com.fifesoft:rsyntaxtextarea:3.6.0")
	implementation("org.drjekyll:fontchooser:3.1.0")
	implementation("hu.kazocsaba:image-viewer:1.2.3")
	implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0") // WebP support for image viewer

	implementation("com.formdev:flatlaf:3.6.1")
	implementation("com.formdev:flatlaf-intellij-themes:3.6.1")
	implementation("com.formdev:flatlaf-extras:3.6.1")

	implementation("com.google.code.gson:gson:2.13.2")
	implementation("org.apache.commons:commons-lang3:3.18.0")
	implementation("org.apache.commons:commons-text:1.14.0")
	implementation("commons-io:commons-io:2.20.0")

	implementation("io.reactivex.rxjava3:rxjava:3.1.11")
	implementation("com.github.akarnokd:rxjava3-swing:3.1.1")
	implementation("com.android.tools.build:apksig:8.13.0")
	implementation("io.github.skylot:jdwp:2.0.0")


}



repositories {
	mavenLocal()
    mavenCentral()
    maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    google()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}



version = findProperty("version")?.toString() ?: System.getenv("VERSION") ?: "dev"

tasks {
    withType(Test::class) {
//        useJUnitPlatform()
		enabled=false
    }

    val shadowJar = withType(ShadowJar::class) {
        archiveClassifier.set("") // remove '-all' suffix
    }

    // copy result jar into "build/dist" directory
    register<Copy>("dist") {
		group = "jadx-plugin"
        dependsOn(shadowJar)
        dependsOn(withType(Jar::class))

        from(shadowJar)
        into(layout.buildDirectory.dir("dist"))
    }
}

tasks.named("build") {
	dependsOn("shadowJar")
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
	rejectVersionIf {
		// disallow release candidates as upgradable versions from stable versions
		isNonStable(candidate.version) && !isNonStable(currentVersion)
	}
}

fun isNonStable(version: String): Boolean {
	val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase(Locale.getDefault()).contains(it) }
	val regex = "^[0-9,.v-]+(-r)?$".toRegex()
	val isStable = stableKeyword || regex.matches(version)
	return isStable.not()
}
