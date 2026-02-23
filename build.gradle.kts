plugins {
    java
    application
    id("com.google.protobuf") version "0.9.4"
}

application {
    mainClass.set(System.getProperty("mainClass") ?: "com.example.firestorehang.FirestoreMapHangRepro")
}

repositories {
    mavenCentral()
}

// Use the exact same protobuf-javalite version as Firebase BOM 34.9.0 / firebase-firestore 26.1.0
val protobufVersion = "3.25.5"

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
