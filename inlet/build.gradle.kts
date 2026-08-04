plugins {
    id("java")
    id("application")
    id("org.graalvm.buildtools.native") version "0.10.2"
}

application {
    mainClass = "me.mrnavastar.ramjet.Inlet"
}

graalvmNative {
    toolchainDetection = true
    binaries {
        all {
            buildArgs.add("-Os") // Optimize for size instead of speed
            //buildArgs.add("--no-fallback")
            buildArgs.add("--static")
            buildArgs.add("--libc=musl")
            buildArgs.add("-march=compatibility")
        }
    }
}

dependencies {
    implementation(project(path = ":common", configuration = "default"))

    implementation("net.java.dev.jna:jna:4.5.0")

    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.1")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.22.0")
}
