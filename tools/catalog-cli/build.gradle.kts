plugins {
    id("tuikio.kotlin-library")
    application
}

dependencies {
    implementation(project(":core:catalog"))
    implementation(project(":core:controls"))
}

application {
    mainClass.set("dev.tuikio.catalogcli.MainKt")
}
