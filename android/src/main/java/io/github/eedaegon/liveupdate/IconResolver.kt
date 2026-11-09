package io.github.eedaegon.liveupdate

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import org.json.JSONObject
import java.io.File

private const val MAX_ICON_BYTES = 1_048_576L
private const val MAX_ICON_SIDE = 1_024
private const val MAX_RESOURCE_NAME = 120
private const val MAX_ICON_PATH = 240
private const val INVALID_MESSAGE = "Invalid live update request."

/** Resolves only packaged resources and files below the app-private files directory. */
internal class IconResolver(private val context: Context) {
    fun resolve(source: JSONObject): Icon {
        requireFields(source, setOf("kind", "value"))
        val value = source.optionalString("value") ?: invalid()
        return when (source.optionalString("kind")) {
            "resource" -> resource(value)
            "appFile" -> appFile(value)
            else -> invalid()
        }
    }

    private fun resource(name: String): Icon {
        if (!validResourceName(name)) invalid()
        val resources = context.resources
        val drawable = resources.getIdentifier(name, "drawable", context.packageName)
        val id = if (drawable != 0) drawable else resources.getIdentifier(name, "mipmap", context.packageName)
        if (id == 0) invalid()
        return Icon.createWithResource(context, id)
    }

    private fun appFile(path: String): Icon {
        if (!validAppFile(path)) invalid()
        val root = context.filesDir.canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile || file.length() > MAX_ICON_BYTES) {
            invalid()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..MAX_ICON_SIDE || bounds.outHeight !in 1..MAX_ICON_SIDE) invalid()
        val bitmap = BitmapFactory.decodeFile(file.path) ?: invalid()
        return Icon.createWithBitmap(bitmap)
    }

    private fun validResourceName(value: String): Boolean = value.length in 1..MAX_RESOURCE_NAME &&
        value.first() in 'a'..'z' && value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    private fun validAppFile(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_ICON_PATH || value.startsWith('/') || '\\' in value) return false
        return value.split('/').all { segment ->
            segment.isNotEmpty() && segment !in setOf(".", "..") &&
                segment.all { it.isAsciiLetterOrDigit() || it in "._-" }
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    private fun requireFields(value: JSONObject, expected: Set<String>) {
        if (value.keys().asSequence().toSet() != expected) invalid()
    }

    private fun invalid(): Nothing = throw AdapterFailure("INVALID_INPUT", INVALID_MESSAGE)
}
