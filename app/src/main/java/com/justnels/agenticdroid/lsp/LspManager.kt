package com.justnels.agenticdroid.lsp

import android.util.Log
import com.justnels.agenticdroid.env.ExecutionEnvironment
import com.justnels.agenticdroid.env.ProcessSession
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LspManager(
    private val onDiagnostics: (String, org.eclipse.lsp4j.PublishDiagnosticsParams) -> Unit
) {
    private val servers = ConcurrentHashMap<String, LspServerSession>()
    private val executor = Executors.newCachedThreadPool()

    fun startServer(
        extension: String,
        env: ExecutionEnvironment,
        workingDirectory: String
    ) {
        if (servers.containsKey(extension)) return

        val command = when (extension) {
            "py" -> "pyright --langserver"
            else -> return
        }

        executor.execute {
            try {
                val session = env.exec(command, workingDirectory)
                val client = LspClientImpl { params ->
                    onDiagnostics(extension, params)
                }

                val launcher = LSPLauncher.createClientLauncher(client, session.inputStream, session.outputStream)
                val server = launcher.remoteProxy
                val listeningThread = launcher.startListening()

                val serverSession = LspServerSession(server, session, listeningThread)
                servers[extension] = serverSession

                // Initialize
                val params = InitializeParams()
                params.rootUri = "file://$workingDirectory"
                server.initialize(params).get()
                server.initialized()
                
                Log.i("LspManager", "Started $extension server")
            } catch (e: Exception) {
                Log.e("LspManager", "Failed to start $extension server", e)
            }
        }
    }

    fun stopServer(extension: String) {
        servers.remove(extension)?.let {
            it.session.kill()
            it.listeningThread.cancel(true)
        }
    }

    fun onFileOpen(extension: String, uri: String, content: String) {
        val server = servers[extension]?.server ?: return
        val item = TextDocumentItem(uri, extension, 1, content)
        server.textDocumentService.didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams(item))
    }

    fun onFileChange(extension: String, uri: String, content: String) {
        val server = servers[extension]?.server ?: return
        val contentChange = org.eclipse.lsp4j.TextDocumentContentChangeEvent(content)
        val params = org.eclipse.lsp4j.DidChangeTextDocumentParams(
            org.eclipse.lsp4j.VersionedTextDocumentIdentifier(uri, 2),
            listOf(contentChange)
        )
        server.textDocumentService.didChange(params)
    }

    fun requestCompletion(
        extension: String,
        uri: String,
        line: Int,
        character: Int
    ): java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.CompletionItem>, org.eclipse.lsp4j.CompletionList>>? {
        val server = servers[extension]?.server ?: return null
        val params = org.eclipse.lsp4j.CompletionParams(
            org.eclipse.lsp4j.TextDocumentIdentifier(uri),
            org.eclipse.lsp4j.Position(line, character)
        )
        return server.textDocumentService.completion(params)
    }

    fun requestDefinition(
        extension: String,
        uri: String,
        line: Int,
        character: Int
    ): java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.Location>, List<org.eclipse.lsp4j.LocationLink>>>? {
        val server = servers[extension]?.server ?: return null
        val params = org.eclipse.lsp4j.DefinitionParams(
            org.eclipse.lsp4j.TextDocumentIdentifier(uri),
            org.eclipse.lsp4j.Position(line, character)
        )
        return server.textDocumentService.definition(params)
    }

    fun requestReferences(
        extension: String,
        uri: String,
        line: Int,
        character: Int
    ): java.util.concurrent.CompletableFuture<List<org.eclipse.lsp4j.Location>>? {
        val server = servers[extension]?.server ?: return null
        val params = org.eclipse.lsp4j.ReferenceParams(
            org.eclipse.lsp4j.TextDocumentIdentifier(uri),
            org.eclipse.lsp4j.Position(line, character),
            org.eclipse.lsp4j.ReferenceContext(false)
        )
        return server.textDocumentService.references(params)
    }

    fun stopAll() {
        servers.keys.forEach { stopServer(it) }
    }

    private data class LspServerSession(
        val server: LanguageServer,
        val session: ProcessSession,
        val listeningThread: java.util.concurrent.Future<*>
    )
}
