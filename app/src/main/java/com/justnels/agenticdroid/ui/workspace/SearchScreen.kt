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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(
    onSearch: (String, Boolean) -> List<SearchResult>,
    onResultSelected: (SearchResult) -> Unit,
    projectOnlyAvailable: Boolean,
    searchProjectOnly: Boolean,
    onSetSearchProjectOnly: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // A project-only search with no project selected has nothing to scope to - fall back
    // to the whole workspace rather than searching nothing.
    val effectiveProjectOnly = searchProjectOnly && projectOnlyAvailable

    LaunchedEffect(query, effectiveProjectOnly) {
        if (query.length < 3) {
            results = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        delay(300)
        isSearching = true
        results = withContext(Dispatchers.IO) { onSearch(query, effectiveProjectOnly) }
        isSearching = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search in Files") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = effectiveProjectOnly,
                onClick = { onSetSearchProjectOnly(true) },
                enabled = projectOnlyAvailable,
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) {
                Text("This Project", style = MaterialTheme.typography.labelSmall)
            }
            SegmentedButton(
                selected = !effectiveProjectOnly,
                onClick = { onSetSearchProjectOnly(false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) {
                Text("Entire Workspace", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isSearching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

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
