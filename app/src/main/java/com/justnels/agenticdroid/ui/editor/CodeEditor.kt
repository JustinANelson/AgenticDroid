package com.justnels.agenticdroid.ui.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INDENT_UNIT = "    "

private fun startOfLine(text: String, offset: Int): Int {
    if (offset <= 0) return 0
    val idx = text.lastIndexOf('\n', offset - 1)
    return if (idx == -1) 0 else idx + 1
}

private fun endOfLine(text: String, offset: Int): Int {
    val idx = text.indexOf('\n', offset)
    return if (idx == -1) text.length else idx
}

/** Indents every line the selection touches (or just the current line, if collapsed). */
private fun indentLines(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val lineStart = startOfLine(text, value.selection.min)
    val lineEnd = endOfLine(text, value.selection.max)
    val affected = text.substring(lineStart, lineEnd)
    val indented = affected.split("\n").joinToString("\n") { INDENT_UNIT + it }
    val newText = text.substring(0, lineStart) + indented + text.substring(lineEnd)
    val newSelStart = value.selection.min + INDENT_UNIT.length
    val newSelEnd = value.selection.max + (indented.length - affected.length)
    return TextFieldValue(newText, TextRange(newSelStart, newSelEnd))
}

/** Removes up to one indent unit (or a leading tab) from every line the selection touches. */
private fun dedentLines(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val lineStart = startOfLine(text, value.selection.min)
    val lineEnd = endOfLine(text, value.selection.max)
    val affected = text.substring(lineStart, lineEnd)
    var removedFromFirstLine = 0
    val lines = affected.split("\n")
    val dedented = lines.mapIndexed { index, line ->
        val removeCount = when {
            line.startsWith(INDENT_UNIT) -> INDENT_UNIT.length
            line.startsWith("\t") -> 1
            else -> line.takeWhile { it == ' ' }.length.coerceAtMost(INDENT_UNIT.length)
        }
        if (index == 0) removedFromFirstLine = removeCount
        line.drop(removeCount)
    }.joinToString("\n")
    val newText = text.substring(0, lineStart) + dedented + text.substring(lineEnd)
    val removedTotal = affected.length - dedented.length
    val newSelStart = (value.selection.min - removedFromFirstLine).coerceIn(lineStart, lineStart + dedented.length)
    val newSelEnd = (value.selection.max - removedTotal).coerceAtLeast(newSelStart)
    return TextFieldValue(newText, TextRange(newSelStart, newSelEnd))
}

private fun moveCursorHorizontally(value: TextFieldValue, delta: Int): TextFieldValue {
    val newPos = (value.selection.end + delta).coerceIn(0, value.text.length)
    return value.copy(selection = TextRange(newPos))
}

private fun moveCursorVertically(value: TextFieldValue, lineDelta: Int): TextFieldValue {
    val text = value.text
    val offset = value.selection.end
    val curLineStart = startOfLine(text, offset)
    val column = offset - curLineStart
    val targetLineStart = if (lineDelta < 0) {
        if (curLineStart == 0) return value
        startOfLine(text, curLineStart - 1)
    } else {
        val curLineEnd = endOfLine(text, offset)
        if (curLineEnd >= text.length) return value
        curLineEnd + 1
    }
    val targetLineEnd = endOfLine(text, targetLineStart)
    val newOffset = (targetLineStart + column).coerceAtMost(targetLineEnd)
    return value.copy(selection = TextRange(newOffset))
}

private fun moveToLineStart(value: TextFieldValue): TextFieldValue =
    value.copy(selection = TextRange(startOfLine(value.text, value.selection.end)))

private fun moveToLineEnd(value: TextFieldValue): TextFieldValue =
    value.copy(selection = TextRange(endOfLine(value.text, value.selection.end)))

