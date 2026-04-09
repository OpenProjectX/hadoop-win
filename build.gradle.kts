import net.researchgate.release.ReleaseExtension
import groovy.lang.Closure
import java.util.regex.Matcher

plugins {
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0" // nexus publish/close/release
    id("net.researchgate.release") version "3.1.0"

}

val clouderaHadoopVersion = providers.gradleProperty("clouderaHadoopVersion")
    .orElse("3.1.1.7.1.9.14-2")
val releaseBranch = providers.gradleProperty("releaseBranch").orNull

val snapshotVersionRegex = Regex(
    """^\d+\.\d+\.\d+-[A-Za-z0-9.-]+(?:-SNAPSHOT)?$"""
)

require(snapshotVersionRegex.matches(version.toString())) {
    "Project version '${version}' must match '<major>.<minor>.<patch>-<clouderaVersion>' " +
            "optionally followed by '-SNAPSHOT'."
}

allprojects {
    group = "org.openprojectx.hadoop.win"
    extra["clouderaHadoopVersion"] = clouderaHadoopVersion.get()
}


subprojects {

    repositories {
        mavenCentral()
        maven(url = "https://repository.cloudera.com/repository/cloudera-repos/")
    }

    tasks.register<DependencyReportTask>("allDependencies") {}

    // Apply to every module (safe even if a module doesn't publish)
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    // Configure publishing only when the project has a Java component (Kotlin/JVM typically applies java too)
    plugins.withId("java") {

        // ✅ Ensure required artifacts exist for Maven Central
        extensions.configure<JavaPluginExtension>("java") {
            withSourcesJar()
            withJavadocJar()
        }

        // Kotlin-only modules can produce "empty-ish" Javadoc; don't fail the build on doclint/errors
        tasks.withType(Javadoc::class.java).configureEach {
            isFailOnError = false
        }


        extensions.configure<PublishingExtension>("publishing") {
            publications {
                // Create once per project
                if (findByName("mavenJava") == null) {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])

                        // Prefer explicit artifactId; by default it's project.name
                        artifactId = project.name

                        pom {
                            // Module-specific name/description; override per-module if you want
                            name.set(project.name)
                            description.set("Hadoop for windows")
                            url.set("https://github.com/OpenProjectX/hadoop-win")

                            licenses {
                                license {
                                    name.set("Apache License 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                                }
                            }

                            developers {
                                developer {
                                    id.set("OpenProjectX")
                                    name.set("OpenProjectX")
                                    email.set("admin@openprojectx.org")
                                }
                            }

                            scm {
                                url.set("https://github.com/OpenProjectX/hadoop-win")
                                connection.set("scm:git:https://github.com/OpenProjectX/hadoop-win.git")
                                developerConnection.set("scm:git:ssh://git@github.com:OpenProjectX/hadoop-win.git")
                            }
                        }
                    }
                }
            }
        }
    }

    // Signing: only configure keys if provided (keeps local dev painless)
    extensions.configure<SigningExtension>("signing") {
        val keyFile = System.getenv("SIGNING_KEY_FILE")
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")

        if (!keyFile.isNullOrBlank()) {
            val keyText = file(keyFile).readText()
            useInMemoryPgpKeys(keyText, keyPass)

            // Sign all publications created in this subproject
            val publishing = extensions.findByType(PublishingExtension::class.java)
            if (publishing != null) {
                sign(publishing.publications)
            }
        }
    }
}


nexusPublishing {

    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("OSSRH_USERNAME"))
            logger.info("using username: ${System.getenv("OSSRH_USERNAME")}")

            password.set(System.getenv("OSSRH_PASSWORD"))
            logger.info("using password: ${System.getenv("OSSRH_PASSWORD")}")

        }
    }
}

configure<ReleaseExtension> {
    buildTasks.set(listOf("publishToSonatype", "closeAndReleaseSonatypeStagingRepository"))
    versionPropertyFile.set("gradle.properties")
    tagTemplate.set("\$name-\$version")
    snapshotSuffix.set("-SNAPSHOT")
    versionPatterns = mapOf(
        """(\d+)\.(\d+)\.(\d+)-([A-Za-z0-9.-]+)$""" to
                object : Closure<String>(project, project) {
                    @Suppress("unused")
                    fun doCall(matcher: Matcher, currentVersion: Any?): String {
                        val major = matcher.group(1).toInt()
                        val minor = matcher.group(2).toInt()
                        val patch = matcher.group(3).toInt() + 1
                        val versionSuffix = matcher.group(4)
                        return "$major.$minor.$patch-$versionSuffix"
                    }
                }
    )

    with(git) {
        if (!releaseBranch.isNullOrBlank()) {
            requireBranch.set(releaseBranch)
        }
    }
}
