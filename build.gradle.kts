import com.jfrog.bintray.gradle.BintrayExtension
import org.gradle.api.artifacts.Dependency
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.jvm.tasks.Jar
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.platform.gradle.plugin.EnginesExtension
import org.junit.platform.gradle.plugin.FiltersExtension
import org.junit.platform.gradle.plugin.JUnitPlatformExtension
import java.io.ByteArrayOutputStream

buildscript {
  repositories {
    jcenter()
    mavenCentral()
  }
  dependencies {
    classpath("org.junit.platform:junit-platform-gradle-plugin:1.0.0-M4")
    classpath("org.jetbrains.dokka:dokka-gradle-plugin:0.9.13")
  }
}

plugins {
  id("com.gradle.build-scan") version "1.6"
  id("org.jetbrains.kotlin.jvm") version "1.1.1" apply false
  id("com.github.ben-manes.versions") version "0.14.0"
  id("com.jfrog.bintray") version "1.7.3" apply false
  id("io.ratpack.ratpack-java") apply false
}

applyFrom("gradle/gitignore.gradle.kts")

allprojects {
  group = "com.mkobit.ratpack"
  version = "0.1.0"

  repositories {
    jcenter()
  }
}

buildScan {
  fun env(key: String): String? = System.getenv(key)

  setLicenseAgree("yes")
  setLicenseAgreementUrl("https://gradle.com/terms-of-service")

  // Env variables from https://circleci.com/docs/2.0/env-vars/
  if (env("CI") != null) {
    logger.lifecycle("Running in CI environment, setting build scan attributes.")
    tag("CI")
    env("CIRCLE_BRANCH")?.let { tag(it) }
    env("CIRCLE_BUILD_NUM")?.let { value("Circle CI Build Number", it) }
    env("CIRCLE_BUILD_URL")?.let { link("Build URL", it) }
    env("CIRCLE_SHA1")?.let { value("Revision", it) }
    env("CIRCLE_COMPARE_URL")?.let { link("Diff", it) }
    env("CIRCLE_REPOSITORY_URL")?.let { value("Repository", it) }
    env("CIRCLE_PR_NUMBER")?.let { value("Pull Request Number", it) }
  }
}

var junitPlatformVersion: String by extra
junitPlatformVersion = "1.0.0-M4"
var junitJupiterVersion: String by extra
junitJupiterVersion = "5.0.0-M4"
var log4jVersion: String by extra
log4jVersion = "2.8.1"
var kotlinVersion: String by extra
kotlinVersion = "1.1.1"

fun ratpackModule(artifactName: String): Dependency {
  val ratpackVersion: String = project.property("ratpackVersion") as String
  return dependencies.create("io.ratpack:ratpack-$artifactName:$ratpackVersion")
}

tasks {
  "wrapper"(Wrapper::class) {
    gradleVersion = "3.5-rc-2"
  }
}

val publishedProjects: Set<String> = setOf(
    "ratpack-core-kotlin",
    "ratpack-test-kotlin",
    "ratpack-guice-kotlin"
)

val revision: String by lazy {
  val stream = ByteArrayOutputStream()
  exec {
    commandLine("git", "log", "--format=%H", "-n", "1", "HEAD")
    workingDir(rootDir)
    standardOutput = stream
  }.rethrowFailure().assertNormalExitValue()
  stream.toByteArray().toString(Charsets.UTF_8).trim()
}

subprojects {
  apply {
    plugin("org.jetbrains.kotlin.jvm")
    plugin("java-library")
    plugin("org.junit.platform.gradle.plugin")
    plugin("com.jfrog.bintray")
    plugin("org.jetbrains.dokka")
  }

  convention.getPlugin(JavaPluginConvention::class.java).apply {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  dependencies {
    "api"(kotlinModule("stdlib-jre8", kotlinVersion))

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation(ratpackModule("test"))
    testImplementation("com.google.truth:truth:0.32")
    testImplementation(kotlinModule("reflect", kotlinVersion))
    testImplementation("com.nhaarman:mockito-kotlin:1.3.0")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testRuntimeOnly("org.apache.logging.log4j:log4j-jul:$log4jVersion")
  }

  configure<JUnitPlatformExtension> {
    platformVersion = junitPlatformVersion
    filters {
      engines {
        include("junit-platform")
      }
    }
  }


  tasks {
    val dokkaJavadoc: DokkaTask by creating(DokkaTask::class) {
      outputFormat = "javadoc"
      outputDirectory = "$buildDir/javadoc"
    }

    "dokkaJavadocJar"(Jar::class) {
      dependsOn(dokkaJavadoc)
      classifier = "javadoc"
      from(dokkaJavadoc.outputDirectory)
    }

    val mainSources = convention.getPlugin(JavaPluginConvention::class).sourceSets["main"]

    "sourcesJar"(Jar::class) {
      classifier = "sources"
      from(mainSources.allSource)
    }
    withType(Jar::class.java) {
      manifest {
        attributes(mapOf(
            "Implementation-Version" to version,
            "Build-Revision" to revision
        ))
      }
    }
    withType(KotlinCompile::class.java) {
      kotlinOptions.jvmTarget = "1.8"
    }
  }


  if (name in publishedProjects) {
    apply {
      plugin("maven-publish")
    }
    logger.lifecycle("Applying Bintray publishing configuration to ${this@subprojects.path}")
    configure<BintrayExtension> {
      user = project.findProperty("bintrayUser") as String?
      key = project.findProperty("bintrayKey") as String?
      pkg(closureOf<BintrayExtension.PackageConfig> {
        setLicenses("Apache-2.0")
        repo = this@subprojects.name
        issueTrackerUrl = "https://github.com/mkobit/ratpack-kotlin/issues"
        vcsUrl = "https://github.com/mkobit/ratpack-kotlin"
        setLabels("kotlin", "ratpack")
      })
    }

    configure<PublishingExtension> {
      publications.create<MavenPublication>("mavenJava") {
        from(components["java"])
        artifact(tasks["dokkaJavadocJar"])
        artifact(tasks["sourcesJar"])
      }
    }
  }
}

project(":ratpack-core-kotlin") {
  dependencies {
    "api"(ratpackModule("core"))
  }
}

val core = project(":ratpack-core-kotlin")

project(":ratpack-guice-kotlin") {
  dependencies {
    "api"(core)
    "api"(ratpackModule("guice"))
  }
}

project(":ratpack-test-kotlin") {
  dependencies {
    "api"(core)
    "api"(ratpackModule("test"))
  }
}

project(":ratpack-example-kotlin") {
  apply {
    plugin("io.ratpack.ratpack-java")
  }
  configure<ApplicationPluginConvention> {
    mainClassName = "com.mkobit.ratpack.example.Main"
  }
  dependencies {
    implementation(core)
    implementation(project(":ratpack-guice-kotlin"))
    testImplementation(project(":ratpack-test-kotlin"))
  }
}

fun JUnitPlatformExtension.filters(setup: FiltersExtension.() -> Unit) {
  when (this) {
    is ExtensionAware -> extensions.getByType(FiltersExtension::class.java).setup()
    else -> throw Exception("${this::class} must be an instance of ExtensionAware")
  }
}
fun FiltersExtension.engines(setup: EnginesExtension.() -> Unit) {
  when (this) {
    is ExtensionAware -> extensions.getByType(EnginesExtension::class.java).setup()
    else -> throw Exception("${this::class} must be an instance of ExtensionAware")
  }
}
