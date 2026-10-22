plugins {
    id("tuikio.kotlin-library")
}

// Parse-throughput benchmark (docs/PERFORMANCE.md); not part of the test run.
tasks.register<JavaExec>("bench") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.tuikio.terminal.TerminalBench")
    args(providers.gradleProperty("captures").getOrElse(""))
}
