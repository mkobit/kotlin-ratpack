import com.jfrog.bintray.gradle.BintrayExtension
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.jetbrains.dokka.gradle.DokkaTask

buildscript {
  repositories {
    jcenter()
    mavenCentral()
  }
  dependencies {
    classpath("org.junit.platform:junit-platform-gradle-plugin:1.0.0-M3")
    classpath("org.jetbrains.dokka:dokka-gradle-plugin:0.9.13")
  }
}

plugins {
  id("com.gradle.build-scan") version "1.6"
  id("org.jetbrains.kotlin.jvm") version "1.1.1" apply false
  id("com.github.ben-manes.versions") version "0.14.0"
  id("com.jfrog.bintray") version "1.7.3" apply false
}

allprojects {
  group = "com.mkobit.ratpack"
  version = "0.1.0"

  repositories {
    jcenter()
  }
}

fun env(key: String): String? = System.getenv(key)

buildScan {
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
junitPlatformVersion = "1.0.0-M3"
var junitJupiterVersion: String by extra
junitJupiterVersion = "5.0.0-M3"
var log4jVersion: String by extra
log4jVersion = "2.8.1"
var kotlinVersion: String by extra
kotlinVersion = "1.1.1"
var ratpackVersion: String by extra
ratpackVersion = "1.4.5"

fun ratpackModule(artifactName: String): Any = "io.ratpack:ratpack-$artifactName:$ratpackVersion"

val publishedProjects: Set<String> = setOf(
    "ratpack-core-kotlin",
    "ratpack-test-kotlin",
    "ratpack-guice-kotlin"
)

subprojects {
  apply {
    plugin("org.jetbrains.kotlin.jvm")
    plugin("java-library")
    plugin("maven-publish")
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

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
    testRuntimeOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
    testRuntimeOnly("org.apache.logging.log4j:log4j-jul:$log4jVersion")
  }


  tasks {
    "jar"(Jar::class) {
      manifest {
        attributes(mapOf(
            "Implementation-Version" to version
        ))
      }
    }

    val dokkaJavadoc = "dokkaJavadoc"(DokkaTask::class) {
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
  }


  this.let { subproject ->
    if (subproject.name in publishedProjects) {
      subproject.logger.lifecycle("Applying Bintray publishing configuration to ${subproject.path}")
      configure<BintrayExtension> {
        user = project.findProperty("bintrayUser") as String?
        key = project.findProperty("bintrayKey") as String?
        pkg(closureOf<BintrayExtension.PackageConfig> {
          setLicenses("Apache-2.0")
          repo = subproject.name
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

afterEvaluate {
  subprojects.forEach { subproject ->
    val gitignore: File = subproject.projectDir.resolve(".gitignore")

    if (!gitignore.isFile) {
      throw GradleException("Subproject ${subproject.name} must have a .gitignore file")
    }
    val ignoredBuild = gitignore.readLines(Charsets.UTF_8).filter { it.contentEquals("build/") }

    if (ignoredBuild.isEmpty()) {
      throw GradleException("Subproject ${subproject.name} does not contain an entry 'build/'")
    }
  }
}
