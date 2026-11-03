package com.looker.droidify.ui.favourites

import android.content.Context
import androidx.core.content.edit
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
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val mutableSortOrder = MutableStateFlow(
        preferences.getString(KEY_SORT_ORDER, null)
            ?.let { name -> supportedSortOrders().find { it.name == name } }
            ?: SortOrder.NAME,
    )
    val sortOrder: StateFlow<SortOrder> = mutableSortOrder

    val favouriteApps: StateFlow<List<ProductItem>> =
        combine(
            settingsRepository.get { favouriteApps },
            mutableSortOrder,
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
        preferences.edit { putString(KEY_SORT_ORDER, sortOrder.name) }
        mutableSortOrder.value = sortOrder
    }

    fun addFavourites(packageNames: Collection<String>) {
        viewModelScope.launch {
            settingsRepository.addFavourites(packageNames)
        }
    }
}
