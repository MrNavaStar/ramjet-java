plugins {
    id("java")
    id("application")
    id("org.graalvm.buildtools.native") version "0.10.2"
}


application {
    mainClass = "me.mrnavastar.Main"
}

dependencies {
    implementation(project(path = ":common", configuration = "default"))

    implementation("io.javalin:javalin:7.2.2")

    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    implementation("party.iroiro.luajava:luajava:4.1.0")
    implementation ("party.iroiro.luajava:lua55:4.1.0")
    runtimeOnly("party.iroiro.luajava:lua55-platform:4.1.0:natives-desktop")
}
