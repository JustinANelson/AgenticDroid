package com.justnels.agenticdroid.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * A basic syntax highlighter for Kotlin/Java keywords.
 */
object SyntaxHighlighter {
    private val keywords = setOf(
        "package", "import", "class", "interface", "fun", "val", "var",
        "if", "else", "when", "for", "while", "return", "public", "private",
        "protected", "override", "data", "object", "try", "catch", "finally",
        "true", "false", "null", "this", "super", "as", "is", "in", "break", "continue"
    )

    private val xmlTags = setOf(
        "manifest", "application", "activity", "intent-filter", "action", "category",
        "resources", "style", "item", "string", "color", "dimen", "drawable"
    )

    fun highlight(code: String, fileName: String = "file.kt"): AnnotatedString {
        val extension = fileName.substringAfterLast('.', "")
        return buildAnnotatedString {
            when (extension) {
                "kt", "java" -> highlightKotlin(code)
                "xml", "html" -> highlightXml(code)
                "json" -> highlightJson(code)
                "md" -> highlightMarkdown(code)
                else -> append(code)
            }
        }
    }

    private fun AnnotatedString.Builder.highlightKotlin(code: String) {
        val words = code.split(Regex("(?<=[\\s.(),:;])|(?=[\\s.(),:;])"))
        words.forEach { word ->
            when {
                keywords.contains(word) -> {
                    withStyle(style = SpanStyle(color = Color(0xFFD73A49), fontWeight = FontWeight.Bold)) {
                        append(word)
                    }
                }
                word.startsWith("\"") && word.endsWith("\"") -> {
                    withStyle(style = SpanStyle(color = Color(0xFF032F62))) {
                        append(word)
                    }
                }
                word.all { it.isDigit() } -> {
                    withStyle(style = SpanStyle(color = Color(0xFF005CC5))) {
                        append(word)
                    }
                }
                word.startsWith("//") -> {
                    withStyle(style = SpanStyle(color = Color(0xFF6A737D))) {
                        append(word)
                    }
                }
                else -> append(word)
            }
        }
    }

    private fun AnnotatedString.Builder.highlightXml(code: String) {
        // Very basic XML highlighting
        val tokens = code.split(Regex("(?<=[<>\"/\\s])|(?=[<>\"/\\s])"))
        tokens.forEach { token ->
            when {
                token == "<" || token == ">" || token == "/" -> {
                    withStyle(style = SpanStyle(color = Color(0xFF22863A))) {
                        append(token)
                    }
                }
                xmlTags.contains(token) -> {
                    withStyle(style = SpanStyle(color = Color(0xFFD73A49))) {
                        append(token)
                    }
                }
                token.startsWith("\"") && token.endsWith("\"") -> {
                    withStyle(style = SpanStyle(color = Color(0xFF032F62))) {
                        append(token)
                    }
                }
                else -> append(token)
            }
        }
    }

    private fun AnnotatedString.Builder.highlightJson(code: String) {
        val tokens = code.split(Regex("(?<=[:{},\"\\s])|(?=[:{},\"\\s])"))
        tokens.forEach { token ->
            when {
                token.startsWith("\"") && token.endsWith("\"") -> {
                    withStyle(style = SpanStyle(color = Color(0xFF032F62))) {
                        append(token)
                    }
                }
                token == ":" || token == "{" || token == "}" || token == "," -> {
                    withStyle(style = SpanStyle(color = Color(0xFFD73A49))) {
                        append(token)
                    }
                }
                token == "true" || token == "false" || token == "null" -> {
                    withStyle(style = SpanStyle(color = Color(0xFF005CC5), fontWeight = FontWeight.Bold)) {
                        append(token)
                    }
                }
                token.all { it.isDigit() } -> {
                    withStyle(style = SpanStyle(color = Color(0xFF005CC5))) {
                        append(token)
                    }
                }
                else -> append(token)
            }
        }
    }

    private fun AnnotatedString.Builder.highlightMarkdown(code: String) {
        val lines = code.split("\n")
        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("#") -> {
                    withStyle(style = SpanStyle(color = Color(0xFF005CC5), fontWeight = FontWeight.Bold)) {
                        append(line)
                    }
                }
                line.startsWith("-") || line.startsWith("*") -> {
                    withStyle(style = SpanStyle(color = Color(0xFFD73A49))) {
                        append(line)
                    }
                }
                line.startsWith("`") && line.endsWith("`") -> {
                    withStyle(style = SpanStyle(color = Color(0xFF032F62), fontFamily = FontFamily.Monospace)) {
                        append(line)
                    }
                }
                else -> append(line)
            }
            if (index < lines.size - 1) append("\n")
        }
    }
}
