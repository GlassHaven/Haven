package sh.haven.feature.editor

import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import sh.haven.core.ui.InteropFocusGuard
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

sealed interface EditorState {
    data object Idle : EditorState
    data object Loading : EditorState
    data class Loaded(
        val content: String,
        val fileName: String,
        val filePath: String,
        val charset: java.nio.charset.Charset = Charsets.UTF_8,
    ) : EditorState
    data class Error(val message: String) : EditorState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    wordWrap: Boolean,
    saving: Boolean = false,
    terminalBackground: Int = 0xFF1A1A2E.toInt(),
    terminalForeground: Int = 0xFF00E676.toInt(),
    onToggleWordWrap: () -> Unit,
    onSave: ((String) -> Unit)? = null,
    onBack: () -> Unit,
) {
    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorColumn by remember { mutableIntStateOf(1) }
    var isDirty by remember { mutableStateOf(false) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }

    // Reset dirty state when new content loads
    LaunchedEffect(state) {
        if (state is EditorState.Loaded) isDirty = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (state) {
                        is EditorState.Loaded -> {
                            if (isDirty) "\u2022 ${state.fileName}" else state.fileName
                        }
                        is EditorState.Loading -> stringResource(R.string.editor_loading)
                        else -> stringResource(R.string.editor_title)
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (state is EditorState.Loaded) {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.editor_find))
                        }
                        IconButton(
                            onClick = { editorRef?.undo() },
                            enabled = canUndo,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.editor_undo))
                        }
                        IconButton(
                            onClick = { editorRef?.redo() },
                            enabled = canRedo,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.editor_redo))
                        }
                        if (onSave != null) {
                            if (saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).padding(2.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                IconButton(
                                    onClick = {
                                        editorRef?.let { onSave(it.text.toString()) }
                                    },
                                    enabled = isDirty,
                                ) {
                                    Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.editor_save))
                                }
                            }
                        }
                    }
                    IconButton(onClick = onToggleWordWrap) {
                        Icon(
                            Icons.AutoMirrored.Filled.WrapText,
                            contentDescription = stringResource(R.string.editor_word_wrap),
                            tint = if (wordWrap) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state is EditorState.Loaded) {
                Text(
                    text = stringResource(R.string.editor_line, cursorLine, cursorColumn),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = 12.dp,
                        vertical = 4.dp,
                    ),
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            AnimatedVisibility(visible = showSearch && state is EditorState.Loaded) {
                FindReplaceBar(
                    editor = editorRef,
                    onClose = {
                        showSearch = false
                        editorRef?.searcher?.stopSearch()
                    },
                )
            }

            AnimatedContent(
                targetState = state,
                modifier = Modifier.fillMaxSize(),
                label = "editor-state",
                contentKey = { it::class },
            ) { current ->
                when (current) {
                    is EditorState.Idle -> Box(Modifier.fillMaxSize())
                    is EditorState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is EditorState.Error -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.editor_failed, current.message),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    is EditorState.Loaded -> {
                        EditorContent(
                            content = current.content,
                            fileName = current.fileName,
                            wordWrap = wordWrap,
                            editable = onSave != null,
                            terminalBackground = terminalBackground,
                            terminalForeground = terminalForeground,
                            onEditorCreated = { editorRef = it },
                            onCursorChange = { line, col ->
                                cursorLine = line
                                cursorColumn = col
                            },
                            onContentChanged = {
                                isDirty = true
                                canUndo = editorRef?.canUndo() ?: false
                                canRedo = editorRef?.canRedo() ?: false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FindReplaceBar(
    editor: CodeEditor?,
    onClose: () -> Unit,
) {
    var searchText by rememberSaveable { mutableStateOf("") }
    var replaceText by rememberSaveable { mutableStateOf("") }
    var useRegex by rememberSaveable { mutableStateOf(false) }
    var showReplace by rememberSaveable { mutableStateOf(false) }
    var matchCount by remember { mutableIntStateOf(0) }
    var matchIndex by remember { mutableIntStateOf(0) }

    fun doSearch() {
        if (editor == null || searchText.isEmpty()) {
            editor?.searcher?.stopSearch()
            matchCount = 0
            matchIndex = 0
            return
        }
        val type = if (useRegex) {
            EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
        } else {
            EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        editor.searcher.search(searchText, EditorSearcher.SearchOptions(type, true))
        matchCount = editor.searcher.matchedPositionCount
        matchIndex = if (matchCount > 0) editor.searcher.currentMatchedPositionIndex + 1 else 0
    }

    LaunchedEffect(searchText, useRegex) { doSearch() }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text(stringResource(R.string.editor_find)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    editor?.searcher?.gotoNext()
                    matchIndex = (editor?.searcher?.currentMatchedPositionIndex ?: -1) + 1
                }),
                trailingIcon = if (matchCount > 0 || searchText.isNotEmpty()) {
                    {
                        Text(
                            if (searchText.isEmpty()) "" else "$matchIndex/$matchCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else null,
            )
            IconButton(onClick = {
                editor?.searcher?.gotoPrevious()
                matchIndex = (editor?.searcher?.currentMatchedPositionIndex ?: -1) + 1
            }) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.editor_find_prev))
            }
            IconButton(onClick = {
                editor?.searcher?.gotoNext()
                matchIndex = (editor?.searcher?.currentMatchedPositionIndex ?: -1) + 1
            }) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.editor_find_next))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_close_search))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = useRegex,
                onClick = { useRegex = !useRegex },
                label = { Text(stringResource(R.string.editor_regex)) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = showReplace,
                onClick = { showReplace = !showReplace },
                label = { Text(stringResource(R.string.editor_replace)) },
            )
        }

        AnimatedVisibility(visible = showReplace) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    placeholder = { Text(stringResource(R.string.editor_replace)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        editor?.searcher?.replaceThis(replaceText)
                        doSearch()
                    },
                    enabled = editor?.searcher?.isMatchedPositionSelected == true,
                ) {
                    Text(stringResource(R.string.editor_replace_one))
                }
                TextButton(
                    onClick = {
                        editor?.searcher?.replaceAll(replaceText)
                        doSearch()
                    },
                    enabled = matchCount > 0,
                ) {
                    Text(stringResource(R.string.editor_replace_all))
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun EditorContent(
    content: String,
    fileName: String,
    wordWrap: Boolean,
    editable: Boolean,
    terminalBackground: Int,
    terminalForeground: Int,
    onEditorCreated: (CodeEditor) -> Unit,
    onCursorChange: (line: Int, column: Int) -> Unit,
    onContentChanged: () -> Unit,
) {
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()

    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }

    // Compose tears the interop view down while this screen is a backgrounded
    // page of the app's pager; a focused editor at that moment is the
    // HyperOS/Android 16 warm-return crash. See [InteropFocusGuard].
    val focusGuard = remember { InteropFocusGuard() }

    LaunchedEffect(wordWrap) {
        editorRef?.setWordwrap(wordWrap)
    }

    DisposableEffect(Unit) {
        onDispose { editorRef?.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextMateSupport.init(ctx)

            HavenCodeEditor(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                TextMateSupport.applyTheme(this, terminalBackground, terminalForeground)

                val scheme = colorScheme
                scheme.setColor(EditorColorScheme.SELECTION_INSERT, cursorColor)
                scheme.setColor(EditorColorScheme.SELECTION_HANDLE, cursorColor)

                setTextSize(14f)
                isEditable = editable
                setWordwrap(wordWrap)
                isLineNumberEnabled = true
                setText(content)

                TextMateSupport.applyLanguage(this, fileName)

                editorRef = this
                onEditorCreated(this)
            }
        },
        // Compose calls this immediately before
        // AndroidViewHolder.removeAllViewsInLayout() — same stack frame — so
        // it is the one point where the editor is guaranteed to be out of the
        // focus chain at removal time, whatever the device's frame timing.
        onReset = { editor -> focusGuard.onReset(editor) },
        onRelease = { editor -> focusGuard.onReset(editor) },
        update = { editor ->
            // Refreshed rather than captured in the factory: supplying onReset
            // makes this a reusable node, and a reused node does not re-run
            // the factory.
            editor.onCursorChange = onCursorChange
            editor.onContentChanged = onContentChanged
            if (editorRef !== editor) {
                // The factory did not run for this composition — the node was
                // reused. Re-establish what it would have set.
                editorRef = editor
                editor.isEditable = editable
                editor.setWordwrap(wordWrap)
                onEditorCreated(editor)
            }
            val currentText = editor.text.toString()
            if (currentText != content) {
                editor.setText(content)
            }
            focusGuard.onUpdate(editor)
        },
    )
}
