import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
    id("io.gitlab.arturbosch.detekt")
}

dependencies {
    detektPlugins("io.nlopez.compose.rules:detekt:${project.property("DETEKT_COMPOSE_RULES_VERSION")}")
}

tasks {
    register("detektAll", Detekt::class) {
        parallel = true
        setSource(files(projectDir))
        include("**/*.kt")
        include("**/*.kts")
        exclude("**/resources/**")
        exclude("**/build/**")
        // Generated output, gitignored exactly like build/, but produced in the Eclipse layout by
        // an IDE-driven sync. CI never has it, so findings in it are invisible on CI and drown the
        // real ones locally — which is how four genuine issues reached CI unseen.
        exclude("**/bin/**")
        config.setFrom(files("${rootProject.projectDir}/tools/detekt.yml"))
        baseline.set(File("${rootProject.projectDir}/tools/detekt-baseline.xml"))
        buildUponDefaultConfig = true
    }

    register("detektGenerateBaseline", DetektCreateBaselineTask::class) {
        description = "Overrides current baseline."
        buildUponDefaultConfig.set(true)
        ignoreFailures.set(true)
        parallel.set(true)
        setSource(files(rootDir))
        config.setFrom(files("${rootProject.projectDir}/tools/detekt.yml"))
        baseline.set(file("$rootDir/tools/detekt-baseline.xml"))
        include("**/*.kt")
        include("**/*.kts")
        exclude("**/resources/**")
        exclude("**/build/**")
        exclude("**/bin/**")
    }
}
