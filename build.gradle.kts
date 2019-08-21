import de.marcphilipp.gradle.nexus.NexusPublishExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

val githubProjectName = "jfix-stdlib"


buildscript {
    repositories {
        jcenter()
        mavenCentral()
    }
    dependencies {
        classpath(Libs.gradleReleasePlugin)
        classpath(Libs.dokkaGradlePlugin)
        classpath(Libs.kotlin_stdlib)
        classpath(Libs.kotlin_jdk8)
        classpath(Libs.kotlin_reflect)
        classpath(Libs.jmh_gradle_plugin)
    }
}


/**
 * Project configuration by properties and environment
 */
fun envConfig() = object : ReadOnlyProperty<Any?, String?> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): String? =
            if (ext.has(property.name)) {
                ext[property.name] as? String
            } else {
                System.getenv(property.name)
            }
}

val repositoryUser by envConfig()
val repositoryPassword by envConfig()
val repositoryUrl by envConfig()
val signingKeyId by envConfig()
val signingPassword by envConfig()
val signingSecretKeyRingFile by envConfig()


plugins {
    kotlin("jvm") version Vers.kotlin apply false
    signing
    `maven-publish`
    id(Libs.nexus_publish_plugin) version "0.3.0" apply false
    id(Libs.nexus_staging_plugin) version "0.21.0"
}

nexusStaging {
    packageGroup = "ru.fix"
    stagingProfileId = "1f0730098fd259"
    username = "$repositoryUser"
    password = "$repositoryPassword"
    numberOfRetries = 50
    delayBetweenRetriesInMillis = 3_000
}

apply {
    plugin("ru.fix.gradle.release")
}

subprojects {
    group = "ru.fix"

    apply {
        plugin("maven-publish")
        plugin("signing")
        plugin("java")
        plugin("org.jetbrains.dokka")
        plugin(Libs.nexus_publish_plugin)
    }

    repositories {
        jcenter()
        mavenCentral()
    }

    val sourcesJar by tasks.creating(Jar::class) {
        classifier = "sources"
        from("src/main/java")
        from("src/main/kotlin")
    }

    val dokkaTask by tasks.creating(DokkaTask::class) {
        outputFormat = "javadoc"
        outputDirectory = "$buildDir/dokka"
    }

    val dokkaJar by tasks.creating(Jar::class) {
        classifier = "javadoc"

        from(dokkaTask.outputDirectory)
        dependsOn(dokkaTask)
    }

    configure<NexusPublishExtension>{
        repositories{
            sonatype{
                username.set("$repositoryUser")
                password.set("$repositoryPassword")
                useStaging.set(true)
                stagingProfileId.set("1f0730098fd259")
            }
        }
    }

    project.afterEvaluate {
        publishing {

            publications {
                //Internal repository setup
                repositories {
                    maven {
                        url = uri("$repositoryUrl")
                        if (url.scheme.startsWith("http", true)) {
                            credentials {
                                username = "$repositoryUser"
                                password = "$repositoryPassword"
                            }
                        }
                    }
                }

                create<MavenPublication>("maven"){
                    from(components["java"])

                    artifact(sourcesJar)
                    artifact(dokkaJar)

                    pom {
                        name.set("${project.group}:${project.name}")
                        description.set("https://github.com/ru-fix/")
                        url.set("https://github.com/ru-fix/$githubProjectName")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers {
                        }
                        scm {
                            url.set("https://github.com/ru-fix/$githubProjectName")
                            connection.set("https://github.com/ru-fix/$githubProjectName.git")
                            developerConnection.set("https://github.com/ru-fix/$githubProjectName.git")
                        }
                    }
                }
            }//publications
        }//publishing
    }//afterEvaluate

    configure<SigningExtension> {
        if (!signingKeyId.isNullOrEmpty()) {
            project.ext["signing.keyId"] = signingKeyId
            project.ext["signing.password"] = signingPassword
            project.ext["signing.secretKeyRingFile"] = signingSecretKeyRingFile
            logger.info("Signing key id provided. Sign artifacts for $project.")
            isRequired = true
        } else {
            logger.info("${project.name}: Signing key not provided. Disable signing for  $project.")
            isRequired = false
        }
        sign(publishing.publications)
    }

    tasks {
        withType<KotlinCompile> {
            kotlinOptions.jvmTarget = "1.8"
        }
        withType<Test> {
            useJUnitPlatform()

            maxParallelForks = 10

            testLogging {
                events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
                showStandardStreams = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}
