package com.nicobrailo.astrodock

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nicobrailo.astrodock.apps.Folder
import com.nicobrailo.astrodock.apps.FolderOps
import com.nicobrailo.astrodock.apps.FolderStore
import com.nicobrailo.astrodock.apps.LauncherApp
import com.nicobrailo.astrodock.apps.LauncherModel
import com.nicobrailo.astrodock.overlay.HomeButtonApps
import com.nicobrailo.astrodock.overlay.HomeButtonService
import kotlinx.coroutines.launch
import java.util.UUID

// Grid of the installed apps and the folders they're grouped into. Tapping an
// app launches it and closes the list, so coming back lands on the slideshow.
// Tapping a folder opens it. The button in the corner opens SettingsActivity.
//
// Long-pressing an item picks it up:
//  - Dropping it on another app puts both in a new folder; dropping it on a
//    folder adds it to that folder.
//  - Letting go without moving shows a menu instead (app info, uninstall, or
//    renaming and ungrouping a folder).
class AppListActivity : AppCompatActivity() {
    // What the grid shows: an app on its own, or a folder of apps
    private sealed interface Entry {
        val label: String
    }

    private data class AppItem(val app: LauncherApp) : Entry {
        override val label get() = app.label
    }

    private data class FolderItem(val folder: Folder, val apps: List<LauncherApp>) : Entry {
        override val label get() = folder.name
    }

    private lateinit var model: LauncherModel
    private lateinit var folderStore: FolderStore
    private lateinit var homeButtonApps: HomeButtonApps
    private val adapter = EntryAdapter()

    private var apps: List<LauncherApp> = emptyList()
    private var folders: List<Folder> = emptyList()

