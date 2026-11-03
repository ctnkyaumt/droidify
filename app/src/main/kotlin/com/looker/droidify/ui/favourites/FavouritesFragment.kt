package com.looker.droidify.ui.favourites

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.looker.droidify.R
import com.looker.droidify.database.Database
import com.looker.droidify.datastore.extension.sortOrderName
import com.looker.droidify.datastore.model.supportedSortOrders
import com.looker.droidify.ui.ScreenFragment
import com.looker.droidify.utility.common.extension.getMutatedIcon
import com.looker.droidify.utility.common.extension.systemBarsPadding
import com.looker.droidify.utility.extension.mainActivity
import com.looker.droidify.utility.getParcelableCompat
import com.looker.droidify.widget.RecyclerFastScroller
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val STATE_LAYOUT_MANAGER = "state_layout_manager"

@AndroidEntryPoint
class FavouritesFragment : ScreenFragment() {

    private val viewModel: FavouritesViewModel by viewModels()

    private lateinit var recyclerView: RecyclerView
    private lateinit var recyclerViewAdapter: FavouriteFragmentAdapter
    private var layoutManagerState: Parcelable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)
        val view = fragmentBinding.root.apply {
            val content = fragmentBinding.fragmentContent
            content.addView(
                RecyclerView(content.context).apply {
                    id = android.R.id.list
                    layoutManager = LinearLayoutManager(context)
                    isVerticalScrollBarEnabled = false
                    setHasFixedSize(true)
                    recyclerViewAdapter =
                        FavouriteFragmentAdapter { mainActivity.navigateProduct(it) }
                    this.adapter = recyclerViewAdapter
                    systemBarsPadding(fabPadding = 0)
                    RecyclerFastScroller(this)
                    recyclerView = this
                },
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.favouriteApps.collect { apps ->
                        recyclerViewAdapter.apps = apps
                        layoutManagerState?.let {
                            layoutManagerState = null
                            recyclerView.layoutManager?.onRestoreInstanceState(it)
                        }
                    }
                }
                launch {
                    Database.RepositoryAdapter
                        .getAllStream()
                        .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.RESUMED)
                        .collectLatest { repositories ->
                            recyclerViewAdapter.repositories = repositories.associateBy { it.id }
                        }
                }
            }
        }

        toolbar.title = getString(R.string.favourites)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        layoutManagerState = savedInstanceState?.getParcelableCompat(STATE_LAYOUT_MANAGER)
        mainActivity.onToolbarCreated(toolbar)

        val sortOrders = supportedSortOrders()
        val sortMenuItems = toolbar.menu.addSubMenu(0, 0, 0, R.string.sorting_order)
            .setIcon(toolbar.context.getMutatedIcon(R.drawable.ic_sort))
            .let { menu ->
                menu.item.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                val items = sortOrders.map { sortOrder ->
                    menu.add(context.sortOrderName(sortOrder))
                        .setOnMenuItemClickListener {
                            viewModel.setSortOrder(sortOrder)
                            true
                        }
                }
                menu.setGroupCheckable(0, true, true)
                items
            }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sortOrder.collect { sortOrder ->
                sortMenuItems.getOrNull(sortOrders.indexOf(sortOrder))?.isChecked = true
            }
        }

        toolbar.menu.add(0, 0, 1, R.string.mark_installed_as_favourites)
            .setOnMenuItemClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    val installed = Database.ProductAdapter.getInstalledPackageNames()
                    viewModel.addFavourites(installed)
                    Snackbar.make(
                        requireView(),
                        R.string.marked_installed_as_favourites,
                        Snackbar.LENGTH_SHORT,
                    ).show()
                }
                true
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val managerState = layoutManagerState ?: recyclerView.layoutManager?.onSaveInstanceState()
        if (managerState != null) {
            outState.putParcelable(STATE_LAYOUT_MANAGER, managerState)
        }
    }
}
