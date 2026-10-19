package app.budgetwise.asr.download

import android.content.Context
import app.budgetwise.asr.ModelFile
import app.budgetwise.asr.RajVariant
import app.budgetwise.asr.engine.ModelFiles
import org.json.JSONObject
import java.io.File

/** On-disk layout: `noBackupFilesDir/models/<variant>/<revision>/{gguf files, verified.json}`. */
internal object ModelStore {
    const val MARKER = "verified.json"

    fun variantRoot(context: Context, v: RajVariant): File =
        File(File(context.noBackupFilesDir, "models"), v.name)

    fun dir(context: Context, v: RajVariant): File = File(variantRoot(context, v), v.revision)

    fun fileFor(dir: File, f: ModelFile): File = File(dir, f.path.substringAfterLast('/'))

    fun writeMarker(dir: File, v: RajVariant) {
        val json = JSONObject()
            .put("revision", v.revision)
            .put("model", JSONObject().put("sha256", v.model.sha256).put("bytes", v.model.bytes))
            .put("mmproj", JSONObject().put("sha256", v.mmproj.sha256).put("bytes", v.mmproj.bytes))
        val tmp = File(dir, "$MARKER.tmp")
        tmp.writeText(json.toString())
        File(dir, MARKER).delete()
        if (!tmp.renameTo(File(dir, MARKER))) throw java.io.IOException("could not write $MARKER")
    }

    /** Cheap check: marker lists the expected hashes and both files have the expected sizes. */
    fun verified(context: Context, v: RajVariant): ModelFiles? {
        val dir = dir(context, v)
        val marker = File(dir, MARKER)
        if (!marker.isFile) return null
        val json = runCatching { JSONObject(marker.readText()) }.getOrNull() ?: return null
        val model = fileFor(dir, v.model)
        val mmproj = fileFor(dir, v.mmproj)
        val ok = json.optJSONObject("model")?.optString("sha256").equals(v.model.sha256, true) &&
            json.optJSONObject("mmproj")?.optString("sha256").equals(v.mmproj.sha256, true) &&
            model.isFile && model.length() == v.model.bytes &&
            mmproj.isFile && mmproj.length() == v.mmproj.bytes
        return if (ok) ModelFiles(model, mmproj) else null
    }
}
