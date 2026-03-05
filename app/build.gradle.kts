plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  namespace = "com.ml.app"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.ml.app"
    minSdk = 26
    targetSdk = 34
    versionCode = 2
    versionName = "1.0"

    val r2Endpoint = System.getenv("R2_ENDPOINT") ?: "https://REPLACE_ME.r2.cloudflarestorage.com"
    val r2Bucket = System.getenv("R2_BUCKET") ?: "ml-br"
    val r2AccessKey = System.getenv("R2_ACCESS_KEY") ?: "REPLACE_ME"
    val r2SecretKey = System.getenv("R2_SECRET_KEY") ?: "REPLACE_ME"
    val r2ObjectKey = System.getenv("R2_OBJECT_KEY") ?: "packs/current/database_pack.zip"
    val r2Region = System.getenv("R2_REGION") ?: "auto"
    val updatedBy = System.getenv("UPDATED_BY") ?: "ml-app"

    buildConfigField("String", "R2_ENDPOINT", "\"$r2Endpoint\"")
    buildConfigField("String", "R2_BUCKET", "\"$r2Bucket\"")
    buildConfigField("String", "R2_ACCESS_KEY", "\"$r2AccessKey\"")
    buildConfigField("String", "R2_SECRET_KEY", "\"$r2SecretKey\"")
    buildConfigField("String", "R2_OBJECT_KEY", "\"$r2ObjectKey\"")
    buildConfigField("String", "R2_REGION", "\"$r2Region\"")
    buildConfigField("String", "UPDATED_BY", "\"$updatedBy\"")
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  
    implementation("androidx.compose.material:material")
implementation(platform("androidx.compose:compose-bom:2024.06.00"))
  implementation("androidx.activity:activity-compose:1.9.1")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3:1.2.1")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("io.coil-kt:coil-compose:2.6.0")
  implementation("org.json:json:20240303")

  debugImplementation("androidx.compose.ui:ui-tooling")
  implementation("androidx.navigation:navigation-compose:2.7.7")

  implementation("androidx.compose.foundation:foundation")

}
