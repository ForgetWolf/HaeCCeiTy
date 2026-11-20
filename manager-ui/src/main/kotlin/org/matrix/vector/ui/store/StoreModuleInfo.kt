package org.matrix.vector.ui.store

import kotlinx.coroutines.flow.StateFlow

interface StoreModuleInfo {
    /** Device-side scope of the installed copy, for the Information tab (empty when unknown). */
    val installedScope: StateFlow<List<String>>

    /** Whether the installed copy is a legacy-framework module (its scope names are swapped). */
    val installedIsLegacy: StateFlow<Boolean>
}
