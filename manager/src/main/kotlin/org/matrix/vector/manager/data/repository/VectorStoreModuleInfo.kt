package org.matrix.vector.manager.data.repository

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.vector.manager.data.model.versionCodeCompat
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.ui.store.StoreModuleInfo

class VectorStoreModuleInfo(private val packageName: String) : StoreModuleInfo {

    private val _installedScope = MutableStateFlow<List<String>>(emptyList())
    override val installedScope: StateFlow<List<String>> = _installedScope.asStateFlow()

    private val _installedIsLegacy = MutableStateFlow(false)
    override val installedIsLegacy: StateFlow<Boolean> = _installedIsLegacy.asStateFlow()

    init {
        refreshScope()
    }

    private fun refreshScope() {
        ServiceLocator.appScope.launch {
            runCatching {
                val pm = ServiceLocator.context.packageManager
                val info = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
                val appInfo = info.applicationInfo ?: return@runCatching
                val manifest =
                    ServiceLocator.moduleDetection.inspect(
                        appInfo,
                        pm,
                        info.versionCodeCompat,
                        info.lastUpdateTime,
                    )
                _installedScope.value = manifest.scope
                _installedIsLegacy.value = manifest.isLegacy
            }
        }
    }
}
