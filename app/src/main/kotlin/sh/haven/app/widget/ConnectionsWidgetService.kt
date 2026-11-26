package sh.haven.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import sh.haven.app.R
import sh.haven.app.launcher.LauncherShortcutManager
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.ConnectionGroupDao

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ConnectionsWidgetEntryPoint {
    fun connectionDao(): ConnectionDao
    fun connectionGroupDao(): ConnectionGroupDao
}

class ConnectionsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return ConnectionsRemoteViewsFactory(applicationContext, appWidgetId)
    }
}

private class ConnectionsRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile
    private var rows: List<WidgetConnectionRow> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val entryPoint = EntryPointAccessors.fromApplication(
            context,
            ConnectionsWidgetEntryPoint::class.java,
        )
        val config = ConnectionsWidgetModel.loadConfig(context, appWidgetId)
        val profiles = runCatching {
            runBlocking { entryPoint.connectionDao().getAll() }
        }.getOrDefault(emptyList())
        rows = ConnectionsWidgetModel.buildRows(profiles, config)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        val snapshot = rows
        val row = snapshot.getOrNull(position) ?: return null
        return RemoteViews(context.packageName, R.layout.widget_connection_item).apply {
            setTextViewText(R.id.widget_item_badge, row.badge)
            setTextViewText(R.id.widget_item_label, row.label)
            if (row.subtitle.isNotBlank()) {
                setTextViewText(R.id.widget_item_subtitle, row.subtitle)
                setViewVisibility(R.id.widget_item_subtitle, View.VISIBLE)
            } else {
                setViewVisibility(R.id.widget_item_subtitle, View.GONE)
            }
            val fillIn = Intent().apply {
                putExtra(LauncherShortcutManager.EXTRA_CONNECTION_ID, row.profileId)
            }
            setOnClickFillInIntent(R.id.widget_item_root, fillIn)
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.profileId?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
