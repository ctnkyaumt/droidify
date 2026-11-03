package com.looker.droidify.ui.appList

import android.database.Cursor
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.looker.droidify.database.CursorOwner
import com.looker.droidify.databinding.RecyclerViewWithFabBinding
import com.looker.droidify.model.ProductItem
import com.looker.droidify.service.Connection
import com.looker.droidify.service.DownloadService
import com.looker.droidify.service.SyncService
import com.looker.droidify.ui.tabsFragment.TabsFragment
import com.looker.droidify.utility.common.Scroller
import com.looker.droidify.utility.common.extension.dp
import com.looker.droidify.utility.common.extension.systemBarsMargin
import com.looker.droidify.utility.common.extension.systemBarsPadding
import com.looker.droidify.utility.extension.mainActivity
import com.looker.droidify.utility.getParcelableCompat
import com.looker.droidify.widget.RecyclerFastScroller
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.looker.droidify.R.string as stringRes

@AndroidEntryPoint
class AppListFragment() : Fragment(), CursorOwner.Callback {
    companion object {
        private const val STATE_LAYOUT_MANAGER = "layoutManager"

        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_SEARCH_QUERY = "search_query"
    }

    enum class Source(
        val titleResId: Int,
        val sections: Boolean,
        val updateAll: Boolean,
    ) {
        AVAILABLE(stringRes.available, true, false),
        INSTALLED(stringRes.installed, false, false),
        UPDATES(stringRes.updates, false, true),
    }

    constructor(source: Source) : this() {
        arguments = Bundle().apply {
            putString(EXTRA_SOURCE, source.name)
        }
    }

    val source by lazy { Source.valueOf(requireArguments().getString(EXTRA_SOURCE)!!) }

    private val viewModel: AppListViewModel by viewModels()

    private var _binding: RecyclerViewWithFabBinding? = null
    private val binding get() = _binding!!

    private lateinit var recyclerView: RecyclerView
    private lateinit var appListAdapter: AppListAdapter
    private var scroller: Scroller? = null
    private var shortAnimationDuration: Int = 0
    private var layoutManagerState: Parcelable? = null

    private var searchQuery: String = ""
    private var isSyncing = false

