plugins {
    id("java")
}

subprojects {
    apply(plugin = "java")

    group = "me.mrnavastar"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    dependencies {
        compileOnly("org.projectlombok:lombok:1.18.46")
        annotationProcessor("org.projectlombok:lombok:1.18.46")
    }
}
