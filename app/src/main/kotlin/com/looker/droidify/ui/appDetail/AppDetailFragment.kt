package com.looker.droidify.ui.appDetail

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import coil3.load
import coil3.request.allowHardware
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.looker.droidify.content.ProductPreferences
import com.looker.droidify.installer.installers.launchShizuku
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.installer.model.isCancellable
import com.looker.droidify.model.InstalledItem
import com.looker.droidify.model.Product
import com.looker.droidify.model.ProductPreference
import com.looker.droidify.model.Release
import com.looker.droidify.model.Repository
import com.looker.droidify.model.findSuggested
import com.looker.droidify.model.UpdateResolver
import com.looker.droidify.service.Connection
import com.looker.droidify.service.DownloadService
import com.looker.droidify.ui.Message
import com.looker.droidify.ui.MessageDialog
import com.looker.droidify.ui.ScreenFragment
import com.looker.droidify.ui.appDetail.AppDetailViewModel.Companion.ARG_PACKAGE_NAME
import com.looker.droidify.ui.appDetail.AppDetailViewModel.Companion.ARG_REPO_ADDRESS
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.authentication
import com.looker.droidify.utility.common.extension.dpToPx
import com.looker.droidify.utility.common.extension.getLauncherActivities
import com.looker.droidify.utility.common.extension.getMutatedIcon
import com.looker.droidify.utility.common.extension.isFirstItemVisible
import com.looker.droidify.utility.common.extension.isSystemApplication
import com.looker.droidify.utility.common.extension.openLink
import com.looker.droidify.utility.common.extension.systemBarsPadding
import com.looker.droidify.utility.common.extension.updateAsMutable
import com.looker.droidify.utility.common.shareUrl
import com.looker.droidify.utility.extension.android.Android.name
import com.looker.droidify.utility.extension.android.Android.primaryPlatform
import com.looker.droidify.utility.extension.mainActivity
import com.looker.droidify.utility.extension.startUpdate
import com.looker.droidify.utility.getParcelableCompat
import com.stfalcon.imageviewer.StfalconImageViewer
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.looker.droidify.R.string as stringRes

@AndroidEntryPoint
class AppDetailFragment() : ScreenFragment(), AppDetailAdapter.Callbacks {
    private companion object {
        const val TAG = "AppDetailFragment"
        const val STATE_LAYOUT_MANAGER = "layoutManager"
        const val STATE_ADAPTER = "adapter"
    }

    constructor(packageName: String, repoAddress: String? = null) : this() {
        arguments = Bundle().apply {
            putString(ARG_PACKAGE_NAME, packageName)
            if (repoAddress != null) putString(ARG_REPO_ADDRESS, repoAddress)
        }
    }

    private enum class Action(
        val id: Int,
        val adapterAction: AppDetailAdapter.Action,
    ) {
        INSTALL(1, AppDetailAdapter.Action.INSTALL),
        UPDATE(2, AppDetailAdapter.Action.UPDATE),
        LAUNCH(3, AppDetailAdapter.Action.LAUNCH),
        DETAILS(4, AppDetailAdapter.Action.DETAILS),
        UNINSTALL(5, AppDetailAdapter.Action.UNINSTALL),
        SOURCE(6, AppDetailAdapter.Action.SOURCE),
        SHARE(7, AppDetailAdapter.Action.SHARE),
    }

    private class Installed(
        val installedItem: InstalledItem,
        val isSystem: Boolean,
        val launcherActivities: List<Pair<String, String>>,
    )

    private val viewModel: AppDetailViewModel by viewModels()

    @SuppressLint("RestrictedApi")
    private var layoutManagerState: LinearLayoutManager.SavedState? = null

    private var actions = Pair(emptySet<Action>(), null as Action?)
    private var products = emptyList<Pair<Product, Repository>>()
    private var preferredRepositoryId = 0L
    private var note = ""
    private var installed: Installed? = null
    private var downloading = false
    private var installing: InstallState? = null

