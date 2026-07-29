package com.pt.jp.logcatengine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.core.logcat.capture.core.LogcatBufferClearResult
import com.core.logcat.capture.core.LogExportFormat
import com.core.logcat.capture.core.LogFilter
import com.core.logcat.capture.core.LogLevel
import com.core.logcat.capture.core.LogLine
import com.core.logcat.capture.core.LogcatConfig
import com.core.logcat.capture.core.LogcatEngineFactory
import com.core.logcat.capture.core.LogcatSession
import com.core.logcat.capture.core.LogcatState
import com.pt.jp.logcatengine.ui.theme.LogcatEngineTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val UiHistoryLimit = 1_000
private val RunningStateColor = Color(0xFF2E7D32)
private val StartingStateColor = Color(0xFF9C6F19)
private val LogListBackgroundColor = Color(0xFF101418)
private val LogTagColor = Color(0xFFB6C2CF)
private val LogMessageColor = Color(0xFFE6EDF3)
private val VerboseLevelColor = Color(0xFF8B949E)
private val DebugLevelColor = Color(0xFF76E3EA)
private val InfoLevelColor = Color(0xFF7EE787)
private val WarningLevelColor = Color(0xFFFFD866)
private val ErrorLevelColor = Color(0xFFFF7B72)
private val UnknownLevelColor = LogTagColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LogcatEngineTheme {
                LogcatSampleLab()
            }
        }
    }
}

