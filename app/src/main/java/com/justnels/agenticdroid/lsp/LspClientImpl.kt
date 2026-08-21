package com.justnels.agenticdroid.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture

class LspClientImpl(
    private val onDiagnostics: (PublishDiagnosticsParams) -> Unit
) : LanguageClient {

    override fun telemetryEvent(`object`: Any?) {}

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        onDiagnostics(diagnostics)
    }

    override fun showMessage(messageParams: MessageParams) {
        // Log or show to user
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem>? {
        return null
    }

    override fun logMessage(message: MessageParams) {
        // Log
    }
}
