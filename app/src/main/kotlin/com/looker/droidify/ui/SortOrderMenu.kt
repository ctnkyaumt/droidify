package com.looker.droidify.ui

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import com.looker.droidify.R
import com.looker.droidify.datastore.extension.sortOrderName
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.datastore.model.supportedSortOrders
import com.looker.droidify.utility.common.extension.getMutatedIcon

class SortOrderMenu(
    val menuItem: MenuItem,
    private val sortOrders: List<SortOrder>,
    private val menuItems: List<MenuItem>,
) {
    fun setChecked(sortOrder: SortOrder) {
        val index = sortOrders.indexOf(sortOrder)
        menuItems.getOrNull(index)?.isChecked = true
    }
}

fun Menu.setupSortOrderMenu(
    context: Context,
    onSelected: (SortOrder) -> Unit,
): SortOrderMenu {
    val sortOrders = supportedSortOrders()
    val subMenu = addSubMenu(0, 0, 0, R.string.sorting_order)
        .setIcon(context.getMutatedIcon(R.drawable.ic_sort))
    subMenu.item.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
    val items = sortOrders.map { sortOrder ->
        subMenu.add(context.sortOrderName(sortOrder))
            .setOnMenuItemClickListener {
                onSelected(sortOrder)
                true
            }
    }
    subMenu.setGroupCheckable(0, true, true)
    return SortOrderMenu(subMenu.item, sortOrders, items)
}
