package com.looker.droidify.ui.tabsFragment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.local.dao.RepoDao
import com.looker.droidify.database.Database
import com.looker.droidify.utility.CategoryLocalization
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.model.ProductItem
import com.looker.droidify.ui.tabsFragment.TabsFragment.BackAction
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@HiltViewModel
class TabsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val savedStateHandle: SavedStateHandle,
    private val repoDao: RepoDao,
) : ViewModel() {

    val currentSection =
        savedStateHandle.getStateFlow<ProductItem.Section>(STATE_SECTION, ProductItem.Section.All)

    val sortOrder = settingsRepository
        .get { sortOrder }
        .asStateFlow(SortOrder.UPDATED)

    val allowHomeScreenSwiping = settingsRepository
        .get { homeScreenSwiping }
        .asStateFlow(false)

    val sections = combine(
        Database.CategoryAdapter.getAllStream(),
        Database.RepositoryAdapter.getEnabledStream(),
        repoDao.allCategories(),
        settingsRepository.get { language },
    ) { categories, repos, repoCategories, language ->
        repoCategories.forEach {
            CategoryLocalization.register(it.defaultName, it.locale, it.name)
        }
        if (repos.isEmpty()) setSection(ProductItem.Section.All)
        buildList {
            add(ProductItem.Section.All)
            categories.sorted().mapTo(this) { categoryName ->
                ProductItem.Section.Category(
                    name = categoryName,
                    localizedName = CategoryLocalization.getLocalizedName(categoryName, language),
                )
            }
            repos.mapTo(this) { ProductItem.Section.Repository(it.id, it.name) }
        }
    }.catch { it.printStackTrace() }
        .asStateFlow(emptyList())

    val isSearchActionItemExpanded = MutableStateFlow(false)

    val showSections = MutableStateFlow(false)

    val backAction = combine(
        currentSection,
        isSearchActionItemExpanded,
        showSections,
    ) { currentSection, isSearchActionItemExpanded, showSections ->
        when {
            currentSection != ProductItem.Section.All -> BackAction.ProductAll
            isSearchActionItemExpanded -> BackAction.CollapseSearchView
            showSections -> BackAction.HideSections
            else -> BackAction.None
        }
    }.asStateFlow(BackAction.None)

    fun setSection(section: ProductItem.Section) {
        savedStateHandle[STATE_SECTION] = section
    }

    fun setSortOrder(sortOrder: SortOrder) {
        viewModelScope.launch {
            settingsRepository.setSortOrder(sortOrder)
        }
    }

    suspend fun resetPrivacyFetchTimestamps() {
        settingsRepository.clearRbLogLastModified()
        settingsRepository.clearDownloadStatsLastModified()
    }

    companion object {
        private const val STATE_SECTION = "section"
    }
}
