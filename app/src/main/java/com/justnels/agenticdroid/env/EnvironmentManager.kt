package com.justnels.agenticdroid.env

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.Data
import androidx.work.Constraints
import androidx.work.NetworkType
import java.util.UUID
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.justnels.agenticdroid.auth.CredentialManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Manages the available execution environments.
 */
class EnvironmentManager(private val context: Context) {
    companion object {
        private const val BOOTSTRAP_WORK_NAME = "bootstrap"
        private const val BOOTSTRAP_TAG = "bootstrap"
        private const val BOOTSTRAP_WORK_ID_KEY = "bootstrap_work_id"
        private const val WIFI_ONLY_KEY = "wifi_only_downloads"
    }

    private val _environments = mutableStateListOf<EnvironmentConfig>()
    val environments: List<EnvironmentConfig> = _environments
    private val prefs = context.getSharedPreferences("environment", Context.MODE_PRIVATE)
    private val credentials = CredentialManager(context)
    private val instances = mutableMapOf<EnvironmentConfig, ExecutionEnvironment>()

    var activeEnvironment by mutableStateOf<EnvironmentConfig>(EnvironmentConfig.Local)
        private set

    var bootstrapWorkId by mutableStateOf<UUID?>(null)

    /**
     * The toolchain download is a multi-hundred-MB, multi-minute transfer - defaulting to
     * requiring an unmetered connection protects a user's mobile data plan from an
     * accidental large download; they can opt out per-install.
     */
    var wifiOnlyDownloads: Boolean by mutableStateOf(prefs.getBoolean(WIFI_ONLY_KEY, true))
        private set

    fun updateWifiOnlyDownloads(enabled: Boolean) {
        wifiOnlyDownloads = enabled
        prefs.edit { putBoolean(WIFI_ONLY_KEY, enabled) }
    }

