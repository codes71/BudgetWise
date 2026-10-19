package app.budgetwise.asr.engine

import java.io.File

/** Verified on-disk model files for one variant. */
data class ModelFiles(val model: File, val mmproj: File)
