import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.junit)
    // Desktop build of the same ONNX Runtime Java API the Android app uses,
    // so the real model can be tested end to end on the JVM.
    testImplementation(libs.onnxruntime.jvm)
}

tasks.test {
    // Point at an exported model folder to run RealModelTest; skipped otherwise.
    System.getenv("JEV_MODEL_DIR")?.let {
        environment("JEV_MODEL_DIR", it)
        inputs.property("heronModelDir", it)
        inputs.dir(it)
    }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
