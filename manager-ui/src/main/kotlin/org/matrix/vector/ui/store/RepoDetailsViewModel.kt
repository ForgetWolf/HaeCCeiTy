package org.matrix.vector.ui.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How the second, richer fetch is going. The page is readable in all three states. */
enum class DetailFetch {
    Loading,
    Loaded,
    Unavailable,
}

data class RepoDetailsState(
    val module: OnlineModule? = null,
    val releases: List<Release> = emptyList(),
    val installed: RepoVersion? = null,
    val fetch: DetailFetch = DetailFetch.Loading,
    val channel: StoreChannel = StoreChannel.Stable,
)

class RepoDetailsViewModel(
    private val packageName: String,
    private val dataSource: StoreDataSource,
    private val settings: StoreSettings,
    private val host: StoreModuleInfo?,
) : ViewModel() {

    /**
     * What the installed copy of this module says it hooks, read from its own APK by the host.
     *
     * The catalogue's `scope` is optional metadata and most authors omit it, so the information
     * panel says "not declared" for the majority of modules. For a module that is *installed*,
     * though, the authoritative list is in its APK, and the host reads it. Empty — and legacy false
     * — when there is no host, or nothing installed to inspect.
     */
    val installedScope: StateFlow<List<String>> =
        host?.installedScope ?: MutableStateFlow<List<String>>(emptyList()).asStateFlow()

    /**
     * Whether the copy on this device is a legacy module, which decides how to read the
     * *catalogue's* scope. Host-supplied; false when absent, which is the honest answer rather than
     * a safe one.
     */
    val installedIsLegacy: StateFlow<Boolean> =
        host?.installedIsLegacy ?: MutableStateFlow(false).asStateFlow()

    private val _detail = MutableStateFlow<OnlineModule?>(null)
    private val _fetch = MutableStateFlow(DetailFetch.Loading)

    /** Whether this module has been told to stop reporting updates. */
    val updatesMuted: StateFlow<Boolean> =
        settings.mutedUpdates
            .map { packageName in it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setUpdatesMuted(muted: Boolean) = settings.setUpdatesMuted(packageName, muted)

    val state: StateFlow<RepoDetailsState> =
        combine(
                dataSource.catalog,
                _detail,
                _fetch,
                dataSource.installedVersions,
                settings.updateChannel,
            ) { catalog, detail, fetch, installed, channelPreference ->
                val seed = catalog.modules.firstOrNull { it.name == packageName }
                val module = detail ?: seed
                val channel = StoreChannel.of(channelPreference)
                RepoDetailsState(
                    module = module,
                    releases = releasesFor(module, channel),
                    installed = installed[packageName],
                    fetch = fetch,
                    channel = channel,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepoDetailsState())

    init {
        fetchDetails()
    }

    fun fetchDetails() {
        viewModelScope.launch {
            _fetch.value = DetailFetch.Loading
            val fetched = dataSource.details(packageName)
            // A failure is not an error screen. The seeded entry is still on display; all that is
            // missing is the README and the older releases, and the page says so quietly.
            _detail.value = fetched ?: _detail.value
            _fetch.value = if (fetched != null) DetailFetch.Loaded else DetailFetch.Unavailable
        }
    }

    private fun releasesFor(module: OnlineModule?, channel: StoreChannel): List<Release> =
        module?.releasesOn(channel).orEmpty()
}
