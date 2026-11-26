package sh.haven.app.widget

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import sh.haven.app.MainActivity
import sh.haven.app.R
import sh.haven.app.launcher.LauncherShortcutManager

private const val TAG = "ConnectionsWidget"

/**
 * Home-screen saved-connections launcher widget (#852).
 *
 * Displays non-secret connection metadata via [ConnectionsWidgetService] and
 * routes row taps to [MainActivity] (`ACTION_LAUNCH_CONNECTION`), matching
 * [LauncherShortcutManager] shortcuts.
 */
class ConnectionsAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            ConnectionsWidgetModel.deleteConfig(context, appWidgetId)
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val config = ConnectionsWidgetModel.loadConfig(context, appWidgetId)
            val title = config.groupLabel?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.widget_connections_title)

            val serviceIntent = Intent(context, ConnectionsWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPi = PendingIntent.getActivity(
                context,
                appWidgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                activityStartBundle(),
            )

            val configIntent = Intent(context, ConnectionsWidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse("haven-widget://configure/$appWidgetId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val configPi = PendingIntent.getActivity(
                context,
                appWidgetId + 100_000,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                activityStartBundle(),
            )

            val rowTemplateIntent = Intent(context, MainActivity::class.java).apply {
                action = LauncherShortcutManager.ACTION_LAUNCH_CONNECTION
                data = Uri.parse("haven-widget://connect/$appWidgetId")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val rowTemplatePi = PendingIntent.getActivity(
                context,
                appWidgetId + 200_000,
                rowTemplateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
                activityStartBundle(),
            )

            val views = RemoteViews(context.packageName, R.layout.widget_connections).apply {
                setTextViewText(R.id.widget_header_title, title)
                setOnClickPendingIntent(R.id.widget_header_title, openAppPi)
                setOnClickPendingIntent(R.id.widget_header_config, configPi)
                setRemoteAdapter(R.id.widget_connections_list, serviceIntent)
                setEmptyView(R.id.widget_connections_list, R.id.widget_empty_text)
                setPendingIntentTemplate(R.id.widget_connections_list, rowTemplatePi)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        @Suppress("DEPRECATION")
        fun notifyDataChanged(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context) ?: return
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, ConnectionsAppWidgetProvider::class.java),
                )
                if (ids.isEmpty()) return
                for (id in ids) {
                    updateAppWidget(context, mgr, id)
                }
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_connections_list)
            } catch (e: Exception) {
                Log.w(TAG, "notifyDataChanged failed: ${e.message}")
            }
        }

        private fun activityStartBundle() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                    .toBundle()
            } else {
                null
            }
    }
}
