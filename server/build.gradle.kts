plugins {
    id("java")
    id("application")
    id("org.graalvm.buildtools.native") version "0.10.2"
}

application {
    mainClass = "me.mrnavastar.ramjet.Server"
}

graalvmNative {
    toolchainDetection = true
    binaries {
        all {
            // Packages/classes to be initialized at native image run time
            val runTimeInitClasses = listOf(
                "org.eclipse.jgit.internal.storage.file.WindowCache",
                "org.eclipse.jgit.lib.internal.WorkQueue",
                "org.eclipse.jgit.lib.RepositoryCache",
                "org.eclipse.jgit.transport.HttpAuthMethod",
            )

            // Packages/classes to be re-initialized at native image run time
            // All org.eclipse.jgit classes are initialized at build time
            // (specified above), but due to SecureRandom seeding
            // in their static initialization blocks, some JGit classes
            // need to be re-initialized at native image run time.
            val runTimeReInitClasses = listOf(
                "org.eclipse.jgit.util.FileUtils:rerun",
            )

            buildArgs.add("--enable-url-protocols=http,https,file,tarToCpio")
            buildArgs.add("--initialize-at-run-time=${runTimeInitClasses.joinToString(",")}")
            buildArgs.add("-H:ClassInitialization=${runTimeReInitClasses.joinToString(",")}")

            buildArgs.add("-Os") // Optimize for size instead of speed
            //buildArgs.add("--no-fallback")
            buildArgs.add("--static")
            buildArgs.add("--libc=musl")
            buildArgs.add("-march=compatibility")

            buildArgs.add("-H:-ReduceImplicitExceptionStackTraceInformation")
        }
    }


}

dependencies {
    implementation(project(path = ":common", configuration = "default"))

    implementation("io.javalin:javalin:7.2.2")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("commons-codec:commons-codec:1.22.0")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.1")

    implementation("land.oras:oras-java-sdk:0.8.2")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.22.0")

    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r")

    implementation("party.iroiro.luajava:luajava:4.1.0")
    implementation("party.iroiro.luajava:luaj:4.1.0")
    //implementation ("party.iroiro.luajava:lua55:4.1.0")
    //implementation("party.iroiro.luajava:lua55-platform:4.1.0:natives-desktop")
}
