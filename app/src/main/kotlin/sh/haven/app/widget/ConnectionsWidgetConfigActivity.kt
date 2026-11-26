package sh.haven.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import sh.haven.app.R
import sh.haven.core.data.db.ConnectionGroupDao
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.ui.theme.HavenTheme
import sh.haven.feature.connections.R as ConnR

@AndroidEntryPoint
class ConnectionsWidgetConfigActivity : AppCompatActivity() {

    @Inject lateinit var connectionGroupDao: ConnectionGroupDao
    @Inject lateinit var preferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val initial = ConnectionsWidgetModel.loadConfig(this, appWidgetId)
        enableEdgeToEdge()
        setContent {
            val themeMode by preferencesRepository.theme
                .collectAsState(initial = UserPreferencesRepository.ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                UserPreferencesRepository.ThemeMode.LIGHT -> false
                UserPreferencesRepository.ThemeMode.DARK -> true
                UserPreferencesRepository.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val groups by connectionGroupDao.observeAll().collectAsState(initial = emptyList())

            var selectedGroupId by remember { mutableStateOf(initial.groupId) }
            var selectedSort by remember { mutableStateOf(initial.sortOrder) }

            HavenTheme(darkTheme = darkTheme) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = stringResource(R.string.widget_config_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.widget_config_group_heading),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OptionRow(
                            label = stringResource(R.string.widget_config_all_connections),
                            selected = selectedGroupId == null,
                            onClick = { selectedGroupId = null },
                        )
                        groups.forEach { g ->
                            OptionRow(
                                label = g.label,
                                selected = selectedGroupId == g.id,
                                onClick = { selectedGroupId = g.id },
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.widget_config_sort_heading),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OptionRow(
                            label = stringResource(R.string.widget_config_sort_saved),
                            selected = selectedSort == WidgetSortOrder.SAVED_ORDER,
                            onClick = { selectedSort = WidgetSortOrder.SAVED_ORDER },
                        )
                        OptionRow(
                            label = stringResource(R.string.widget_config_sort_recent),
                            selected = selectedSort == WidgetSortOrder.RECENT_FIRST,
                            onClick = { selectedSort = WidgetSortOrder.RECENT_FIRST },
                        )

                        Spacer(Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { finish() }) {
                                Text(stringResource(ConnR.string.common_cancel))
                            }
                            Button(
                                onClick = {
                                    val groupLabel = groups.firstOrNull { it.id == selectedGroupId }?.label
                                    ConnectionsWidgetModel.saveConfig(
                                        context = this@ConnectionsWidgetConfigActivity,
                                        appWidgetId = appWidgetId,
                                        config = WidgetConfig(
                                            groupId = selectedGroupId,
                                            groupLabel = groupLabel,
                                            sortOrder = selectedSort,
                                        ),
                                    )
                                    val mgr = AppWidgetManager.getInstance(this@ConnectionsWidgetConfigActivity)
                                    ConnectionsAppWidgetProvider.updateAppWidget(
                                        context = this@ConnectionsWidgetConfigActivity,
                                        appWidgetManager = mgr,
                                        appWidgetId = appWidgetId,
                                    )
                                    @Suppress("DEPRECATION")
                                    mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_connections_list)
                                    setResult(
                                        RESULT_OK,
                                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                                    )
                                    finish()
                                },
                            ) {
                                Text(stringResource(R.string.widget_config_save))
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
