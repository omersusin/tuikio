plugins {
    id("tuikio.kotlin-library")
}

dependencies {
    api(project(":core:terminal"))
    api(project(":core:catalog"))
}