@Composable
fun CodeEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fileName: String = "file.kt",
    fileId: String = fileName, // Use fileName or path as stable ID
    diagnostics: List<org.eclipse.lsp4j.Diagnostic> = emptyList(),
    completionResults: List<org.eclipse.lsp4j.CompletionItem> = emptyList(),
    onTriggerCompletion: (Int, Int) -> Unit = { _, _ -> },
    onClearCompletion: () -> Unit = {},
    pendingScrollToLine: Int? = null,
    onLineScrolled: () -> Unit = {},
    onGoToDefinition: (Int, Int) -> Unit = { _, _ -> },
    onFindUsages: (Int, Int) -> Unit = { _, _ -> },
    usagesResults: List<org.eclipse.lsp4j.Location> = emptyList(),
    onClearUsages: () -> Unit = {},
    onOpenFile: (String) -> Unit = {}
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current
    val lineHeightDp = with(density) { 20.sp.toDp() }
    val lineHeightPx = with(density) { 20.sp.toPx() }
    
    // Only reset textFieldValue when the file itself changes (different ID)
    var textFieldValue by remember(fileId) {
        mutableStateOf(TextFieldValue(text = content, selection = TextRange(content.length)))
    }

    // Sync externally changed content (e.g. from an agent or save)
    // while avoiding cursor jumps for user typing.
    LaunchedEffect(content) {
        if (content != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = content)
        }
    }

    // Undo/redo history for the accessory row's Undo/Redo keys. A checkpoint is the value
    // *before* an edit, so undo restores it and redo re-applies the edit that followed.
    // Continuous typing coalesces into one checkpoint per pause (typingBurstActive) instead
    // of one per keystroke, matching how desktop editors group undo steps; every accessory-
    // row action (bracket insert, indent, arrow-key move doesn't count) is always its own
    // checkpoint since it's a single deliberate action, not a keystroke stream.
    val undoStack = remember(fileId) { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember(fileId) { mutableStateListOf<TextFieldValue>() }
    var typingBurstActive by remember(fileId) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var burstEndJob by remember(fileId) { mutableStateOf<Job?>(null) }

    fun pushCheckpoint(previousValue: TextFieldValue) {
        undoStack.add(previousValue)
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun applyEdit(newValue: TextFieldValue) {
        textFieldValue = newValue
        if (newValue.text != content) onContentChange(newValue.text)
    }

    // Handle pending scroll to line
    LaunchedEffect(pendingScrollToLine) {
        pendingScrollToLine?.let { line ->
            val y = (line * lineHeightPx).toInt()
            verticalScrollState.animateScrollTo(y)
            onLineScrolled()
        }
    }

    val visualTransformation = remember(fileName, diagnostics) {
        VisualTransformation { text ->
            TransformedText(
                SyntaxHighlighter.highlight(text.text, fileName, diagnostics),
                OffsetMapping.Identity
            )
        }
    }

    val lines = remember(content) { content.split("\n") }
    val lineCount = lines.size

    Column(modifier = modifier.fillMaxSize()) {
        // Editor Surface
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E)) // VS Code-like dark background
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Line Numbers Gutter
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(48.dp)
                        .background(Color(0xFF252526))
                        .verticalScroll(verticalScrollState)
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    (1..lineCount).forEach { lineNumber ->
                        Text(
                            text = lineNumber.toString(),
                            color = Color(0xFF858585),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            modifier = Modifier.padding(end = 12.dp, bottom = 2.dp).height(lineHeightDp)
                        )
                    }
                }

                // Main Text Field with horizontal scrolling
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScrollState)
                        .verticalScroll(verticalScrollState)
                        .padding(top = 8.dp, start = 12.dp, end = 16.dp, bottom = 32.dp)
                ) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = {
                            val oldText = textFieldValue.text
                            val previousValue = textFieldValue
                            textFieldValue = it
                            if (it.text != content) {
                                onContentChange(it.text)

                                if (it.text != oldText) {
                                    if (!typingBurstActive) {
                                        pushCheckpoint(previousValue)
                                        typingBurstActive = true
                                    }
                                    burstEndJob?.cancel()
                                    burstEndJob = coroutineScope.launch {
                                        delay(600)
                                        typingBurstActive = false
                                    }
                                }

                                // Detect trigger characters
                                if (it.text.length > oldText.length && it.selection.start > 0) {
                                    val added = it.text.substring(it.selection.start - 1, it.selection.start)
                                    if (added == "." || added == "(") {
                                        val sub = it.text.substring(0, it.selection.start)
                                        val l = sub.count { char -> char == '\n' }
                                        val c = sub.substringAfterLast('\n').length
                                        onTriggerCompletion(l, c)
                                    } else if (completionResults.isNotEmpty()) {
                                        // Still filter or hide if space/other is typed?
                                        // Simplified: clear if not alphanumeric/trigger
                                        if (!added[0].isLetterOrDigit() && added != "_") {
                                            onClearCompletion()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.width(IntrinsicSize.Max).defaultMinSize(minWidth = 2000.dp), // Allow horizontal scrolling
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = Color.White,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(Color(0xFF007ACC)),
                        visualTransformation = visualTransformation,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            autoCorrectEnabled = false
                        ),
                        decorationBox = { innerTextField ->
                            innerTextField()
                        }
                    )
                    
                    if (completionResults.isNotEmpty()) {
                        CompletionPopup(
                            results = completionResults,
                            onSelect = { item ->
                                val label = item.insertText ?: item.label
                                val currentText = textFieldValue.text
                                val selection = textFieldValue.selection
                                val newText = currentText.substring(0, selection.start) + label + currentText.substring(selection.end)
                                val newSelection = TextRange(selection.start + label.length)
                                textFieldValue = TextFieldValue(newText, newSelection)
                                onContentChange(newText)
                                onClearCompletion()
                            },
                            onDismiss = onClearCompletion
                        )
                    }

                    if (usagesResults.isNotEmpty()) {
                        UsagesPopup(
                            results = usagesResults,
                            onSelect = { loc ->
                                onOpenFile(loc.uri.removePrefix("file://"))
                                onGoToDefinition(loc.range.start.line, loc.range.start.character)
                                onClearUsages()
                            },
                            onDismiss = onClearUsages
                        )
                    }
                }
            }
        }

        // Accessory Row for Mobile Coding
        EditorAccessoryRow(
            onKeyClick = { char ->
                when (char) {
                    "COMP" -> {
                        val sub = textFieldValue.text.substring(0, textFieldValue.selection.start)
                        val l = sub.count { it == '\n' }
                        val c = sub.substringAfterLast('\n').length
                        onTriggerCompletion(l, c)
                    }
                    "DEF" -> {
                        val sub = textFieldValue.text.substring(0, textFieldValue.selection.start)
                        val l = sub.count { it == '\n' }
                        val c = sub.substringAfterLast('\n').length
                        onGoToDefinition(l, c)
                    }
                    "USAG" -> {
                        val sub = textFieldValue.text.substring(0, textFieldValue.selection.start)
                        val l = sub.count { it == '\n' }
                        val c = sub.substringAfterLast('\n').length
                        onFindUsages(l, c)
                    }
                    "UNDO" -> {
                        undoStack.removeLastOrNull()?.let { previous ->
                            redoStack.add(textFieldValue)
                            typingBurstActive = false
                            textFieldValue = previous
                            onContentChange(previous.text)
                        }
                    }
                    "REDO" -> {
                        redoStack.removeLastOrNull()?.let { next ->
                            undoStack.add(textFieldValue)
                            typingBurstActive = false
                            textFieldValue = next
                            onContentChange(next.text)
                        }
                    }
                    "TAB" -> {
                        pushCheckpoint(textFieldValue)
                        typingBurstActive = false
                        val selection = textFieldValue.selection
                        val newText = textFieldValue.text.substring(0, selection.start) + INDENT_UNIT + textFieldValue.text.substring(selection.end)
                        applyEdit(TextFieldValue(newText, TextRange(selection.start + INDENT_UNIT.length)))
                    }
                    "INDENT" -> {
                        pushCheckpoint(textFieldValue)
                        typingBurstActive = false
                        applyEdit(indentLines(textFieldValue))
                    }
                    "DEDENT" -> {
                        pushCheckpoint(textFieldValue)
                        typingBurstActive = false
                        applyEdit(dedentLines(textFieldValue))
                    }
                    "LEFT" -> textFieldValue = moveCursorHorizontally(textFieldValue, -1)
                    "RIGHT" -> textFieldValue = moveCursorHorizontally(textFieldValue, 1)
                    "UP" -> textFieldValue = moveCursorVertically(textFieldValue, -1)
                    "DOWN" -> textFieldValue = moveCursorVertically(textFieldValue, 1)
                    "HOME" -> textFieldValue = moveToLineStart(textFieldValue)
                    "END" -> textFieldValue = moveToLineEnd(textFieldValue)
                    else -> {
                        pushCheckpoint(textFieldValue)
                        typingBurstActive = false
                        val currentText = textFieldValue.text
                        val selection = textFieldValue.selection
                        val newText = currentText.substring(0, selection.start) + char + currentText.substring(selection.end)
                        val newSelection = TextRange(selection.start + char.length)
                        applyEdit(TextFieldValue(newText, newSelection))
                    }
                }
            },
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }
}

