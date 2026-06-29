plugins {
    id("java")
}

dependencies {
    implementation("org.apache.httpcomponents.client5:httpclient5:5.6.1")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.22.0")
}
