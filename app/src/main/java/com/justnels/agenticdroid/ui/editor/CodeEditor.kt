package com.justnels.agenticdroid.ui.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CodeEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fileName: String = "file.kt"
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    
    var textFieldValue by remember(content) {
        mutableStateOf(TextFieldValue(text = content, selection = TextRange(content.length)))
    }

    val visualTransformation = remember(fileName) {
        VisualTransformation { text ->
            TransformedText(
                SyntaxHighlighter.highlight(text.text, fileName),
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
                            modifier = Modifier.padding(end = 12.dp, bottom = 2.dp)
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
                            textFieldValue = it
                            if (it.text != content) {
                                onContentChange(it.text)
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
                }
            }
        }

        // Accessory Row for Mobile Coding
        EditorAccessoryRow(
            onKeyClick = { char ->
                val currentText = textFieldValue.text
                val selection = textFieldValue.selection
                val newText = currentText.substring(0, selection.start) + char + currentText.substring(selection.end)
                val newSelection = TextRange(selection.start + char.length)
                
                textFieldValue = TextFieldValue(newText, newSelection)
                onContentChange(newText)
            }
        )
    }
}

@Composable
fun EditorAccessoryRow(
    onKeyClick: (String) -> Unit
) {
    val keys = listOf("{", "}", "(", ")", "[", "]", "<", ">", ";", "=", "\"", "'", ":", ".", "/", "\\", "|", "&", "!", "?")

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
        keys.forEach { key ->
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