@Composable
fun UsagesPopup(
    results: List<org.eclipse.lsp4j.Location>,
    onSelect: (org.eclipse.lsp4j.Location) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -60)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 250.dp)
                .padding(horizontal = 16.dp),
            color = Color(0xFF252526),
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, Color(0xFF454545)),
            shape = MaterialTheme.shapes.small
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Usages", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                    }
                }
                HorizontalDivider(color = Color(0xFF454545))
                LazyColumn {
                    items(results) { loc ->
                        UsageItemRow(loc, onClick = { onSelect(loc) })
                    }
                }
            }
        }
    }
}

@Composable
fun UsageItemRow(
    loc: org.eclipse.lsp4j.Location,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = loc.uri.substringAfterLast('/'),
                color = Color(0xFF569CD6),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Line ${loc.range.start.line + 1}, Col ${loc.range.start.character}",
                color = Color(0xFF858585),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun CompletionPopup(
    results: List<org.eclipse.lsp4j.CompletionItem>,
    onSelect: (org.eclipse.lsp4j.CompletionItem) -> Unit,
    onDismiss: () -> Unit
) {
    // For now, fixed position or near bottom since calculating precise cursor pixel pos in BasicTextField is hard
    androidx.compose.ui.window.Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.BottomStart,
        offset = androidx.compose.ui.unit.IntOffset(0, -60) // Above accessory row
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .padding(horizontal = 16.dp),
            color = Color(0xFF252526),
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, Color(0xFF454545)),
            shape = MaterialTheme.shapes.small
        ) {
            LazyColumn {
                items(results) { item ->
                    CompletionItemRow(item, onClick = { onSelect(item) })
                }
            }
        }
    }
}

