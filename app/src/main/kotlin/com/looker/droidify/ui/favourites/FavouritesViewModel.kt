package com.looker.droidify.ui.favourites

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.database.Database
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.datastore.model.supportedSortOrders
import com.looker.droidify.model.ProductItem
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PREFERENCES_NAME = "favourites"
private const val KEY_SORT_ORDER = "sort_order"

@HiltViewModel
class FavouritesViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    init {
        val oldPrefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (oldPrefs.contains(KEY_SORT_ORDER)) {
            val oldSortOrderName = oldPrefs.getString(KEY_SORT_ORDER, null)
            val oldSortOrder = supportedSortOrders().find { it.name == oldSortOrderName }
            if (oldSortOrder != null) {
                viewModelScope.launch {
                    settingsRepository.setFavouritesSortOrder(oldSortOrder)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(PREFERENCES_NAME)
            } else {
                oldPrefs.edit().clear().apply()
            }
        }
    }

    val sortOrder: StateFlow<SortOrder> =
        settingsRepository.get { favouritesSortOrder }.asStateFlow(SortOrder.NAME)

    val favouriteApps: StateFlow<List<ProductItem>> =
        combine(
            settingsRepository.get { favouriteApps },
            settingsRepository.get { favouritesSortOrder },
        ) { favourites, sortOrder ->
            favourites.mapNotNull { app ->
                val products = Database.ProductAdapter.get(app, null)
                val product = products.firstOrNull() ?: return@mapNotNull null
                val installed = Database.InstalledAdapter.get(app, null)

                product to product.item().apply {
                    this.installedVersion = installed?.version.orEmpty()
                    this.canUpdate = product.canUpdate(installed)
                }
            }.let { apps ->
                when (sortOrder) {
                    SortOrder.UPDATED -> apps.sortedByDescending { (product, _) -> product.updated }
                    SortOrder.ADDED -> apps.sortedByDescending { (product, _) -> product.added }
                    else -> apps.sortedBy { (_, item) -> item.name.lowercase() }
                }
            }.map { (_, item) -> item }
        }.asStateFlow(emptyList())

    fun setSortOrder(sortOrder: SortOrder) {
        viewModelScope.launch {
            settingsRepository.setFavouritesSortOrder(sortOrder)
        }
    }

    fun addFavourites(packageNames: Collection<String>) {
        viewModelScope.launch {
            settingsRepository.addFavourites(packageNames)
        }
    }
}
