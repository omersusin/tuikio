// Tuikio's submission server: JDK HttpServer, no third-party runtime dependencies.
plugins {
    id("tuikio.kotlin-library")
    application
}

dependencies {
    implementation(project(":core:community"))
}

application {
    mainClass.set("dev.tuikio.backend.MainKt")
}