    private val updateAllRequested = MutableStateFlow(false)
    private val downloadState = MutableStateFlow(DownloadService.DownloadState())
    private var downloadStateJob: Job? = null
    private val downloadConnection = Connection(
        serviceClass = DownloadService::class.java,
        onBind = { _, binder ->
            downloadStateJob?.cancel()
            downloadStateJob = lifecycleScope.launch {
                binder.downloadState.collect { downloadState.value = it }
            }
        },
        onUnbind = { _, _ ->
            downloadStateJob?.cancel()
            downloadStateJob = null
            downloadState.value = DownloadService.DownloadState()
        },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = RecyclerViewWithFabBinding.inflate(inflater, container, false)

        shortAnimationDuration = resources.getInteger(android.R.integer.config_shortAnimTime)

        searchQuery = savedInstanceState?.getString(EXTRA_SEARCH_QUERY).orEmpty()

        val viewModel = viewModel

        recyclerView = binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            isMotionEventSplittingEnabled = false
            setHasFixedSize(true)
            recycledViewPool.setMaxRecycledViews(AppListAdapter.ViewType.PRODUCT.ordinal, 30)
            appListAdapter = AppListAdapter(source, mainActivity::navigateProduct)
            adapter = appListAdapter
            systemBarsPadding()
            RecyclerFastScroller(this)
        }
        binding.swipeRefresh.setOnRefreshListener {
            val parent = parentFragment as? TabsFragment
            if (parent == null || !parent.syncRepositories()) {
                binding.swipeRefresh.isRefreshing = false
            }
        }
        with(binding.updateAll) {
            if (source.updateAll) {
                downloadConnection.bind(requireContext())
                setOnClickListener {
                    val tabsFragment = parentFragment as? TabsFragment
                    if (tabsFragment != null) {
                        updateAllRequested.value = true
                        tabsFragment.updateAll { updateAllRequested.value = false }
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.showUpdateAllButton.collect {
                        isVisible = it
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    combine(updateAllRequested, downloadState) { requested, state ->
                        when {
                            state.batchTotal > 0 -> false to getString(
                                stringRes.updating_progress_FORMAT,
                                (state.batchCompleted + 1).coerceAtMost(state.batchTotal),
                                state.batchTotal,
                            )

                            requested -> false to getString(stringRes.updating_all)
                            else -> true to getString(stringRes.update_all)
                        }
                    }.collect { (enabled, label) ->
                        isEnabled = enabled
                        text = label
                    }
                }
                systemBarsMargin(16.dp)
            }
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        layoutManagerState = savedInstanceState?.getParcelableCompat(STATE_LAYOUT_MANAGER)
        mainActivity.cursorOwner.attach(
            callback = this,
            request = viewModel.state.value.toRequest(source, searchQuery),
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SyncService.syncState.collect { state ->
                        isSyncing = state !is SyncService.State.Finish
                        if (state is SyncService.State.Finish) {
                            binding.swipeRefresh.isRefreshing = false
                        }
                        updateEmptyText()
                    }
                }
                launch {
                    viewModel.reposStream.collect { repos ->
                        appListAdapter.repositories = repos.associateBy { it.id }
                        updateEmptyText()
                    }
                }
                launch {
                    viewModel.state.collect {
                        mainActivity.cursorOwner.attach(
                            callback = this@AppListFragment,
                            request = it.toRequest(source, searchQuery),
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val managerState = layoutManagerState ?: recyclerView.layoutManager?.onSaveInstanceState()
        if (managerState != null) {
            outState.putParcelable(STATE_LAYOUT_MANAGER, managerState)
        }
        outState.putString(EXTRA_SEARCH_QUERY, searchQuery)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        scroller = null
        if (source.updateAll) downloadConnection.unbind(requireContext())
        mainActivity.cursorOwner.detach(this)
    }

    override fun onCursorData(request: CursorOwner.Request, cursor: Cursor?) {
        appListAdapter.cursor = cursor
        updateEmptyText()
        layoutManagerState?.let {
            layoutManagerState = null
            recyclerView.layoutManager?.onRestoreInstanceState(it)
        }
    }

    private fun updateEmptyText() {
        val cursor = appListAdapter.cursor
        val isInitialSync = isSyncing &&
            searchQuery.isEmpty() &&
            (cursor == null || cursor.count == 0) &&
            viewModel.reposStream.value.let { repos -> repos.isNotEmpty() && repos.all { it.updated == 0L } }

        appListAdapter.emptyText = when {
            cursor == null && !isInitialSync -> ""
            searchQuery.isNotEmpty() -> {
                getString(stringRes.no_matching_applications_found)
            }

            isInitialSync -> {
                getString(stringRes.initial_sync_in_progress)
            }

            else -> when (source) {
                Source.AVAILABLE -> getString(stringRes.no_applications_available)
                Source.INSTALLED -> getString(stringRes.no_applications_installed)
                Source.UPDATES -> getString(stringRes.all_applications_up_to_date)
            }
        }
        if (appListAdapter.itemCount == 1 && appListAdapter.getItemEnumViewType(0) == AppListAdapter.ViewType.EMPTY) {
            appListAdapter.notifyItemChanged(0)
        }
    }

    fun setSearchQuery(newSearchQuery: String) {
        if (view != null) {
            searchQuery = newSearchQuery
            mainActivity.cursorOwner.attach(
                callback = this,
                request = viewModel.state.value.toRequest(source, searchQuery),
            )
        }
    }

    fun setSection(section: ProductItem.Section) {
        viewModel.setSection(section)
    }
}
