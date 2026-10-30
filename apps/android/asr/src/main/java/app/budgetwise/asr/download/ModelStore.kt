package app.budgetwise.asr.download

import android.content.Context
import app.budgetwise.asr.ModelFile
import app.budgetwise.asr.VoicePack
import app.budgetwise.asr.engine.VoiceFiles
import org.json.JSONObject
import java.io.File

/** On-disk layout: `noBackupFilesDir/models/voice/<VoicePack.VERSION>/{model files, verified.json}`. */
internal object ModelStore {
    const val MARKER = "verified.json"
    private const val PACK_DIR = "voice"

    fun modelsRoot(context: Context): File = File(context.noBackupFilesDir, "models")

    fun packRoot(context: Context): File = File(modelsRoot(context), PACK_DIR)

    fun dir(context: Context): File = File(packRoot(context), VoicePack.VERSION)

    fun fileFor(dir: File, f: ModelFile): File = File(dir, f.name)

    /** Bytes already downloaded: completed files plus `.part` files, each capped at its pinned size. */
    fun bytesOnDisk(context: Context): Long {
        val dir = dir(context)
        return VoicePack.files.sumOf { f ->
            val dest = fileFor(dir, f)
            val part = File(dest.path + ".part")
            minOf(maxOf(dest.length(), part.length()), f.bytes)
        }
    }

    /**
     * Deletes what this build can no longer use: older pack versions and the Raj model directories of earlier
     * builds (`models/RAJ_0_6B`, `models/RAJ_1_7B`), so they stop counting against free storage.
     */
    fun deleteStale(context: Context) {
        val packRoot = packRoot(context)
        modelsRoot(context).listFiles().orEmpty().filter { it != packRoot }.forEach { it.deleteRecursively() }
        packRoot.listFiles().orEmpty().filter { it.name != VoicePack.VERSION }.forEach { it.deleteRecursively() }
    }

    fun writeMarker(dir: File) {
        val files = JSONObject()
        for (f in VoicePack.files) files.put(f.name, JSONObject().put("sha256", f.sha256).put("bytes", f.bytes))
        val json = JSONObject().put("pack", VoicePack.VERSION).put("files", files)
        val tmp = File(dir, "$MARKER.tmp")
        tmp.writeText(json.toString())
        File(dir, MARKER).delete()
        if (!tmp.renameTo(File(dir, MARKER))) throw java.io.IOException("could not write $MARKER")
    }

    /** Cheap check: the marker lists the expected hashes and every file has the expected size. */
    fun verified(context: Context): VoiceFiles? {
        val dir = dir(context)
        val marker = File(dir, MARKER)
        if (!marker.isFile) return null
        val json = runCatching { JSONObject(marker.readText()) }.getOrNull() ?: return null
        val listed = json.optJSONObject("files") ?: return null
        val ok = VoicePack.files.all { f ->
            val file = fileFor(dir, f)
            listed.optJSONObject(f.name)?.optString("sha256").equals(f.sha256, true) &&
                file.isFile && file.length() == f.bytes
        }
        if (!ok) return null
        return VoiceFiles(
            freococoModel = fileFor(dir, VoicePack.FREOCOCO_MODEL),
            freococoVocab = fileFor(dir, VoicePack.FREOCOCO_VOCAB),
            omnilingualModel = fileFor(dir, VoicePack.OMNILINGUAL_MODEL),
            omnilingualTokens = fileFor(dir, VoicePack.OMNILINGUAL_TOKENS),
        )
    }
}
