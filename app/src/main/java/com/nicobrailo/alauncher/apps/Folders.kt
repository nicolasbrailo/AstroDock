package com.nicobrailo.alauncher.apps

// Folders group apps in the app list. A folder holds the keys of the apps in it
// (see LauncherApp.key), so it survives an app being updated, and apps that are
// uninstalled simply drop out of it.
data class Folder(val id: String, val name: String, val appKeys: List<String>)

// Changing a list of folders. Pure functions, so the rules are easy to test:
// an app is only ever in one folder, and a folder with fewer than two apps is
// dissolved (a folder holding one app is just that app with extra steps).
object FolderOps {
    const val MIN_APPS = 2

    // Puts two apps in a new folder, taking them out of any folder they were in
    fun create(folders: List<Folder>, id: String, name: String, first: String, second: String): List<Folder> {
        if (first == second) return folders
        val without = folders.map { it.copy(appKeys = it.appKeys - setOf(first, second)) }
        return (without + Folder(id, name, listOf(first, second))).dissolveSmall()
    }

    // Moves an app into a folder, taking it out of any other
    fun addApp(folders: List<Folder>, folderId: String, appKey: String): List<Folder> =
        folders.map {
            when (it.id) {
                folderId -> if (appKey in it.appKeys) it else it.copy(appKeys = it.appKeys + appKey)
                else -> it.copy(appKeys = it.appKeys - appKey)
            }
        }.dissolveSmall()

    // Takes an app out of its folder. The folder goes away if too little is left.
    fun removeApp(folders: List<Folder>, appKey: String): List<Folder> =
        folders.map { it.copy(appKeys = it.appKeys - appKey) }.dissolveSmall()

    fun rename(folders: List<Folder>, folderId: String, name: String): List<Folder> =
        folders.map { if (it.id == folderId) it.copy(name = name) else it }

    // Ungroups a folder: its apps go back to the list on their own
    fun delete(folders: List<Folder>, folderId: String): List<Folder> =
        folders.filterNot { it.id == folderId }

    // Drops folders that no longer hold enough apps, e.g. after the apps in
    // them were uninstalled
    fun forApps(folders: List<Folder>, existingKeys: Set<String>): List<Folder> =
        folders.map { it.copy(appKeys = it.appKeys.filter { key -> key in existingKeys }) }.dissolveSmall()

    private fun List<Folder>.dissolveSmall() = filter { it.appKeys.size >= MIN_APPS }
}
