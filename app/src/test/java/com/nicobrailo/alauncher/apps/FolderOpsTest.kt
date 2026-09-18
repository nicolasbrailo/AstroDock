package com.nicobrailo.alauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderOpsTest {
    private val none = emptyList<Folder>()

    @Test
    fun createMakesAFolderOfTwoApps() {
        val folders = FolderOps.create(none, "1", "Games", "a", "b")
        assertEquals(1, folders.size)
        assertEquals(listOf("a", "b"), folders[0].appKeys)
        // Dropping an app on itself does nothing
        assertEquals(none, FolderOps.create(none, "1", "Games", "a", "a"))
    }

    @Test
    fun anAppIsOnlyEverInOneFolder() {
        var folders = FolderOps.create(none, "1", "Games", "a", "b")
        folders = FolderOps.create(folders, "2", "Work", "c", "d")
        folders = FolderOps.addApp(folders, "2", "a")
        assertEquals(listOf("c", "d", "a"), folders.single { it.id == "2" }.appKeys)
        // "1" held only a and b, so with a gone it dissolves
        assertTrue(folders.none { it.id == "1" })
    }

    @Test
    fun aFolderWithFewerThanTwoAppsDissolves() {
        var folders = FolderOps.create(none, "1", "Games", "a", "b")
        folders = FolderOps.addApp(folders, "1", "c")
        folders = FolderOps.removeApp(folders, "c")
        assertEquals(listOf("a", "b"), folders.single().appKeys)
        assertEquals(none, FolderOps.removeApp(folders, "a"))
    }

    @Test
    fun uninstalledAppsDropOut() {
        var folders = FolderOps.create(none, "1", "Games", "a", "b")
        folders = FolderOps.addApp(folders, "1", "c")
        assertEquals(listOf("a", "c"), FolderOps.forApps(folders, setOf("a", "c", "z")).single().appKeys)
        assertEquals(none, FolderOps.forApps(folders, setOf("a")))
    }

    @Test
    fun renameAndDelete() {
        val folders = FolderOps.create(none, "1", "Games", "a", "b")
        assertEquals("Fun", FolderOps.rename(folders, "1", "Fun").single().name)
        assertEquals(none, FolderOps.delete(folders, "1"))
    }
}