@Composable
private fun LogcatSampleLab() {
    val engine = remember { LogcatEngineFactory.create() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val listState = rememberLazyListState()
    val lines = remember { mutableStateListOf<LogLine>() }
    val engineState by engine.state.collectAsState()

    var session by remember { mutableStateOf<LogcatSession?>(null) }
    var filterMode by remember { mutableStateOf(SampleFilterMode.None) }
    var filterText by remember { mutableStateOf("") }
    var minLevel by remember { mutableStateOf(LogLevel.Debug) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var simulateLogsEnabled by remember { mutableStateOf(false) }
    var showClearLogcatDialog by remember { mutableStateOf(false) }
    var isClearingLogcat by remember { mutableStateOf(false) }
    var controlsExpanded by remember { mutableStateOf(true) }
    val simulatorTag = stringResource(R.string.log_tag_simulator)
    val shareSheetOpenedStatus = stringResource(R.string.status_share_sheet_opened)
    val simulatedLogsResumedStatus = stringResource(R.string.status_simulated_logs_resumed)
    val simulatedLogsPausedStatus = stringResource(R.string.status_simulated_logs_paused)
    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val isRunning = engineState is LogcatState.Running

    fun startCapture() {
        scope.launch {
            session?.stopAndJoin()
            lines.clear()
            actionStatus = null
            session = engine.startAndJoin(
                LogcatConfig.currentProcess(
                    minLevel = minLevel,
                    filter = filterMode.toFilter(filterText),
                    historyLimit = UiHistoryLimit,
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        startCapture()
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.stop()
        }
    }

    LaunchedEffect(session) {
        session?.logs?.collect { line ->
            lines.add(line)
            if (lines.size > UiHistoryLimit) {
                lines.removeAt(0)
            }
        }
    }

    LaunchedEffect(simulateLogsEnabled, resources, simulatorTag) {
        if (!simulateLogsEnabled) return@LaunchedEffect

        var counter = 0
        while (true) {
            Log.d(simulatorTag, resources.getString(R.string.sample_log_simulator_tick, counter))
            if (counter % 7 == 0) {
                Log.w(
                    simulatorTag,
                    resources.getString(R.string.sample_log_simulator_warning, counter),
                )
            }
            counter += 1
            delay(1_000.milliseconds)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (showBackToTop) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_top))
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 88.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    StatusStrip(
                        state = engineState,
                        pid = Process.myPid(),
                        lineCount = lines.size,
                    )
                    HorizontalDivider()
                }
                item {
                    ControlSection(
                        isRunning = isRunning,
                        expanded = controlsExpanded,
                        onExpandedChange = { controlsExpanded = it },
                        onStart = ::startCapture,
                        onStop = {
                            scope.launch {
                                session?.stopAndJoin()
                            }
                        },
                        onClear = {
                            lines.clear()
                            session?.clearHistory()
                            actionStatus = null
                        },
                        onCopy = {
                            val copied = context.copyLogHistory(session)
                            actionStatus = resources.getQuantityString(
                                R.plurals.status_lines_copied,
                                copied,
                                copied,
                            )
                        },
                        onShare = {
                            session?.shareLogHistory(context)
                            actionStatus = shareSheetOpenedStatus
                        },
                        onClearLogcat = {
                            showClearLogcatDialog = true
                        },
                        simulateLogsEnabled = simulateLogsEnabled,
                        onSimulateLogsChange = { enabled ->
                            simulateLogsEnabled = enabled
                            actionStatus = if (enabled) {
                                simulatedLogsResumedStatus
                            } else {
                                simulatedLogsPausedStatus
                            }
                        },
                    )
                }
                item {
                    FilterStrip(
                        filterMode = filterMode,
                        onFilterModeChange = { mode ->
                            if (isRunning) {
                                filterMode = mode
                                session?.updateFilter(mode.toFilter(filterText))
                            } else {
                                context.showServiceNotRunningToast()
                            }
                        },
                        filterText = filterText,
                        onFilterTextChange = { text ->
                            filterText = text
                            session?.updateFilter(filterMode.toFilter(text))
                        },
                        minLevel = minLevel,
                        onMinLevelChange = { level ->
                            if (isRunning) {
                                minLevel = level
                                startCapture()
                            } else {
                                context.showServiceNotRunningToast()
                            }
                        },
                    )
                }
                actionStatus?.let { status ->
                    item {
                        Text(
                            text = status,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                item {
                    HorizontalDivider()
                }
                items(lines) { line ->
                    LogLineRow(line)
                }
            }

            if (showClearLogcatDialog) {
                ClearLogcatDialog(
                    isClearing = isClearingLogcat,
                    onDismiss = {
                        if (!isClearingLogcat) {
                            showClearLogcatDialog = false
                        }
                    },
                    onConfirm = {
                        scope.launch {
                            isClearingLogcat = true
                            val result = session?.clearDeviceBuffers()
                                ?: engine.clearDeviceBuffers()
                            isClearingLogcat = false
                            showClearLogcatDialog = false
                            actionStatus = resources.toStatusText(result)
                            if (result.isSuccess) {
                                lines.clear()
                                session?.clearHistory()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(
    state: LogcatState,
    pid: Int,
    lineCount: Int,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            StatusPill(state)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.status_pid, pid),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.status_line_count,
                    lineCount,
                    lineCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(state: LogcatState) {
    val color = when (state) {
        is LogcatState.Running -> RunningStateColor
        is LogcatState.Starting -> StartingStateColor
        is LogcatState.Error -> MaterialTheme.colorScheme.error
        is LogcatState.Idle,
        is LogcatState.Stopped -> MaterialTheme.colorScheme.outline
    }
    val label = when (state) {
        is LogcatState.Running -> stringResource(R.string.state_running)
        is LogcatState.Starting -> stringResource(R.string.state_starting)
        is LogcatState.Error -> stringResource(R.string.state_error)
        is LogcatState.Idle -> stringResource(R.string.state_idle)
        is LogcatState.Stopped -> stringResource(R.string.state_stopped)
    }

    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ControlSection(
    isRunning: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClearLogcat: () -> Unit,
    simulateLogsEnabled: Boolean,
    onSimulateLogsChange: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.control_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Button(onClick = onStart) {
                    Text(
                        if (isRunning) {
                            stringResource(R.string.action_restart)
                        } else {
                            stringResource(R.string.action_start)
                        }
                    )
                }
                OutlinedButton(onClick = onStop, enabled = isRunning) {
                    Text(stringResource(R.string.action_stop))
                }
                TextButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(
                        if (expanded) {
                            stringResource(R.string.action_hide)
                        } else {
                            stringResource(R.string.action_show)
                        }
                    )
                }
            }

            if (expanded) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    OutlinedButton(onClick = onClear) {
                        Text(stringResource(R.string.action_clear))
                    }
                    OutlinedButton(onClick = onCopy) {
                        Text(stringResource(R.string.action_copy))
                    }
                    OutlinedButton(onClick = onShare) {
                        Text(stringResource(R.string.action_share))
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    OutlinedButton(onClick = onClearLogcat) {
                        Text(stringResource(R.string.action_clear_logcat))
                    }
                    Checkbox(
                        checked = simulateLogsEnabled,
                        onCheckedChange = onSimulateLogsChange,
                    )
                    Text(
                        text = stringResource(R.string.toggle_simulate_w_logs),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterStrip(
    filterMode: SampleFilterMode,
    onFilterModeChange: (SampleFilterMode) -> Unit,
    filterText: String,
    onFilterTextChange: (String) -> Unit,
    minLevel: LogLevel,
    onMinLevelChange: (LogLevel) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            SampleFilterMode.entries.forEach { mode ->
                FilterChip(
                    selected = filterMode == mode,
                    onClick = { onFilterModeChange(mode) },
                    label = { Text(stringResource(mode.labelResId)) },
                )
            }
        }
        OutlinedTextField(
            value = filterText,
            onValueChange = onFilterTextChange,
            enabled = filterMode != SampleFilterMode.None,
            singleLine = true,
            label = { Text(stringResource(R.string.filter_field_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf(
                LogLevel.Verbose,
                LogLevel.Debug,
                LogLevel.Info,
                LogLevel.Warning,
                LogLevel.Error,
                LogLevel.Fatal,
            ).forEach { level ->
                FilterChip(
                    selected = minLevel == level,
                    onClick = { onMinLevelChange(level) },
                    label = { Text(level.nativeValue) },
                    enabled = true,
                )
            }
        }
    }
}

@Composable
private fun ClearLogcatDialog(
    isClearing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.dialog_clear_logcat_title))
        },
        text = {
            Text(
                stringResource(R.string.dialog_clear_logcat_body),
            )
        },
        confirmButton = {
            TextButton(
                enabled = !isClearing,
                onClick = onConfirm,
            ) {
                Text(
                    if (isClearing) {
                        stringResource(R.string.dialog_clear_logcat_confirm_busy)
                    } else {
                        stringResource(R.string.dialog_clear_logcat_confirm)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isClearing,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun LogLineRow(line: LogLine) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(LogListBackgroundColor)
            .padding(horizontal = 12.dp, vertical = 1.dp),
    ) {
        Text(
            text = line.level?.nativeValue ?: stringResource(R.string.log_level_unknown),
            color = line.level.levelColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = line.tag ?: "",
            color = LogTagColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = line.message,
            color = LogMessageColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class SampleFilterMode(@param:StringRes val labelResId: Int) {
    None(R.string.filter_none),
    Text(R.string.filter_text),
    Regex(R.string.filter_regex);

    fun toFilter(value: String): LogFilter {
        val text = value.trim()
        return when {
            this == None || text.isEmpty() -> LogFilter.None
            this == Text -> LogFilter.Literal(text)
            else -> LogFilter.Regex(text)
        }
    }
}

private fun LogLevel?.levelColor(): Color {
    return when (this) {
        LogLevel.Verbose -> VerboseLevelColor
        LogLevel.Debug -> DebugLevelColor
        LogLevel.Info -> InfoLevelColor
        LogLevel.Warning -> WarningLevelColor
        LogLevel.Error,
        LogLevel.Fatal -> ErrorLevelColor
        LogLevel.Silent,
        null -> UnknownLevelColor
    }
}

private fun Context.copyLogHistory(session: LogcatSession?): Int {
    val history = session?.history().orEmpty()
    val text = history.joinToString(separator = "\n") { line -> line.raw }
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(
        ClipData.newPlainText(getString(R.string.clipboard_log_history_label), text)
    )
    return history.size
}

private fun LogcatSession.shareLogHistory(context: Context) {
    val file = context.cacheDir.resolve("logcat-history.jsonl")
    exportHistory(file, LogExportFormat.JsonLines)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.share_log_history_title))
    )
}

private fun Context.showServiceNotRunningToast() {
    Toast.makeText(
        this,
        getString(R.string.toast_service_not_running),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun Resources.toStatusText(result: LogcatBufferClearResult): String {
    return when (result) {
        LogcatBufferClearResult.Success -> getString(R.string.status_logcat_buffer_cleared)
        is LogcatBufferClearResult.Failed -> result.output
            .takeIf { it.isNotBlank() }
            ?.let {
                getString(
                    R.string.status_logcat_buffer_clear_failed_with_output,
                    result.message,
                    it,
                )
            }
            ?: result.message
    }
}