    private var recyclerView: RecyclerView? = null
    private var detailAdapter: AppDetailAdapter? = null
    private var imageViewer: StfalconImageViewer.Builder<Product.Screenshot>? = null

    private val downloadConnection = Connection(
        serviceClass = DownloadService::class.java,
        onBind = { _, binder ->
            lifecycleScope.launch {
                binder.downloadState.collect(::updateDownloadState)
            }
        },
    )

    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        detailAdapter = AppDetailAdapter(this@AppDetailFragment)
        mainActivity.onToolbarCreated(toolbar)
        toolbar.menu.apply {
            Action.entries.forEach { action ->
                add(0, action.id, 0, action.adapterAction.titleResId)
                    .setIcon(toolbar.context.getMutatedIcon(action.adapterAction.iconResId))
                    .setVisible(false)
                    .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    .setOnMenuItemClickListener {
                        onActionClick(action.adapterAction)
                        true
                    }
            }
        }

        val content = fragmentBinding.fragmentContent
        content.addView(
            RecyclerView(content.context).apply {
                id = android.R.id.list
                this.layoutManager = LinearLayoutManager(
                    context,
                    LinearLayoutManager.VERTICAL,
                    false,
                )
                isMotionEventSplittingEnabled = false
                isVerticalScrollBarEnabled = false
                adapter = detailAdapter
                (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
                if (detailAdapter != null) {
                    savedInstanceState?.getParcelableCompat<AppDetailAdapter.SavedState>(
                        STATE_ADAPTER,
                    )
                        ?.let(detailAdapter!!::restoreState)
                }
                layoutManagerState = savedInstanceState?.getParcelableCompat(STATE_LAYOUT_MANAGER)
                recyclerView = this
                systemBarsPadding(fabPadding = 0)
            },
        )
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.state.collectLatest { state ->
                        products = state.products.mapNotNull { product ->
                            val requiredRepo = state.repos.find { it.id == product.repositoryId }
                            requiredRepo?.let { product to it }
                        }
                        preferredRepositoryId = state.preference.preferredRepositoryId
                        note = state.preference.note
                        layoutManagerState?.let {
                            recyclerView?.layoutManager!!.onRestoreInstanceState(it)
                        }
                        layoutManagerState = null
                        installed = state.installedItem?.let {
                            with(requireContext().packageManager) {
                                val isSystem = isSystemApplication(viewModel.packageName)
                                val launcherActivities = if (state.isSelf) {
                                    emptyList()
                                } else {
                                    getLauncherActivities(viewModel.packageName)
                                }
                                Installed(it, isSystem, launcherActivities)
                            }
                        }
                        val adapter = recyclerView?.adapter as? AppDetailAdapter

                        // `delay` is cancellable hence it waits for 50 milliseconds to show empty page
                        if (products.isEmpty()) delay(50)

                        adapter?.setProducts(
                            context = requireContext(),
                            packageName = viewModel.packageName,
                            suggestedRepo = state.addressIfUnavailable,
                            products = products,
                            rblogs = state.rblogs,
                            installedItem = state.installedItem,
                            isFavourite = state.isFavourite,
                            allowIncompatibleVersion = state.allowIncompatibleVersions,
                            preferredRepositoryId = preferredRepositoryId,
                            note = note,
                        )
                        updateButtons()
                    }
                }
                launch {
                    viewModel.customButtons.collectLatest { buttons ->
                        (recyclerView?.adapter as? AppDetailAdapter)?.setCustomButtons(buttons)
                    }
                }
                launch {
                    viewModel.installerState.collect(::updateInstallState)
                }
                launch {
                    recyclerView?.isFirstItemVisible?.collect(::updateToolbarButtons)
                }
            }
        }

        downloadConnection.bind(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView = null
        detailAdapter = null
        imageViewer = null

        downloadConnection.unbind(requireContext())
    }

    @SuppressLint("RestrictedApi")
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val layoutManagerState =
            layoutManagerState ?: recyclerView?.layoutManager?.onSaveInstanceState()
        layoutManagerState?.let { outState.putParcelable(STATE_LAYOUT_MANAGER, it) }
        val adapterState = (recyclerView?.adapter as? AppDetailAdapter)?.saveState()
        adapterState?.let { outState.putParcelable(STATE_ADAPTER, it) }
    }

    private fun updateButtons(
        preference: ProductPreference = ProductPreferences[viewModel.packageName],
    ) {
        val installed = installed
        val product = products.findSuggested(installed?.installedItem, preferredRepositoryId)?.first
        val suggestedRelease = product?.let {
            UpdateResolver.findSuggestedRelease(it.releases, installed?.installedItem, primaryPlatform)
        } ?: product?.selectedReleases?.firstOrNull()
        val compatible = suggestedRelease != null && suggestedRelease.incompatibilities.isEmpty()
        val canInstall = product != null && installed == null && compatible
        val canUpdate =
            product != null && compatible && product.canUpdate(installed?.installedItem) &&
                !preference.shouldIgnoreUpdate(suggestedRelease?.versionCode ?: product.versionCode)
        val canUninstall = product != null && installed != null && !installed.isSystem
        val canLaunch =
            product != null && installed != null && installed.launcherActivities.isNotEmpty()

        val incompatibilityReason = if (product != null) {
            val selectedRelease = suggestedRelease ?: product.selectedReleases.firstOrNull()
            val incompatibility = selectedRelease?.incompatibilities?.firstOrNull()
            val installedItem = installed?.installedItem

            when {
                incompatibility != null -> {
                    when (incompatibility) {
                        is Release.Incompatibility.MinSdk,
                        is Release.Incompatibility.MaxSdk,
                            -> getString(
                            stringRes.incompatible_with_FORMAT,
                            name,
                        )

                        is Release.Incompatibility.Platform -> getString(
                            stringRes.incompatible_with_FORMAT,
                            primaryPlatform ?: getString(stringRes.unknown),
                        )

                        is Release.Incompatibility.Feature -> getString(
                            stringRes.requires_FORMAT,
                            incompatibility.feature,
                        )
                    }
                }

                installedItem != null
                    && selectedRelease != null
                    && !UpdateResolver.isSignatureCompatible(installedItem.signatures, selectedRelease.signature) -> {
                    getString(stringRes.incompatible_signature_DESC)
                }

                else -> null
            }
        } else {
            null
        }

        (recyclerView?.adapter as? AppDetailAdapter)?.incompatibilityReason = incompatibilityReason

        val actions = buildSet {
            if (canInstall) add(Action.INSTALL)
            if (canUpdate) add(Action.UPDATE)
            if (canLaunch) add(Action.LAUNCH)
            if (installed != null) add(Action.DETAILS)
            if (canUninstall) add(Action.UNINSTALL)
            add(Action.SHARE)
            add(Action.SOURCE)
        }

        val primaryAction = when {
            canUpdate -> Action.UPDATE
            canLaunch -> Action.LAUNCH
            canInstall -> Action.INSTALL
            installed != null -> Action.DETAILS
            else -> Action.SHARE
        }

        val adapterAction = when {
            installing == InstallState.Installing -> null
            installing == InstallState.Pending -> AppDetailAdapter.Action.CANCEL
            downloading -> AppDetailAdapter.Action.CANCEL
            else -> primaryAction.adapterAction
        }

        (recyclerView?.adapter as? AppDetailAdapter)?.action = adapterAction

        for (action in sequenceOf(
            Action.INSTALL,
            Action.UPDATE,
        )) {
            toolbar.menu.findItem(action.id).isEnabled = !downloading
        }
        this.actions = Pair(actions, primaryAction)
        updateToolbarButtons()
    }

    private fun updateToolbarButtons(
        isActionVisible: Boolean = (recyclerView?.layoutManager as LinearLayoutManager)
            .findFirstVisibleItemPosition() == 0,
    ) {
        toolbar.title = if (isActionVisible) {
            getString(stringRes.application)
        } else {
            products.firstOrNull()?.first?.name ?: getString(stringRes.application)
        }
        val (actions, primaryAction) = actions
        val displayActions = actions.updateAsMutable {
            if (isActionVisible && primaryAction != null) {
                remove(primaryAction)
            }
        }
        Action.entries.forEach { action ->
            toolbar.menu.findItem(action.id).isVisible = action in displayActions
        }
    }

    private fun InstallState?.toAdapterStatus(): AppDetailAdapter.Status = when (this) {
        InstallState.Pending -> AppDetailAdapter.Status.PendingInstall
        InstallState.Installing -> AppDetailAdapter.Status.Installing
        else -> AppDetailAdapter.Status.Idle
    }

    private fun updateInstallState(installerState: InstallState?) {
        (recyclerView?.adapter as? AppDetailAdapter)?.status = installerState.toAdapterStatus()
        installing = installerState
        updateButtons()
    }

    private fun updateDownloadState(state: DownloadService.DownloadState) {
        val packageName = viewModel.packageName
        val isPending = packageName in state.queue
        val isDownloading = state isDownloading packageName
        val isCompleted = state isComplete packageName
        val isActive = isPending || isDownloading
        // A download success stays as the service's current item until the next download, so
        // once the install manager has a state for this package (queued, installing, failed,
        // installed), that state is authoritative and the success must not be acted on again.
        val installerState = viewModel.currentInstallState()
        if (isPending) {
            detailAdapter?.status = AppDetailAdapter.Status.Pending
        }
        if (isDownloading) {
            detailAdapter?.status = when (state.currentItem) {
                is DownloadService.State.Connecting -> AppDetailAdapter.Status.Connecting
                is DownloadService.State.Downloading -> AppDetailAdapter.Status.Downloading(
                    state.currentItem.read,
                    state.currentItem.total,
                )

                else -> AppDetailAdapter.Status.Idle
            }
        }
        if (isCompleted) {
            if (state.currentItem is DownloadService.State.Success && installerState == null) {
                installing = InstallState.Pending
                detailAdapter?.status = AppDetailAdapter.Status.PendingInstall
            } else if (state.currentItem is DownloadService.State.Success) {
                updateInstallState(installerState)
            } else {
                detailAdapter?.status = AppDetailAdapter.Status.Idle
            }
        }
        if (this.downloading != isActive) {
            this.downloading = isActive
            updateButtons()
        }
        if (state.currentItem is DownloadService.State.Success &&
            state.currentItem.packageName == viewModel.packageName &&
            installerState == null &&
            isResumed
        ) {
            installing = InstallState.Pending
            detailAdapter?.status = AppDetailAdapter.Status.PendingInstall
            viewModel.installPackage(
                state.currentItem.packageName,
                state.currentItem.release.cacheFileName,
            )
        }
    }

    override fun onActionClick(action: AppDetailAdapter.Action) {
        when (action) {
            AppDetailAdapter.Action.INSTALL,
            AppDetailAdapter.Action.UPDATE,
                -> {
                if (Cache.getEmptySpace(requireContext()) < products.first().first.releases.first().size) {
                    MessageDialog(Message.InsufficientStorage).show(childFragmentManager)
                    return
                }
                val shizukuState = viewModel.shizukuState(requireContext())
                if (shizukuState != null && shizukuState.check) {
                    shizukuDialog(
                        context = requireContext(),
                        shizukuState = shizukuState,
                        openShizuku = { launchShizuku(requireContext()) },
                        switchInstaller = { viewModel.setDefaultInstaller() },
                    ).show()
                    return
                }
                viewModel.clearFinishedInstall()
                downloadConnection.startUpdate(
                    packageName = viewModel.packageName,
                    installedItem = installed?.installedItem,
                    products = products,
                    preferredRepositoryId = preferredRepositoryId,
                )
            }

            AppDetailAdapter.Action.LAUNCH -> {
                val launcherActivities = installed?.launcherActivities.orEmpty()
                if (launcherActivities.size >= 2) {
                    LaunchDialog(launcherActivities).show(
                        childFragmentManager,
                        LaunchDialog::class.java.name,
                    )
                } else {
                    launcherActivities.firstOrNull()?.let { startLauncherActivity(it.first) }
                }
            }

            AppDetailAdapter.Action.DETAILS -> {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:${viewModel.packageName}".toUri(),
                    ),
                )
            }

            AppDetailAdapter.Action.UNINSTALL -> viewModel.uninstallPackage()

            AppDetailAdapter.Action.CANCEL -> {
                val binder = downloadConnection.binder
                if (installing?.isCancellable == true) {
                    viewModel.removeQueue()
                } else if (downloading && binder != null) {
                    binder.cancel(viewModel.packageName)
                }
            }

            AppDetailAdapter.Action.SHARE -> {
                val repo = products[0].second
                val address = when {
                    "https://f-droid.org/repo" in repo.mirrors ->
                        "https://f-droid.org/packages/${viewModel.packageName}/"

                    "https://f-droid.org/archive/repo" in repo.mirrors ->
                        "https://f-droid.org/packages/${viewModel.packageName}/"

                    "https://apt.izzysoft.de/fdroid/repo" in repo.mirrors ->
                        "https://apt.izzysoft.de/fdroid/index/apk/${viewModel.packageName}"

                    else -> shareUrl(viewModel.packageName, repo.address)
                }
                val sendIntent = Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, address)
                    .setType("text/plain")
                startActivity(Intent.createChooser(sendIntent, null))
            }

            AppDetailAdapter.Action.SOURCE -> {
                val link = products[0].first.source
                context?.openLink(link)
            }
        }
    }

    override fun onFavouriteClicked() {
        viewModel.setFavouriteState()
    }

    override fun onCustomButtonClick(url: String) {
        context?.openLink(url)
    }

    private fun startLauncherActivity(name: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(viewModel.packageName, name))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPreferenceChanged(preference: ProductPreference) {
        updateButtons(preference)
    }

    override fun onPreferredRepositoryClick() {
        val repositories = products.map { it.second }.distinctBy { it.id }
        if (repositories.size < 2) return
        val labels = listOf(getString(stringRes.preferred_repository_automatic)) +
            repositories.map { it.name }
        val checked = repositories.indexOfFirst { it.id == preferredRepositoryId } + 1
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(stringRes.preferred_repository)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, position ->
                val repositoryId = if (position == 0) 0L else repositories[position - 1].id
                viewModel.setPreferredRepository(repositoryId)
                dialog.dismiss()
            }
            .setNegativeButton(stringRes.cancel, null)
            .show()
    }

    override fun onNoteClick() {
        val context = requireContext()
        val input = TextInputEditText(context).apply {
            setText(note)
            setSelection(note.length)
            minLines = 2
        }
        val inputLayout = TextInputLayout(context).apply {
            hint = getString(stringRes.note_hint)
            addView(input)
        }
        val container = FrameLayout(context).apply {
            val horizontal = 24.dpToPx.roundToInt()
            setPadding(horizontal, 8.dpToPx.roundToInt(), horizontal, 0)
            addView(inputLayout)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(stringRes.note)
            .setView(container)
            .setPositiveButton(stringRes.save) { _, _ ->
                viewModel.setNote(input.text?.toString().orEmpty())
            }
            .setNegativeButton(stringRes.cancel, null)
            .apply {
                if (note.isNotEmpty()) {
                    setNeutralButton(stringRes.delete) { _, _ -> viewModel.setNote("") }
                }
            }
            .show()
    }

    override fun onPermissionsClick(group: String?, permissions: List<String>) {
        MessageDialog(Message.Permissions(group, permissions))
            .show(childFragmentManager)
    }

    override fun onScreenshotClick(position: Int) {
        val (product, repository) =
            products.findSuggested(installed?.installedItem, preferredRepositoryId) ?: return
        val screenshots = product.screenshots
        val size = screenshots.count { it.type != Product.Screenshot.Type.VIDEO }
        val isRTL = context?.resources?.configuration?.layoutDirection == View.LAYOUT_DIRECTION_RTL
        if (imageViewer == null) {
            val images = screenshots.filterNot { it.type == Product.Screenshot.Type.VIDEO }
                .let { if (isRTL) it.reversed() else it }
            imageViewer = StfalconImageViewer
                .Builder(context, images) { view, current ->
                    val screenshotUrl = current.url(
                        repository = repository,
                        packageName = viewModel.packageName,
                    )
                    view.load(screenshotUrl) {
                        authentication(repository.authentication)
                        allowHardware(false)
                    }
                }
        }
        val i = if (screenshots.any { it.type == Product.Screenshot.Type.VIDEO }) {
            position - 1
        } else {
            position
        }
        imageViewer?.withStartPosition(if (isRTL) size - 1 - i else i)
        imageViewer?.show()
    }

    override fun onVideoClick(url: String) {
        context?.openLink(url)
    }

    override fun onReleaseClick(release: Release) {
        val installedItem = installed?.installedItem
        when {
            release.incompatibilities.isNotEmpty() -> {
                MessageDialog(
                    Message.ReleaseIncompatible(
                        release.incompatibilities,
                        release.platforms,
                        release.minSdkVersion,
                        release.maxSdkVersion,
                    ),
                ).show(childFragmentManager)
            }

            Cache.getEmptySpace(requireContext()) < release.size -> {
                MessageDialog(Message.InsufficientStorage).show(childFragmentManager)
            }

            installedItem != null && installedItem.versionCode > release.versionCode -> {
                MessageDialog(Message.ReleaseOlder).show(childFragmentManager)
            }

            installedItem != null && release.signature.isNotEmpty() &&
                installedItem.signature != release.signature -> {
                lifecycleScope.launch {
                    if (viewModel.shouldIgnoreSignature()) {
                        queueReleaseInstall(release, installedItem)
                    } else {
                        MessageDialog(Message.ReleaseSignatureMismatch).show(childFragmentManager)
                    }
                }
            }

            else -> {
                queueReleaseInstall(release, installedItem)
            }
        }
    }

    private fun queueReleaseInstall(release: Release, installedItem: InstalledItem?) {
        val productRepository = products.firstOrNull { (product, _) ->
            product.releases.any { it == release }
        }
        if (productRepository != null) {
            downloadConnection.binder?.enqueue(
                viewModel.packageName,
                productRepository.first.name,
                productRepository.second,
                release,
                installedItem != null,
            )
        }
    }

    override fun onRequestAddRepository(address: String) {
        mainActivity.navigateAddRepository(address)
    }

    override fun onUriClick(uri: Uri, shouldConfirm: Boolean): Boolean {
        return if (shouldConfirm && (uri.scheme == "http" || uri.scheme == "https")) {
            MessageDialog(Message.Link(uri)).show(childFragmentManager)
            true
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Failed to open url: $uri", e)
                false
            }
        }
    }

    class LaunchDialog() : DialogFragment() {
        companion object {
            private const val EXTRA_NAMES = "names"
            private const val EXTRA_LABELS = "labels"
        }

        constructor(launcherActivities: List<Pair<String, String>>) : this() {
            arguments = Bundle().apply {
                putStringArrayList(EXTRA_NAMES, ArrayList(launcherActivities.map { it.first }))
                putStringArrayList(EXTRA_LABELS, ArrayList(launcherActivities.map { it.second }))
            }
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
            val names = requireArguments().getStringArrayList(EXTRA_NAMES)!!
            val labels = requireArguments().getStringArrayList(EXTRA_LABELS)!!
            return MaterialAlertDialogBuilder(requireContext())
                .setTitle(stringRes.launch)
                .setItems(labels.toTypedArray()) { _, position ->
                    (parentFragment as AppDetailFragment)
                        .startLauncherActivity(names[position])
                }
                .setNegativeButton(stringRes.cancel, null)
                .create()
        }
    }
}