    /**
     * Starting bootstrap through a single named unique-work slot (rather than a manual
     * cancel-then-enqueue against the "bootstrap" tag) avoids a race where a stale
     * in-flight worker could still be writing into the toolchain directory after a new
     * one begins clearing/rewriting it - WorkManager guarantees the REPLACE cancellation
     * finishes before the new run starts.
     */
    fun startBootstrap(groups: Set<RunnerPackageGroup> = setOf(RunnerPackageGroup.CORE)) {
        val request = OneTimeWorkRequestBuilder<BootstrapWorker>()
            .addTag(BOOTSTRAP_TAG)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                Data.Builder()
                    .putStringArray(BootstrapWorker.GROUPS_KEY, groups.map { it.name }.toTypedArray())
                    .build()
            )
            .build()
        bootstrapWorkId = request.id
        persistBootstrapWorkId(request.id)
        WorkManager.getInstance(context).enqueueUniqueWork(BOOTSTRAP_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun getBootstrapInfo(): Flow<WorkInfo?>? {
        return bootstrapWorkId?.let { WorkManager.getInstance(context).getWorkInfoByIdLiveData(it).asFlow() }
    }

    fun dismissBootstrap() {
        bootstrapWorkId = null
        persistBootstrapWorkId(null)
    }

    private fun persistBootstrapWorkId(id: UUID?) {
        prefs.edit {
            if (id != null) putString(BOOTSTRAP_WORK_ID_KEY, id.toString()) else remove(BOOTSTRAP_WORK_ID_KEY)
        }
    }

    /**
     * Re-attaches to a bootstrap that is still running (or that just failed) after the
     * app process was killed and restarted - WorkManager itself survives process death,
     * but the in-memory bootstrapWorkId does not, so without this a killed-and-relaunched
     * app would silently lose visibility into an in-progress or freshly failed download.
     * Performs a blocking WorkManager query; call from a background dispatcher.
     */
    fun reattachBootstrapWork() {
        val idString = prefs.getString(BOOTSTRAP_WORK_ID_KEY, null) ?: return
        val id = runCatching { UUID.fromString(idString) }.getOrNull() ?: run {
            persistBootstrapWorkId(null)
            return
        }
        val info = runCatching { WorkManager.getInstance(context).getWorkInfoById(id).get() }.getOrNull()
        if (info == null || info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.CANCELLED) {
            persistBootstrapWorkId(null)
            return
        }
        bootstrapWorkId = id
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
        restoreSSHProfiles().forEach(_environments::add)

        val savedEnvironment = prefs.getString("active", null)
        activeEnvironment = when (savedEnvironment) {
            "local" -> EnvironmentConfig.Local
            "node" -> if (bootstrapper.isInstalled()) EnvironmentConfig.Node else EnvironmentConfig.Local
            else -> _environments.filterIsInstance<EnvironmentConfig.SSH>()
                .firstOrNull { profileId(it.config) == savedEnvironment?.removePrefix("ssh:") }
                ?: EnvironmentConfig.Local
        }
    }

    fun activateEnvironment(config: EnvironmentConfig) {
        // Installation state can change between Compose rendering a card and dispatching
        // its click (notably when a provisioning-version bump invalidates CORE). Treat a
        // stale activation as a no-op; an expected UI race must never crash the main thread.
        if (config is EnvironmentConfig.Node && !bootstrapper.isInstalled()) return
        activeEnvironment = config
        val value = when (config) {
            EnvironmentConfig.Local -> "local"
            EnvironmentConfig.Node -> "node"
            is EnvironmentConfig.SSH -> "ssh:${profileId(config.config)}"
        }
        prefs.edit { putString("active", value) }
    }

    fun getExecutionEnvironment(config: EnvironmentConfig): ExecutionEnvironment {
        return instances.getOrPut(config) {
            when (config) {
            is EnvironmentConfig.Local -> LocalExecutionEnvironment()
            is EnvironmentConfig.SSH -> SSHExecutionEnvironment(context, config.config)
            is EnvironmentConfig.Node -> {
                if (bootstrapper.isInstalled()) {
                    NodeExecutionEnvironment(context)
                } else {
                    throw IllegalStateException("Node environment is not installed")
                }
            }
            }
        }
    }

    fun addSSHEnvironment(config: SSHConfig) {
        val environment = EnvironmentConfig.SSH(config)
        _environments.filterIsInstance<EnvironmentConfig.SSH>()
            .filter { profileId(it.config) == profileId(config) }
            .forEach { existing ->
                _environments.remove(existing)
                instances.remove(existing)?.close()
            }
        _environments.add(environment)
        saveSSHProfiles()
    }

    fun removeEnvironment(config: EnvironmentConfig) {
        if (config != EnvironmentConfig.Local) {
            _environments.remove(config)
            instances.remove(config)?.close()
            if (activeEnvironment == config) activateEnvironment(EnvironmentConfig.Local)
            if (config is EnvironmentConfig.SSH) {
                credentials.clearCredential("ssh_password_${profileId(config.config)}")
                credentials.clearCredential("ssh_passphrase_${profileId(config.config)}")
                credentials.clearCredential("ssh_private_key_${profileId(config.config)}")
                saveSSHProfiles()
            }
        }
    }

    fun cancelBootstrap() = WorkManager.getInstance(context).cancelUniqueWork(BOOTSTRAP_WORK_NAME)

    fun installedRunnerGroups(): Set<RunnerPackageGroup> = bootstrapper.installedGroups()

    fun runnerGroupSizeBytes(group: RunnerPackageGroup): Long = bootstrapper.groupSizeBytes(group)

    suspend fun uninstallRunnerGroup(group: RunnerPackageGroup) = bootstrapper.uninstallGroup(group)

    /** Re-downloads [group] from Termux's current live index, in place. */
    fun refreshRunnerGroup(group: RunnerPackageGroup) {
        bootstrapper.markGroupForRefresh(group)
        startBootstrap(installedRunnerGroups() + group)
    }

    fun close() {
        instances.values.forEach(ExecutionEnvironment::close)
        instances.clear()
    }

    private fun profileId(config: SSHConfig): String = profileId(config.host, config.port, config.username)

    private fun profileId(host: String, port: Int, username: String): String {
        val raw = "$username@$host:$port"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }

    private fun saveSSHProfiles() {
        val profiles = JSONArray()
        _environments.filterIsInstance<EnvironmentConfig.SSH>().forEach { environment ->
            val config = environment.config
            val id = profileId(config)
            profiles.put(JSONObject().apply {
                put("host", config.host)
                put("port", config.port)
                put("username", config.username)
                put("fingerprint", config.hostKeyFingerprint)
                put("workingDirectory", config.workingDirectory)
                put("authType", config.authType.name)
                put("privateKeyPath", config.privateKeyPath ?: JSONObject.NULL)
                put("useCloudflareTunnel", config.useCloudflareTunnel)
                put("usePersistentSession", config.usePersistentSession)
            })
            config.password?.let { credentials.saveCredential("ssh_password_$id", it) }
            config.privateKeyPassphrase?.let { credentials.saveCredential("ssh_passphrase_$id", it) }
            config.privateKeyContent?.let { credentials.saveCredential("ssh_private_key_$id", it) }
        }
        prefs.edit { putString("ssh_profiles", profiles.toString()) }
    }

    private fun restoreSSHProfiles(): List<EnvironmentConfig.SSH> = runCatching {
        val profiles = JSONArray(prefs.getString("ssh_profiles", "[]"))
        (0 until profiles.length()).map { index ->
            val item = profiles.getJSONObject(index)
            val host = item.getString("host")
            val port = item.optInt("port", 22)
            val username = item.getString("username")
            val id = profileId(host, port, username)
            EnvironmentConfig.SSH(SSHConfig(
                host = host,
                port = port,
                username = username,
                password = credentials.getCredential("ssh_password_$id"),
                hostKeyFingerprint = item.getString("fingerprint"),
                workingDirectory = item.optString("workingDirectory", "."),
                authType = SSHAuthType.valueOf(item.optString("authType", SSHAuthType.PASSWORD.name)),
                privateKeyPath = item.optString("privateKeyPath").takeIf { it.isNotBlank() },
                privateKeyPassphrase = credentials.getCredential("ssh_passphrase_$id"),
                privateKeyContent = credentials.getCredential("ssh_private_key_$id"),
                useCloudflareTunnel = item.optBoolean("useCloudflareTunnel", false),
                usePersistentSession = item.optBoolean("usePersistentSession", false)
            ))
        }
    }.getOrElse { emptyList() }
}

sealed class EnvironmentConfig {
    object Local : EnvironmentConfig()
    data class SSH(val config: SSHConfig) : EnvironmentConfig()
    object Node : EnvironmentConfig()
}
