import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.CustomChart
import jetbrains.buildServer.configs.kotlin.CustomChart.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.dockerSupport
import jetbrains.buildServer.configs.kotlin.buildFeatures.parallelTests
import jetbrains.buildServer.configs.kotlin.buildSteps.dockerCommand
import jetbrains.buildServer.configs.kotlin.buildSteps.gradle
import jetbrains.buildServer.configs.kotlin.buildSteps.nodeJS
import jetbrains.buildServer.configs.kotlin.buildSteps.qodana
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.projectCustomChart
import jetbrains.buildServer.configs.kotlin.projectFeatures.dockerRegistry
import jetbrains.buildServer.configs.kotlin.triggers.VcsTrigger
import jetbrains.buildServer.configs.kotlin.triggers.finishBuildTrigger
import jetbrains.buildServer.configs.kotlin.triggers.vcs

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2024.03"

project {

    buildType(BuildBackend)
    buildType(RunBackendTests)
    buildType(BuildBackendDockerImage)
    buildType(BuildFrontendDockerImage)
    buildType(Deploy)
    buildType(AggregatingBuild)
    buildType(BuildFrontend)

    params {
        param("teamcity.internal.feature.build.cache.enabled", "true")
    }

    features {
        dockerRegistry {
            id = "PROJECT_EXT_3"
            name = "Docker Registry"
            userName = "yegnau"
            password = "credentialsJSON:9f86b288-7177-45cc-b2bc-1a1e77a44383"
        }
        projectCustomChart {
            id = "PROJECT_EXT_4"
            title = "Passed Tests"
            seriesTitle = "Serie"
            format = CustomChart.Format.TEXT
            series = listOf(
                Serie(title = "Number of Failed Tests", key = SeriesKey.FAILED_TESTS, sourceBuildTypeId = "AlarmDemo2_BuildBackend"),
                Serie(title = "Number of Passed Tests", key = SeriesKey.PASSED_TESTS, sourceBuildTypeId = "AlarmDemo2_BuildBackend")
            )
        }
    }
    buildTypesOrder = arrayListOf(RunBackendTests, BuildBackend, BuildBackendDockerImage, BuildFrontend, BuildFrontendDockerImage, Deploy, AggregatingBuild)
}

object AggregatingBuild : BuildType({
    name = "Aggregating build"

    type = BuildTypeSettings.Type.COMPOSITE

    vcs {
        showDependenciesChanges = true
    }

    triggers {
        vcs {
            enabled = false
        }
        finishBuildTrigger {
            buildType = "${Deploy.id}"
            successfulOnly = true
        }
    }

    dependencies {
        snapshot(Deploy) {
            reuseBuilds = ReuseBuilds.NO
        }
    }
})

object BuildBackend : BuildType({
    name = "Build Backend"

    artifactRules = "build/libs/*"

    vcs {
        root(DslContext.settingsRoot, "+:alarm-management => .")
    }

    steps {
        gradle {
            tasks = "build"
            gradleParams = "-x test"
        }
        qodana {
            enabled = false
            linter = jvm {
            }
            additionalDockerArguments = """-e QODANA_REVISION="%build.vcs.number%" -e QODANA_REMOTE_URL="%vcsroot.url%" -e QODANA_BRANCH="%vcsroot.branch%%""""
            cloudToken = "credentialsJSON:98b80096-d5bb-41ca-9000-06f287466a77"
        }
    }

    triggers {
        vcs {
            enabled = false
        }
    }

    dependencies {
        snapshot(RunBackendTests) {
            onDependencyFailure = FailureAction.CANCEL
        }
    }
})

object BuildBackendDockerImage : BuildType({
    name = "Build Backend Docker Image"

    vcs {
        root(DslContext.settingsRoot, "+:alarm-management => .")
    }

    steps {
        dockerCommand {
            name = "Docker Build"
            commandType = build {
                source = file {
                    path = "Dockerfile"
                }
                namesAndTags = "yegnau/alarm-backend:%build.number%"
            }
        }
        dockerCommand {
            commandType = push {
                namesAndTags = "yegnau/alarm-backend:%build.number%"
            }
        }
    }

    features {
        dockerSupport {
            cleanupPushedImages = true
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_3"
            }
        }
    }

    dependencies {
        dependency(BuildBackend) {
            snapshot {
            }

            artifacts {
                artifactRules = """
                    alarm-management-0.0.1-SNAPSHOT.jar => build/libs/
                """.trimIndent()
            }
        }
    }
})

object BuildFrontend : BuildType({
    name = "Build Frontend"

    artifactRules = "alarm-frontend/build/**/* => build-frontend.zip"

    vcs {
        root(DslContext.settingsRoot, "+:alarm-frontend => .")
    }

    steps {
        nodeJS {
            workingDir = "alarm-frontend"
            shellScript = """
                export CYPRESS_CACHE_FOLDER=/app-alarm/.cache
                mkdir -p /app-alarm/.cache
                chmod -R 777 /app-alarm
                chown -R node:node /app-alarm
                npm ci
                npm run build
            """.trimIndent()
        }
        script {
            scriptContent = "ls -al"
        }
    }
})

object BuildFrontendDockerImage : BuildType({
    name = "Build Frontend Docker Image"

    vcs {
        root(DslContext.settingsRoot, "+:alarm-frontend => .")
    }

    steps {
        dockerCommand {
            commandType = build {
                source = file {
                    path = "Dockerfile"
                }
                namesAndTags = "yegnau/alarm-frontend:%build.number%-koniakin"
            }
        }
        dockerCommand {
            commandType = push {
                namesAndTags = "yegnau/alarm-frontend:%build.number%-koniakin"
            }
        }
    }

    features {
        dockerSupport {
            loginToRegistry = on {
                dockerRegistryId = "PROJECT_EXT_3"
            }
        }
    }

    dependencies {
        snapshot(BuildFrontend) {
        }
    }
})

object Deploy : BuildType({
    name = "Deploy"

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        script {
            scriptContent = "echo 'deploying'"
        }
    }

    triggers {
        vcs {
            quietPeriodMode = VcsTrigger.QuietPeriodMode.USE_DEFAULT
            perCheckinTriggering = true
            groupCheckinsByCommitter = true
            enableQueueOptimization = false
        }
    }

    dependencies {
        snapshot(BuildBackendDockerImage) {
        }
        snapshot(BuildFrontendDockerImage) {
        }
    }
})

object RunBackendTests : BuildType({
    name = "Run Backend Tests"

    artifactRules = "build/libs/*"

    vcs {
        root(DslContext.settingsRoot, "+:alarm-management => .")
    }

    steps {
        gradle {
            tasks = "test"
            buildFile = "build.gradle"
            dockerImage = "openjdk:latest"
        }
    }

    triggers {
        vcs {
            enabled = false
        }
    }

    features {
        parallelTests {
            numberOfBatches = 3
        }
    }
})
