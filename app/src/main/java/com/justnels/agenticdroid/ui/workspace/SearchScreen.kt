package com.justnels.agenticdroid.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.workspace.SearchResult

@Composable
fun SearchScreen(
    onSearch: (String) -> List<SearchResult>,
    onResultSelected: (SearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { 
                query = it
                if (it.length >= 3) {
                    results = onSearch(it)
                }
            },
            label = { Text("Search in Files") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results) { result ->
                SearchResultItem(result, onResultSelected)
            }
        }
    }
}

@Composable
fun SearchResultItem(
    result: SearchResult,
    onResultSelected: (SearchResult) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onResultSelected(result) }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = result.path.substringAfterLast("/"),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Line ${result.lineNumber}: ${result.lineContent}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
