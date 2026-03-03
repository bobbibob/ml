package com.ml.app.data

import android.content.Context
import java.io.File

object PackPaths {
  fun packDir(context: Context): File = File(context.filesDir, "current_pack")
  fun dbFile(context: Context): File = File(packDir(context), "data.sqlite")
  fun imagesDir(context: Context): File = File(packDir(context), "images")
  fun manifestFile(context: Context): File = File(packDir(context), "manifest.json")
  fun tempDir(context: Context): File = File(context.cacheDir, "ml_tmp")
}
