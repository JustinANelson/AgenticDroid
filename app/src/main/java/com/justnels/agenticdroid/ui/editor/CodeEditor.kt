package com.justnels.agenticdroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CodeEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fileName: String = "file.kt"
) {
    val scrollState = rememberScrollState()
    val visualTransformation = remember(fileName) {
        VisualTransformation { text ->
            TransformedText(
                SyntaxHighlighter.highlight(text.text, fileName),
                OffsetMapping.Identity
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
            .verticalScroll(scrollState)
    ) {
        Row {
            // Line numbers placeholder
            Column(
                modifier = Modifier
                    .width(32.dp)
                    .padding(end = 8.dp)
            ) {
                val lineCount = content.split("\n").size
                (1..lineCount).forEach { lineNumber ->
                    Text(
                        text = lineNumber.toString(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = visualTransformation,
                decorationBox = { innerTextField ->
                    innerTextField()
                }
            )
        }
    }
}
