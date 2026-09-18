package com.nicobrailo.alauncher.apps

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Saves the folders (FolderOps) in their own SharedPreferences file, as JSON:
// [{"id": ..., "name": ..., "apps": [key, ...]}, ...]
class FolderStore(context: Context) {
    private val prefs = context.getSharedPreferences("folders", Context.MODE_PRIVATE)

    fun load(): List<Folder> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val apps = obj.getJSONArray("apps")
                Folder(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    appKeys = (0 until apps.length()).map { apps.getString(it) },
                )
            }
        } catch (e: JSONException) {
            // Nothing to recover: better an empty app list than a crash loop
            Log.w(TAG, "Ignoring unreadable folders", e)
            emptyList()
        }
    }

    fun save(folders: List<Folder>) {
        val array = JSONArray()
        for (folder in folders) {
            array.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("apps", JSONArray(folder.appKeys))
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val TAG = "FolderStore"
        const val KEY = "folders"
    }
}
