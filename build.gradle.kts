// AGP 8.13.x -> Gradle 8.13+ (wrapper épinglé en 8.14.3), JDK 17+.
// Kotlin 2.3.x : compatible AGP <= 8.13 ; la migration AGP 9 (Kotlin intégré) est
// une étape séparée (change la déclaration des plugins et exige Gradle 9.1+).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
