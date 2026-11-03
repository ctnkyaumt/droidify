package com.looker.droidify.ui.appList

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.database.CursorOwner.Request.Available
import com.looker.droidify.database.CursorOwner.Request.Installed
import com.looker.droidify.database.CursorOwner.Request.Updates
import com.looker.droidify.database.Database
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.model.ProductItem
import com.looker.droidify.model.ProductItem.Section.All
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.utility.getPreinstalledSystemPackages
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class AppListViewModel
@Inject constructor(
    @ApplicationContext context: Context,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val skipSignatureStream = settingsRepository
        .get { ignoreSignature }
        .asStateFlow(false)

    private val sortOrderFlow = settingsRepository
        .get { sortOrder }
        .asStateFlow(SortOrder.UPDATED)

    private val hideSystemAppsStream = settingsRepository
        .get { hideSystemApps }
        .asStateFlow(false)

    private val preinstalledPackagesFlow = flow {
        emit(context.getPreinstalledSystemPackages())
    }.flowOn(Dispatchers.IO)

    private val sections = MutableStateFlow<ProductItem.Section>(All)

    val state = combine(
        skipSignatureStream,
        sortOrderFlow,
        sections,
        hideSystemAppsStream,
        preinstalledPackagesFlow,
    ) { skipSignature, sortOrder, section, hideSystemApps, preinstalledPackages ->
        AppListState(
            sections = section,
            sortOrder = sortOrder,
            skipSignatureCheck = skipSignature,
            hideSystemApps = hideSystemApps,
            excludedPackages = if (hideSystemApps) preinstalledPackages else emptySet(),
        )
    }.asStateFlow(AppListState())

    val reposStream = Database.RepositoryAdapter
        .getAllStream()
        .asStateFlow(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val showUpdateAllButton = skipSignatureStream.flatMapLatest { skip ->
        Database.ProductAdapter
            .getUpdatesStream(skip)
            .map { it.isNotEmpty() }
    }.asStateFlow(false)

    fun setSection(newSection: ProductItem.Section) {
        viewModelScope.launch {
            sections.emit(newSection)
        }
    }
}

data class AppListState(
    val sections: ProductItem.Section = All,
    val sortOrder: SortOrder = SortOrder.UPDATED,
    val skipSignatureCheck: Boolean = false,
    val hideSystemApps: Boolean = false,
    val excludedPackages: Set<String> = emptySet(),
) {
    fun toRequest(source: AppListFragment.Source, searchQuery: String) = when (source) {
        AppListFragment.Source.AVAILABLE -> Available(
            searchQuery = searchQuery,
            section = sections,
            order = sortOrder,
            skipSignatureCheck = skipSignatureCheck,
        )

        AppListFragment.Source.INSTALLED -> Installed(
            searchQuery = searchQuery,
            order = sortOrder,
            skipSignatureCheck = skipSignatureCheck,
            excludedPackages = excludedPackages,
        )

        AppListFragment.Source.UPDATES -> Updates(
            searchQuery = searchQuery,
            order = sortOrder,
            skipSignatureCheck = skipSignatureCheck,
        )
    }
}