@Composable
fun CompletionItemRow(
    item: org.eclipse.lsp4j.CompletionItem,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (item.kind) {
                org.eclipse.lsp4j.CompletionItemKind.Function, org.eclipse.lsp4j.CompletionItemKind.Method -> "ƒ"
                org.eclipse.lsp4j.CompletionItemKind.Variable -> "v"
                org.eclipse.lsp4j.CompletionItemKind.Class -> "C"
                org.eclipse.lsp4j.CompletionItemKind.Module -> "M"
                org.eclipse.lsp4j.CompletionItemKind.Keyword -> "K"
                else -> "•"
            }
            Text(
                text = icon,
                color = Color(0xFF007ACC),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(20.dp)
            )
            Text(
                text = item.label,
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.detail != null) {
                Text(
                    text = item.detail,
                    color = Color(0xFF858585),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class AccessoryIconKey(
    val id: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val description: String,
    val enabled: Boolean = true
)

@Composable
fun EditorAccessoryRow(
    onKeyClick: (String) -> Unit,
    canUndo: Boolean = false,
    canRedo: Boolean = false
) {
    val iconKeys = listOf(
        AccessoryIconKey("UNDO", Icons.AutoMirrored.Filled.Undo, "Undo", enabled = canUndo),
        AccessoryIconKey("REDO", Icons.AutoMirrored.Filled.Redo, "Redo", enabled = canRedo),
        AccessoryIconKey("TAB", Icons.Filled.KeyboardTab, "Insert tab"),
        AccessoryIconKey("DEDENT", Icons.AutoMirrored.Filled.FormatIndentDecrease, "Dedent line(s)"),
        AccessoryIconKey("INDENT", Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent line(s)"),
        AccessoryIconKey("HOME", Icons.Filled.FirstPage, "Line start"),
        AccessoryIconKey("LEFT", Icons.Filled.KeyboardArrowLeft, "Cursor left"),
        AccessoryIconKey("UP", Icons.Filled.KeyboardArrowUp, "Cursor up"),
        AccessoryIconKey("DOWN", Icons.Filled.KeyboardArrowDown, "Cursor down"),
        AccessoryIconKey("RIGHT", Icons.Filled.KeyboardArrowRight, "Cursor right"),
        AccessoryIconKey("END", Icons.Filled.LastPage, "Line end")
    )
    val symbolKeys = listOf(
        "COMP", "DEF", "USAG", "{", "}", "(", ")", "[", "]", "<", ">", ";", "=", "\"", "'", ":",
        ".", "/", "\\", "|", "&", "!", "?", "->", "=>", "$"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF2D2D2D))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        iconKeys.forEach { key ->
            Surface(
                onClick = { onKeyClick(key.id) },
                color = if (key.enabled) Color(0xFF3C3C3C) else Color(0xFF2D2D2D),
                shape = MaterialTheme.shapes.small
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = key.icon,
                        contentDescription = key.description,
                        tint = if (key.enabled) Color.White else Color(0xFF6E6E6E),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        VerticalDivider(modifier = Modifier.height(28.dp), color = Color(0xFF454545))
        symbolKeys.forEach { key ->
            Surface(
                onClick = { onKeyClick(key) },
                color = Color(0xFF3C3C3C),
                shape = MaterialTheme.shapes.small
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = key,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
