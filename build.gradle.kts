plugins {
    id("java")
}

subprojects {
    apply(plugin = "java")

    group = "me.mrnavastar"
    version = "0.1.0"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.matching("GraalVM Community")
        }
    }

    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }

    dependencies {
        compileOnly("org.projectlombok:lombok:1.18.46")
        annotationProcessor("org.projectlombok:lombok:1.18.46")
    }
}