    // The item being dragged, and whether the drag ever left it. A drag that
    // never moved is a long-press, and opens the menu instead.
    private var dragged: Entry? = null
    private var dragMoved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)
        hideSystemBars()

        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        model = LauncherModel(this) { refresh() }
        folderStore = FolderStore(this)
        homeButtonApps = HomeButtonApps(this)

        val grid = findViewById<RecyclerView>(R.id.apps)
        grid.layoutManager = GridLayoutManager(this, columns())
        grid.adapter = adapter
        // Dropping on empty space does nothing, but the drag has to be accepted
        // here or it ends as soon as it leaves an item
        grid.setOnDragListener { _, event -> event.action != DragEvent.ACTION_DROP }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        // LauncherModel reports later changes; this catches anything that
        // happened while the list was closed
        model.start()
        refresh()
    }

    override fun onStop() {
        model.stop()
        super.onStop()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") // The replacement needs API 34
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun columns(): Int {
        val columnWidthPx = resources.displayMetrics.density * COLUMN_WIDTH_DP
        return (resources.displayMetrics.widthPixels / columnWidthPx).toInt().coerceAtLeast(1)
    }

    private fun refresh() {
        lifecycleScope.launch {
            apps = model.apps()
            // Apps uninstalled while we weren't looking leave their folders
            val pruned = FolderOps.forApps(folderStore.load(), apps.map { it.key }.toSet())
            updateFolders(pruned, save = pruned != folders)
        }
    }

    private fun updateFolders(newFolders: List<Folder>, save: Boolean = true) {
        folders = newFolders
        if (save) folderStore.save(newFolders)

        val byKey = apps.associateBy { it.key }
        val folderItems = folders.map { folder -> FolderItem(folder, folder.appKeys.mapNotNull(byKey::get)) }
        val grouped = folders.flatMap { it.appKeys }.toSet()
        val loose = apps.filterNot { it.key in grouped }.map { AppItem(it) }
        adapter.submit((folderItems + loose).sortedBy { it.label.lowercase() })
    }

    // ---- Opening things ----------------------------------------------------

    private fun launch(app: LauncherApp) {
        try {
            model.launch(app)
            // Some apps leave no way back to the launcher (see HomeButtonService)
            if (homeButtonApps.shouldShow(app.component.packageName, app.wantsLightStatusBar)) {
                HomeButtonService.show(this)
            }
            finish()
        } catch (e: SecurityException) {
            // Uninstalled since the list was built, or not launchable any more
            Log.w(TAG, "Can't launch ${app.component}", e)
            refresh()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Can't launch ${app.component}", e)
            refresh()
        }
    }

    private fun openFolder(item: FolderItem) {
        val view = layoutInflater.inflate(R.layout.dialog_folder, null) as RecyclerView
        val contents = EntryAdapter()
        view.layoutManager = GridLayoutManager(this, columns().coerceAtMost(4))
        view.adapter = contents
        contents.submit(item.apps.map { AppItem(it) })

        val dialog = AlertDialog.Builder(this, R.style.Theme_AstroDock_Dialog)
            .setTitle(item.folder.name)
            .setView(view)
            .show()
        contents.onClick = { entry ->
            dialog.dismiss()
            launch((entry as AppItem).app)
        }
        // Inside a folder the menu can also take an app out of it
        contents.onLongPress = { entry, anchor ->
            appMenu(anchor, (entry as AppItem).app, insideFolder = true) { dialog.dismiss() }
        }
    }

    // ---- Menus -------------------------------------------------------------

    private fun showMenu(entry: Entry, anchor: View) {
        when (entry) {
            is AppItem -> appMenu(anchor, entry.app, insideFolder = false) {}
            is FolderItem -> folderMenu(anchor, entry.folder)
        }
    }

    private fun appMenu(anchor: View, app: LauncherApp, insideFolder: Boolean, onChanged: () -> Unit) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(R.string.app_menu_app_info).setOnMenuItemClickListener {
            model.showAppInfo(app)
            onChanged()
            true
        }
        // System apps can't be uninstalled, and another profile's apps can only
        // be uninstalled from inside that profile
        if (!app.isSystem && app.isOwnProfile) {
            menu.menu.add(R.string.app_menu_uninstall).setOnMenuItemClickListener {
                val uri = Uri.parse("package:${app.component.packageName}")
                startActivity(Intent(Intent.ACTION_DELETE, uri))
                onChanged()
                true
            }
        }
        val packageName = app.component.packageName
        val hasHomeButton = homeButtonApps.shouldShow(packageName, app.wantsLightStatusBar)
        val homeButtonText =
            if (hasHomeButton) R.string.app_menu_home_button_off else R.string.app_menu_home_button_on
        menu.menu.add(homeButtonText).setOnMenuItemClickListener {
            // Without the permission the button can't be drawn, so ask for it
            if (!hasHomeButton && !AndroidSettings.canDrawOverlays(this)) {
                // The permission is ours, not the app's we're covering
                startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${this.packageName}")
                    )
                )
            }
            homeButtonApps.setShown(packageName, !hasHomeButton)
            onChanged()
            true
        }
        if (insideFolder) {
            menu.menu.add(R.string.app_menu_remove_from_folder).setOnMenuItemClickListener {
                updateFolders(FolderOps.removeApp(folders, app.key))
                onChanged()
                true
            }
        }
        menu.show()
    }

    private fun folderMenu(anchor: View, folder: Folder) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(R.string.folder_menu_rename).setOnMenuItemClickListener {
            renameFolder(folder)
            true
        }
        menu.menu.add(R.string.folder_menu_ungroup).setOnMenuItemClickListener {
            updateFolders(FolderOps.delete(folders, folder.id))
            true
        }
        menu.show()
    }

    private fun renameFolder(folder: Folder) {
        val input = EditText(this).apply {
            setText(folder.name)
            setSelection(folder.name.length)
        }
        AlertDialog.Builder(this, R.style.Theme_AstroDock_Dialog)
            .setTitle(R.string.folder_rename_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) updateFolders(FolderOps.rename(folders, folder.id, name))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---- Dragging ----------------------------------------------------------

    private fun startDrag(entry: Entry, view: View) {
        dragged = entry
        dragMoved = false
        val data = ClipData.newPlainText("astrodock-entry", entry.label)
        view.startDragAndDrop(data, View.DragShadowBuilder(view), null, View.DRAG_FLAG_OPAQUE)
    }

    // Handles a drag over one item of the grid. Returns whether the event was
    // consumed, which is what a drag listener must report.
    private fun onDrag(target: Entry, view: View, event: DragEvent): Boolean {
        val source = dragged ?: return false
        val onSelf = target == source
        when (event.action) {
            DragEvent.ACTION_DRAG_ENTERED -> {
                if (!onSelf) {
                    dragMoved = true
                    view.alpha = HIGHLIGHT_ALPHA
                }
            }

            DragEvent.ACTION_DRAG_EXITED -> view.alpha = 1f

            DragEvent.ACTION_DROP -> {
                view.alpha = 1f
                if (!onSelf) drop(source, target)
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                view.alpha = 1f
                // A long-press that never went anywhere: show the menu instead
                if (onSelf && !dragMoved) showMenu(source, view)
                if (onSelf) dragged = null
            }
        }
        return true
    }

    // What dropping one item on another means
    private fun drop(source: Entry, target: Entry) {
        when {
            // Two apps make a new folder
            source is AppItem && target is AppItem -> updateFolders(
                FolderOps.create(
                    folders,
                    UUID.randomUUID().toString(),
                    getString(R.string.folder_default_name),
                    target.app.key,
                    source.app.key,
                )
            )
            // An app dropped on a folder joins it
            source is AppItem && target is FolderItem ->
                updateFolders(FolderOps.addApp(folders, target.folder.id, source.app.key))
            // Dragging folders around isn't supported
            else -> Unit
        }
    }

    // ---- The grid ----------------------------------------------------------

    private inner class EntryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var entries: List<Entry> = emptyList()

        // Replaced inside a folder, where tapping and long-pressing differ
        var onClick: (Entry) -> Unit = { entry ->
            when (entry) {
                is AppItem -> launch(entry.app)
                is FolderItem -> openFolder(entry)
            }
        }
        var onLongPress: ((Entry, View) -> Unit)? = null

        @Suppress("NotifyDataSetChanged") // The whole list is replaced, and it's small
        fun submit(newEntries: List<Entry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun getItemCount() = entries.size

        override fun getItemViewType(position: Int) =
            if (entries[position] is FolderItem) TYPE_FOLDER else TYPE_APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == TYPE_FOLDER) R.layout.item_folder else R.layout.item_app
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = entries[position]
            val view = holder.itemView
            view.findViewById<TextView>(R.id.label).text = entry.label
            when (entry) {
                is AppItem -> view.findViewById<ImageView>(R.id.icon).setImageDrawable(entry.app.icon)
                is FolderItem -> {
                    val previews = listOf(R.id.icon1, R.id.icon2, R.id.icon3, R.id.icon4)
                    for ((i, id) in previews.withIndex()) {
                        view.findViewById<ImageView>(id).setImageDrawable(entry.apps.getOrNull(i)?.icon)
                    }
                }
            }

            view.setOnClickListener { onClick(entry) }
            view.setOnLongClickListener {
                val longPress = onLongPress
                if (longPress != null) longPress(entry, view) else startDrag(entry, view)
                true
            }
            // Only the main grid rearranges things
            view.setOnDragListener(
                if (onLongPress == null) View.OnDragListener { v, event -> onDrag(entry, v, event) } else null
            )
        }
    }

    private companion object {
        const val TAG = "AppListActivity"
        const val COLUMN_WIDTH_DP = 140
        const val TYPE_APP = 0
        const val TYPE_FOLDER = 1
        const val HIGHLIGHT_ALPHA = 0.4f
    }
}
