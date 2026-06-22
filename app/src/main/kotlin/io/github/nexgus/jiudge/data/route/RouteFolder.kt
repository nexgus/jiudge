package io.github.nexgus.jiudge.data.route

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Remembers the user-picked SAF folder that holds saved routes. The folder lives in public storage
 * (Documents/Downloads/Drive - the user's choice), so unlike app storage it survives uninstall. We
 * persist the tree URI and take a durable read/write grant so the choice sticks across restarts.
 *
 * If the grant is ever lost (reinstall, cleared data, manual revoke) [current] returns null and the
 * UI re-prompts; the route files themselves stay where the user put them.
 */
object RouteFolder {
    private const val PREFS = "route_folder"
    private const val KEY_TREE_URI = "tree_uri"

    /** The picked folder's tree URI, or null if none is set or the grant has been lost. */
    fun current(context: Context): Uri? {
        val saved = prefs(context).getString(KEY_TREE_URI, null)?.let(Uri::parse) ?: return null
        val stillGranted =
            context.contentResolver.persistedUriPermissions.any {
                it.uri == saved && it.isReadPermission && it.isWritePermission
            }
        return saved.takeIf { stillGranted }
    }

    /** Persists the picked tree [uri] and takes a durable read/write grant on it. */
    fun persist(
        context: Context,
        uri: Uri,
    ) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
