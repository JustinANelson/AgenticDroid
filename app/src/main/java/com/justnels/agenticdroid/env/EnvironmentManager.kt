package com.justnels.agenticdroid.env

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import java.util.UUID
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Manages the available execution environments.
 */
class EnvironmentManager(private val context: Context) {
    private val _environments = mutableStateListOf<EnvironmentConfig>()
    val environments: List<EnvironmentConfig> = _environments

    var activeEnvironment by mutableStateOf<EnvironmentConfig>(EnvironmentConfig.Local)
        private set

    var bootstrapWorkId by mutableStateOf<UUID?>(null)

    fun startBootstrap() {
        WorkManager.getInstance(context).cancelAllWorkByTag("bootstrap")
        val request = OneTimeWorkRequestBuilder<BootstrapWorker>()
            .addTag("bootstrap")
            .build()
        bootstrapWorkId = request.id
        WorkManager.getInstance(context).enqueue(request)
    }

    fun getBootstrapInfo(): Flow<WorkInfo?>? {
        return bootstrapWorkId?.let { WorkManager.getInstance(context).getWorkInfoByIdLiveData(it).asFlow() }
    }

    val bootstrapper = NodeBootstrapper(context)

    var isBootstrapping by mutableStateOf(false)
    var bootstrapStatus by mutableStateOf("")
    var lastBootstrapError by mutableStateOf<String?>(null)
    var bootstrapSuccess by mutableStateOf(false)

    init {
        // Default local environment
        _environments.add(EnvironmentConfig.Local)
        _environments.add(EnvironmentConfig.Node)

        val savedEnvironment = context.getSharedPreferences("environment", Context.MODE_PRIVATE)
            .getString("active", null)
        activeEnvironment = when {
            savedEnvironment == "local" -> EnvironmentConfig.Local
            bootstrapper.isInstalled() -> EnvironmentConfig.Node
            else -> EnvironmentConfig.Local
        }
    }

    fun activateEnvironment(config: EnvironmentConfig) {
        activeEnvironment = config
        val value = when (config) {
            EnvironmentConfig.Local -> "local"
            EnvironmentConfig.Node -> "node"
            is EnvironmentConfig.SSH -> "ssh"
        }
        context.getSharedPreferences("environment", Context.MODE_PRIVATE)
            .edit()
            .putString("active", value)
            .apply()
    }

    fun getExecutionEnvironment(config: EnvironmentConfig): ExecutionEnvironment {
        return when (config) {
            is EnvironmentConfig.Local -> LocalExecutionEnvironment()
            is EnvironmentConfig.SSH -> SSHExecutionEnvironment(config.config)
            is EnvironmentConfig.Node -> {
                if (bootstrapper.isInstalled()) {
                    NodeExecutionEnvironment(context)
                } else {
                    LocalExecutionEnvironment() // Fallback
                }
            }
        }
    }

    fun addSSHEnvironment(config: SSHConfig) {
        _environments.add(EnvironmentConfig.SSH(config))
    }

    fun removeEnvironment(config: EnvironmentConfig) {
        if (config != EnvironmentConfig.Local) {
            _environments.remove(config)
        }
    }
}

sealed class EnvironmentConfig {
    object Local : EnvironmentConfig()
    data class SSH(val config: SSHConfig) : EnvironmentConfig()
    object Node : EnvironmentConfig()
}
