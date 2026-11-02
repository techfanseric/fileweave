/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.ClipData
import android.content.Context
import me.zhanghai.android.files.ui.ActionPanel
import me.zhanghai.android.files.ui.ViewOptionsDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.leinardi.android.speeddial.SpeedDialView
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.compat.checkSelfPermissionCompat
import me.zhanghai.android.files.compat.setGroupDividerEnabledCompat
import me.zhanghai.android.files.databinding.FileListFragmentAppBarIncludeBinding
import me.zhanghai.android.files.databinding.FileEmptyStateBinding
import me.zhanghai.android.files.databinding.FilePreviewPaneBinding
import me.zhanghai.android.files.databinding.FileListFragmentBinding
import me.zhanghai.android.files.databinding.FileListFragmentBottomBarIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentContentIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentSpeedDialIncludeBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.extension
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileSortOptions.By
import me.zhanghai.android.files.filelist.FileSortOptions.Order
import me.zhanghai.android.files.fileproperties.FilePropertiesDialogFragment
import me.zhanghai.android.files.navigation.BookmarkDirectories
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.NavigationFragment
import me.zhanghai.android.files.navigation.NavigationRootMapLiveData
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.terminal.Terminal
import me.zhanghai.android.files.ui.AppBarLayoutExpandHackListener
import me.zhanghai.android.files.ui.CoordinatorAppBarLayout
import me.zhanghai.android.files.ui.DrawerLayoutOnBackPressedCallback
import me.zhanghai.android.files.ui.FixQueryChangeSearchView
import me.zhanghai.android.files.ui.OverlayToolbarActionMode
import me.zhanghai.android.files.ui.PersistentBarLayout
import me.zhanghai.android.files.ui.PersistentBarLayoutToolbarActionMode
import me.zhanghai.android.files.ui.PersistentDrawerLayout
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.ui.SpeedDialViewOnBackPressedCallback
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.files.ui.ToolbarActionMode
import me.zhanghai.android.files.util.DebouncedRunnable
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.asFileName
import me.zhanghai.android.files.util.asFileNameOrNull
import me.zhanghai.android.files.util.checkSelfPermission
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.create
import me.zhanghai.android.files.util.createInstallPackageIntent
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createManageAppAllFilesAccessPermissionIntent
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.hasSw600Dp
import me.zhanghai.android.files.util.isOrientationLandscape
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.setOnEditorConfirmActionListener
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.files.viewer.image.ImageViewerActivity

class FileListFragment : Fragment(), BreadcrumbLayout.Listener, FileListAdapter.Listener,
    ConfirmReplaceFileDialogFragment.Listener, OpenApkDialogFragment.Listener,
    ConfirmDeleteFilesDialogFragment.Listener, CreateArchiveDialogFragment.Listener,
    RenameFileDialogFragment.Listener, CreateFileDialogFragment.Listener,
    CreateDirectoryDialogFragment.Listener, NavigateToPathDialogFragment.Listener,
    NavigationFragment.Listener, ShowRequestAllFilesAccessRationaleDialogFragment.Listener,
    ShowRequestNotificationPermissionRationaleDialogFragment.Listener,
    ShowRequestNotificationPermissionInSettingsRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionInSettingsRationaleDialogFragment.Listener {
    private val requestAllFilesAccessLauncher = registerForActivityResult(
        RequestAllFilesAccessContract(), this::onRequestAllFilesAccessResult
    )
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(), this::onRequestStoragePermissionResult
    )
    private val requestStoragePermissionInSettingsLauncher = registerForActivityResult(
        RequestPermissionInSettingsContract(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
        this::onRequestStoragePermissionInSettingsResult
    )
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(), this::onRequestNotificationPermissionResult
    )
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestNotificationPermissionInSettingsLauncher = registerForActivityResult(
        RequestPermissionInSettingsContract(android.Manifest.permission.POST_NOTIFICATIONS),
        this::onRequestNotificationPermissionInSettingsResult
    )

    private val args by args<Args>()
    private val argsPath by lazy { args.intent.extraPath }
    private val startsAtHome: Boolean
        get() = argsPath == null && args.intent.action in listOf(null, Intent.ACTION_MAIN)

    private val viewModel by viewModels { { FileListViewModel() } }

    private lateinit var binding: Binding

    private lateinit var navigationFragment: NavigationFragment

    private lateinit var menuBinding: MenuBinding

    private lateinit var overlayActionMode: ToolbarActionMode

    private lateinit var bottomActionMode: ToolbarActionMode

    private lateinit var dragSelection: DragSelectTouchListener
    private lateinit var preview: FileListPreview

    private lateinit var layoutManager: GridLayoutManager

    private lateinit var adapter: FileListAdapter
    private lateinit var recentFooterAdapter: RecentFilesFooterAdapter

    private val debouncedSearchRunnable = DebouncedRunnable(Handler(Looper.getMainLooper()), 1000) {
        if (!isResumed || !viewModel.isSearchViewExpanded) {
            return@DebouncedRunnable
        }
        val query = viewModel.searchViewQuery
        viewModel.search(query)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        Binding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (savedInstanceState == null) {
            navigationFragment = NavigationFragment()
            childFragmentManager.commit { add(R.id.navigationFragment, navigationFragment) }
        } else {
            navigationFragment = childFragmentManager.findFragmentById(R.id.navigationFragment)
                as NavigationFragment
        }
        navigationFragment.listener = this
        val activity = requireActivity() as AppCompatActivity
        // The path breadcrumb overlaid in the toolbar replaces the fixed title line, so the
        // title stays empty and vertical space is saved.
        activity.setTitle("")
        activity.setSupportActionBar(binding.toolbar)
        overlayActionMode = OverlayToolbarActionMode(binding.overlayToolbar, binding.toolbar)
        bottomActionMode = PersistentBarLayoutToolbarActionMode(
            binding.persistentBarLayout, binding.bottomBarLayout, binding.bottomToolbar
        )
        val contentLayoutInitialPaddingBottom = binding.contentLayout.paddingBottom
        binding.appBarLayout.addOnOffsetChangedListener { _, verticalOffset ->
            binding.contentLayout.updatePaddingRelative(
                bottom = contentLayoutInitialPaddingBottom +
                    binding.appBarLayout.totalScrollRange + verticalOffset
            )
        }
        binding.appBarLayout.syncBackgroundColorTo(binding.overlayToolbar)
        binding.breadcrumbLayout.setListener(this)
        binding.toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateToolbarActions()
        }
        if (!(activity.hasSw600Dp && activity.isOrientationLandscape)) {
            binding.swipeRefreshLayout.setProgressViewEndTarget(
                true, binding.swipeRefreshLayout.progressViewEndOffset
            )
        }
        binding.swipeRefreshLayout.setOnRefreshListener { this.refresh() }
        layoutManager = GridLayoutManager(activity, 1)
        binding.recyclerView.layoutManager = layoutManager
        me.zhanghai.android.files.viewer.audio.AudioBar(this, binding.root as android.widget.FrameLayout)
        adapter = FileListAdapter(this)
        recentFooterAdapter = RecentFilesFooterAdapter { viewModel.loadMoreRecentFiles() }
        binding.recyclerView.adapter = ConcatAdapter(adapter, recentFooterAdapter)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (position >= adapter.itemCount) layoutManager.spanCount else 1
        }
        dragSelection = DragSelectTouchListener(binding.recyclerView, adapter::updateDragSelection, adapter::endDragSelection)
        binding.recyclerView.addOnItemTouchListener(dragSelection)
        preview = FileListPreview(FilePreviewPaneBinding.bind(
            binding.root.findViewById(R.id.previewPane)
        ), ::openFileDirect) { FilePropertiesDialogFragment.show(it, this) }
        binding.root.findViewById<View>(R.id.filePanes).addOnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ -> updatePreviewLayout()
        }
        // Use the measured file area, including changes while the persistent drawer animates.
        // Screen configuration width includes the drawer and can overestimate available space.
        binding.recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateSpanCount()
        }
        val fastScroller = FastScrollerBuilder(binding.recyclerView)
            .setPopupTextProvider { view, position ->
                if (position in 0 until adapter.itemCount) adapter.getPopupText(view, position)
                else getString(R.string.fw_recent_load_more)
            }
            .useMd2Style().build()
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView, fastScroller)
        )
        binding.speedDialView.inflate(R.menu.file_list_speed_dial)
        binding.speedDialView.setOnActionSelectedListener {
            when (it.id) {
                R.id.action_create_file -> showCreateFileDialog()
                R.id.action_create_directory -> showCreateDirectoryDialog()
            }
            // Returning false causes the speed dial to close without animation.
            //return false
            binding.speedDialView.close()
            true
        }

        val viewLifecycleOwner = viewLifecycleOwner
        addOnBackPressedCallback(
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (viewModel.isSearchViewExpanded) {
                        menuBinding.searchItem.collapseActionView()
                    } else if (viewModel.isQuickFiltersExpanded) {
                        viewModel.isQuickFiltersExpanded = false
                    } else {
                        navigateUp()
                    }
                }
            }
                .also { callback ->
                    fun updateEnabled() {
                        callback.isEnabled = viewModel.isSearchViewExpanded || viewModel.isQuickFiltersExpanded ||
                            (!viewModel.isRecentFiles && viewModel.canNavigateUpBreadcrumb)
                    }
                    viewModel.breadcrumbLiveData.observe(viewLifecycleOwner) { updateEnabled() }
                    viewModel.recentFilesLiveData.observe(viewLifecycleOwner) { updateEnabled() }
                    viewModel.searchViewExpandedLiveData.observe(viewLifecycleOwner) { updateEnabled() }
                    viewModel.quickFiltersExpandedLiveData.observe(viewLifecycleOwner) { updateEnabled() }
                }
        )
        addOnBackPressedCallback(overlayActionMode.onBackPressedCallback)
        addOnBackPressedCallback(SpeedDialViewOnBackPressedCallback(binding.speedDialView))
        binding.drawerLayout?.let {
            addOnBackPressedCallback(DrawerLayoutOnBackPressedCallback(it))
        }

        if (!viewModel.hasTrail) {
            var path = argsPath
            val intent = args.intent
            var pickOptions: PickOptions? = null
            when (val action = intent.action) {
                Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT,
                Intent.ACTION_CREATE_DOCUMENT -> {
                    val mode = if (action == Intent.ACTION_CREATE_DOCUMENT) {
                        PickOptions.Mode.CREATE_FILE
                    } else {
                        PickOptions.Mode.OPEN_FILE
                    }
                    val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
                    val fileName = if (mode == PickOptions.Mode.CREATE_FILE) {
                        intent.getStringExtra(Intent.EXTRA_TITLE)?.asFileNameOrNull()?.value
                            ?: mimeType.extension?.let { "file.$it" } ?: "file"
                    } else {
                        null
                    }
                    val readOnly = action == Intent.ACTION_GET_CONTENT
                    val extraMimeTypes = if (mode == PickOptions.Mode.OPEN_FILE) {
                        intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                            ?.mapNotNull { it.asMimeTypeOrNull() }?.takeIfNotEmpty()
                    } else {
                        null
                    }
                    val mimeTypes = extraMimeTypes ?: listOf(mimeType)
                    val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    val allowMultiple = mode != PickOptions.Mode.CREATE_FILE &&
                        intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    pickOptions =
                        PickOptions(mode, fileName, readOnly, mimeTypes, localOnly, allowMultiple)
                }
                Intent.ACTION_OPEN_DOCUMENT_TREE -> {
                    val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    pickOptions = PickOptions(
                        PickOptions.Mode.OPEN_DIRECTORY, null, false, emptyList(), localOnly, false
                    )
                }
                ACTION_VIEW_DOWNLOADS ->
                    path = Paths.get(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ).path
                    )
                else ->
                    if (path != null) {
                        val mimeType = intent.type?.asMimeTypeOrNull()
                        if (mimeType != null && path.isArchiveFile(mimeType)) {
                            path = path.createArchiveRootPath()
                        }
                    }
            }
            if (path == null) {
                path = Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
            }
            viewModel.resetTo(path)
            viewModel.isRecentFiles = startsAtHome
            if (pickOptions != null) {
                viewModel.pickOptions = pickOptions
            }
        }
        childFragmentManager.setFragmentResultListener(
            RecentFilesSettingsDialogFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, _ -> if (viewModel.isRecentFiles) refresh() }
        viewModel.recentFilesLiveData.observe(viewLifecycleOwner) { onRecentFilesChanged(it) }
        viewModel.currentPathLiveData.observe(viewLifecycleOwner) { onCurrentPathChanged(it) }
        setUpQuickFilters()
        viewModel.searchStateLiveData.observe(viewLifecycleOwner) { updateQuickFilters() }
        viewModel.quickFiltersExpandedLiveData.observe(viewLifecycleOwner) { updateQuickFilters() }
        viewModel.searchViewExpandedLiveData.observe(viewLifecycleOwner) {
            onSearchViewExpandedChanged(it)
        }
        viewModel.breadcrumbLiveData.observe(viewLifecycleOwner) {
            // At a storage root the single breadcrumb segment reads as the app title
            // ("文件") rather than the storage name, keeping the toolbar familiar.
            val data = if (it.paths.size == 1) {
                it.copy(nameProducers = listOf { context -> context.getString(R.string.file_list_title) })
            } else {
                it
            }
            binding.breadcrumbLayout.setData(data)
        }
        viewModel.viewTypeLiveData.observe(viewLifecycleOwner) { onViewTypeChanged(it) }
        // Live data only calls observeForever() on its sources when it is active, so we have to
        // make view type live data active first (so that it can load its initial value) before we
        // register another observer that needs to get the view type.
        if (binding.persistentDrawerLayout != null) {
            Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.observe(viewLifecycleOwner) {
                onPersistentDrawerOpenChanged(it)
            }
        }
        viewModel.sortOptionsLiveData.observe(viewLifecycleOwner) { onSortOptionsChanged(it) }
        viewModel.viewSortPathSpecificLiveData.observe(viewLifecycleOwner) {
            onViewSortPathSpecificChanged(it)
        }
        viewModel.pickOptionsLiveData.observe(viewLifecycleOwner) { onPickOptionsChanged(it) }
        viewModel.selectedFilesLiveData.observe(viewLifecycleOwner) { onSelectedFilesChanged(it) }
        viewModel.pasteStateLiveData.observe(viewLifecycleOwner) { onPasteStateChanged(it) }
        Settings.FILE_LIST_SHOW_DETAILS.observe(viewLifecycleOwner) {
            adapter.showDetails = it
            updateViewSortMenuItems()
        }
        Settings.FILE_NAME_ELLIPSIZE.observe(viewLifecycleOwner) { onFileNameEllipsizeChanged(it) }
        viewModel.fileListLiveData.observe(viewLifecycleOwner) { onFileListChanged(it) }
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.observe(viewLifecycleOwner) {
            onShowHiddenFilesChanged(it)
        }
    }

    override fun onResume() {
        super.onResume()

        if (!viewModel.isNotificationPermissionRequested) {
            ensureStorageAccess()
        }
        if (!viewModel.isStorageAccessRequested) {
            ensureNotificationPermission()
        }
        if (viewModel.isRecentFiles) refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        menuBinding = MenuBinding.inflate(menu, inflater)
        menuBinding.menu.findItem(R.id.action_view_model).subMenu!!.setGroupDividerEnabledCompat(true)
        setUpSearchView()
        updatePickerTools()
    }

    private fun updatePickerTools() = updateToolbarActions()

    private fun updateToolbarActions() {
        if (!this::menuBinding.isInitialized) return
        val toolbar = binding.toolbar
        val density = resources.displayMetrics.density
        val widthDp = toolbar.width / density
        val fontScale = resources.configuration.fontScale
        val inlineCount = FileToolbarLayout.inlineSecondaryActions(
            widthDp, fontScale, viewModel.pickOptions != null, viewModel.isSearchViewExpanded
        )
        val filtering = menuBinding.menu.findItem(R.id.action_search_filters)
        val viewing = menuBinding.viewSortItem
        val filterInline = inlineCount >= 1
        val viewInline = inlineCount >= 2 && !viewModel.isSearchViewExpanded
        if (filtering.isVisible != filterInline) filtering.isVisible = filterInline
        if (viewing.isVisible != viewInline) viewing.isVisible = viewInline
        val model = menuBinding.menu.findItem(R.id.action_page_model).subMenu!!
        // The collapsed quick-filter entry already leads to the full filter dialog via Format.
        // Keep one filter entry in More on compact layouts.
        model.findItem(R.id.action_filter_files).isVisible = filterInline
        for ((index, pair) in listOf(filtering to R.id.fw_picker_filter,
            viewing to R.id.fw_picker_view_options).withIndex()) {
            val (action, id) = pair
            if (!action.isVisible && model.findItem(id) == null) {
                model.add(0, id, 5 + index, action.title)
            } else if (action.isVisible) model.removeItem(id)
        }
        // The title and path share the toolbar only outside pickers. Base the reserved space
        // on the actual visible controls, rather than always subtracting four action cells.
        val end = ((10 + (2 + (if (filterInline) 1 else 0) +
            (if (viewInline) 1 else 0)) * 48) * density).toInt()
        val titles = listOf(binding.breadcrumbLayout, binding.root.findViewById<View>(R.id.recentFilesTitle))
        for (title in titles) {
            if (title.parent === toolbar.parent) {
                val params = title.layoutParams as ViewGroup.MarginLayoutParams
                if (params.marginEnd != end) {
                    params.marginEnd = end
                    title.layoutParams = params
                }
            }
        }
    }

    private fun setUpSearchView() {
        val searchView = menuBinding.searchItem.actionView as FixQueryChangeSearchView
        // MenuItem.OnActionExpandListener.onMenuItemActionExpand() is called before SearchView
        // resets the query.
        searchView.setOnSearchClickListener {
            viewModel.isQuickFiltersExpanded = false
            viewModel.isSearchViewExpanded = true
            binding.breadcrumbLayout.isVisible = false
            searchView.setQuery(viewModel.searchViewQuery, false)
            debouncedSearchRunnable()
        }
        // SearchView.OnCloseListener.onClose() is not always called.
        menuBinding.searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.isSearchViewExpanded = false
                viewModel.isQuickFiltersExpanded = false
                debouncedSearchRunnable.cancel()
                viewModel.clearSearchQuery()
                updateBreadcrumbVisibility()
                return true
            }
        })
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                debouncedSearchRunnable.cancel()
                viewModel.search(query)
                return true
            }

            override fun onQueryTextChange(query: String): Boolean {
                if (searchView.shouldIgnoreQueryChange) {
                    return false
                }
                viewModel.searchViewQuery = query
                debouncedSearchRunnable()
                return false
            }
        })
        if (viewModel.isSearchViewExpanded) {
            menuBinding.searchItem.expandActionView()
        }
    }

    private fun collapseSearchView() {
        if (this::menuBinding.isInitialized && menuBinding.searchItem.isActionViewExpanded) {
            menuBinding.searchItem.collapseActionView()
        }
        debouncedSearchRunnable.cancel()
        viewModel.stopSearching()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateViewSortMenuItems()
        updateSelectAllMenuItem()
        updateShowHiddenFilesMenuItem()
        val recent = viewModel.isRecentFiles
        menu.findItem(R.id.action_recent_scope).isVisible = recent
        menu.findItem(R.id.action_folder_share).isVisible = !recent && viewModel.pickOptions == null
        menu.findItem(R.id.action_folder_tools).isVisible = !recent && viewModel.pickOptions == null
        menu.findItem(R.id.action_share_mac_dir).isVisible = !currentPath.isArchivePath
        menuBinding.searchItem.isVisible = true
        for (id in intArrayOf(R.id.action_navigate_up, R.id.action_new_task,
            R.id.action_share, R.id.action_lan_share, R.id.action_copy_path,
            R.id.action_open_in_terminal, R.id.action_add_bookmark, R.id.action_create_shortcut)) {
            menu.findItem(id).isVisible = !recent
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_page_more -> {
                val path = currentPath
                ActionPanel(requireContext(), viewLifecycleOwner,
                    if (viewModel.isRecentFiles) getString(R.string.fw_recent_files) else
                        me.zhanghai.android.files.navigation.NavigationRootMapLiveData.valueCompat[path]
                            ?.getName(requireContext()) ?: path.name,
                    menuBinding.menu.findItem(R.id.action_page_model).subMenu!!,
                    keepOpen = { it.itemId in setOf(R.id.action_filter_files, R.id.action_navigate_to) },
                    isValid = { isAdded && currentPath == path }) { onOptionsItemSelected(it) }.show()
                true
            }
            R.id.action_view_sort, R.id.fw_picker_view_options -> {
                ViewOptionsDialog.show(requireContext(), viewLifecycleOwner,
                    menuBinding.menu.findItem(R.id.action_view_model).subMenu!!,
                    viewModel.isRecentFiles) {
                    onOptionsItemSelected(it)
                    updateViewSortMenuItems()
                    updateShowHiddenFilesMenuItem()
                }
                true
            }
            R.id.action_filter_files -> { showAdvancedSearchFilters(); true }
            R.id.action_share_mac_dir -> {
                startActivity(Intent(requireContext(), me.zhanghai.android.files.bridge.BridgeActivity::class.java).apply { extraPath = currentPath })
                true
            }
            R.id.action_recent_scope -> { configureRecentFiles(); true }
            R.id.action_lan_share_hub -> {
                startActivity(Intent(requireContext(), me.zhanghai.android.files.lanshare.LanShareActivity::class.java))
                true
            }
            android.R.id.home -> {
                binding.drawerLayout?.openDrawer(GravityCompat.START)
                if (binding.persistentDrawerLayout != null) {
                    Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(
                        !Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
                    )
                }
                true
            }
            R.id.action_view_list -> {
                viewModel.viewType = FileViewType.LIST
                true
            }
            R.id.action_search_filters, R.id.fw_picker_filter -> {
                viewModel.isQuickFiltersExpanded = !viewModel.isQuickFiltersExpanded
                true
            }
            R.id.action_view_compact -> {
                viewModel.viewType = FileViewType.COMPACT
                true
            }
            R.id.action_view_preview -> {
                viewModel.viewType = FileViewType.PREVIEW
                true
            }
            R.id.action_show_details -> {
                Settings.FILE_LIST_SHOW_DETAILS.putValue(!Settings.FILE_LIST_SHOW_DETAILS.valueCompat)
                true
            }
            R.id.action_view_grid -> {
                viewModel.viewType = FileViewType.GRID
                true
            }
            R.id.action_sort_by_name -> {
                viewModel.setSortBy(By.NAME)
                true
            }
            R.id.action_sort_by_type -> {
                viewModel.setSortBy(By.TYPE)
                true
            }
            R.id.action_sort_by_size -> {
                viewModel.setSortBy(By.SIZE)
                true
            }
            R.id.action_sort_by_last_modified -> {
                viewModel.setSortBy(By.LAST_MODIFIED)
                true
            }
            R.id.action_sort_order_ascending -> {
                viewModel.setSortOrder(
                    if (!menuBinding.sortOrderAscendingItem.isChecked) {
                        Order.ASCENDING
                    } else {
                        Order.DESCENDING
                    }
                )
                true
            }
            R.id.action_sort_directories_first -> {
                viewModel.setSortDirectoriesFirst(!menuBinding.sortDirectoriesFirstItem.isChecked)
                true
            }
            R.id.action_view_sort_path_specific -> {
                viewModel.isViewSortPathSpecific = !menuBinding.viewSortPathSpecificItem.isChecked
                true
            }
            R.id.action_fw_vault -> {
                startActivity(Intent(requireContext(), me.zhanghai.android.files.vault.VaultActivity::class.java)); true
            }
            R.id.action_fw_bridge -> {
                startActivity(Intent(requireContext(), me.zhanghai.android.files.bridge.BridgeActivity::class.java)); true
            }
            R.id.action_new_task -> {
                newTask()
                true
            }
            R.id.action_navigate_up -> {
                navigateUp()
                true
            }
            R.id.action_navigate_to -> {
                showNavigateToPathDialog()
                true
            }
            R.id.action_refresh -> {
                refresh()
                true
            }
            R.id.action_select_all -> {
                selectAllFiles()
                true
            }
            R.id.action_show_hidden_files -> {
                setShowHiddenFiles(!menuBinding.showHiddenFilesItem.isChecked)
                true
            }
            R.id.action_lan_share -> {
                shareOnLan(listOf(currentPath))
                true
            }
            R.id.action_share -> {
                share()
                true
            }
            R.id.action_copy_path -> {
                copyPath()
                true
            }
            R.id.action_open_in_terminal -> {
                openInTerminal()
                true
            }
            R.id.action_add_bookmark -> {
                addBookmark()
                true
            }
            R.id.action_create_shortcut -> {
                createShortcut()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (overlayActionMode.isActive && viewModel.pickOptions?.allowMultiple != false &&
            event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_A) { selectAllFiles(); return true }
        if (event.isAltPressed && keyCode == KeyEvent.KEYCODE_DPAD_UP) { navigateUp(); return true }
        if (!overlayActionMode.isActive && !bottomActionMode.isActive && event.isCtrlPressed && requireActivity().currentFocus !is android.widget.EditText) {
            when (keyCode) {
                KeyEvent.KEYCODE_L -> { showNavigateToPathDialog(); return true }
                KeyEvent.KEYCODE_R -> { refresh(); return true }
                KeyEvent.KEYCODE_A -> { selectAllFiles(); return true }
                KeyEvent.KEYCODE_H -> { setShowHiddenFiles(!Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat); return true }
            }
        }
        if (bottomActionMode.isActive) {
            val menu = bottomActionMode.menu
            menu.setQwertyMode(
                KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC
            )
            if (menu.performShortcut(keyCode, event, 0)) {
                return true
            }
        }
        if (overlayActionMode.isActive) {
            val menu = overlayActionMode.menu
            menu.setQwertyMode(
                KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC
            )
            if (menu.performShortcut(keyCode, event, 0)) {
                return true
            }
        }
        return false
    }

    private fun onPersistentDrawerOpenChanged(open: Boolean) {
        binding.persistentDrawerLayout?.let {
            if (open) {
                it.openDrawer(GravityCompat.START)
            } else {
                it.closeDrawer(GravityCompat.START)
            }
        }
        updateSpanCount()
    }

    private fun onCurrentPathChanged(path: Path) {
        ActionPanel.dismiss(requireContext())
        preview.clear()
        updateOverlayToolbar()
        updateBottomToolbar()
    }

    private fun setUpQuickFilters() {
        val types = mapOf(R.id.quickFilterImage to SearchFileType.IMAGE,
            R.id.quickFilterVideo to SearchFileType.VIDEO, R.id.quickFilterAudio to SearchFileType.AUDIO)
        types.forEach { (id, type) ->
            binding.root.findViewById<View>(id).setOnClickListener { applyQuickType(type) }
        }
        binding.root.findViewById<View>(R.id.quickFilterAll).setOnClickListener {
            applySearchFilters(SearchFilters(recursive = viewModel.isRecentFiles ||
                (viewModel.isSearchViewExpanded && viewModel.searchState.filters.recursive)))
        }
        binding.root.findViewById<View>(R.id.quickFilterFormat).setOnClickListener { showQuickFilterMenu() }
        binding.root.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterSelected)
            .setOnCloseIconClickListener { applyQuickType(SearchFileType.ANY) }
        binding.root.findViewById<com.google.android.material.chip.Chip>(R.id.quickFilterDates).apply {
            setOnClickListener { isChecked = true; showAdvancedSearchFilters() }
            setOnCloseIconClickListener { applySearchFilters(viewModel.searchState.filters.copy(dates = null)) }
        }
        binding.root.findViewById<View>(R.id.quickFilterScope).setOnClickListener {
            applySearchFilters(viewModel.searchState.filters.copy(recursive = !viewModel.searchState.filters.recursive))
        }
    }

    private fun applyQuickType(type: SearchFileType, extension: String = "") {
        val state = viewModel.searchState
        applySearchFilters(state.filters.copy(type = type, extension = extension,
            recursive = if (!state.isSearching && !viewModel.isRecentFiles && !viewModel.isSearchViewExpanded)
                false else state.filters.recursive))
    }

    private fun showQuickFilterMenu() {
        showAdvancedSearchFilters()
    }

    private fun showAdvancedSearchFilters() {
        if (childFragmentManager.findFragmentByTag("search_filters") == null) {
            SearchFiltersDialogFragment.newInstance(viewModel.searchState.filters, viewModel.isRecentFiles)
                .show(childFragmentManager, "search_filters")
        }
    }

    private fun updateQuickFilters() {
        val filters = viewModel.searchState.filters
        val hasFilters = filters.type != SearchFileType.ANY || filters.dates != null
        binding.root.findViewById<View>(R.id.quickFiltersScroll).isVisible =
            viewModel.selectedFiles.isEmpty() &&
                (viewModel.isSearchViewExpanded || viewModel.isQuickFiltersExpanded || hasFilters)
        fun chip(id: Int) = binding.root.findViewById<com.google.android.material.chip.Chip>(id)
        chip(R.id.quickFilterAll).isChecked = filters.type == SearchFileType.ANY && filters.dates == null
        chip(R.id.quickFilterImage).isChecked = filters.type == SearchFileType.IMAGE
        chip(R.id.quickFilterVideo).isChecked = filters.type == SearchFileType.VIDEO
        chip(R.id.quickFilterAudio).isChecked = filters.type == SearchFileType.AUDIO
        chip(R.id.quickFilterSelected).apply {
            isVisible = filters.type == SearchFileType.EXTENSION || filters.type == SearchFileType.MARKDOWN
            text = if (filters.type == SearchFileType.MARKDOWN) "Markdown" else filters.extension.uppercase(java.util.Locale.ROOT)
            isChecked = true
            setOnClickListener { isChecked = true; showQuickFilterMenu() }
        }
        chip(R.id.quickFilterDates).apply {
            isVisible = filters.dates != null
            val range = filters.dates
            text = getString(if (filters.dateField == SearchDateField.CREATED) R.string.fw_filter_created_dates else R.string.fw_filter_modified_dates,
                listOfNotNull(range?.start?.toString(), range?.end?.toString()).distinct().joinToString(" – "))
            isChecked = true
        }
        chip(R.id.quickFilterScope).apply {
            isVisible = viewModel.searchState.isSearching && !viewModel.isRecentFiles
            setText(if (filters.recursive) R.string.fw_filter_recursive else R.string.fw_filter_current_folder)
            isChecked = filters.recursive
        }
    }

    fun applySearchFilters(filters: SearchFilters) {
        ActionPanel.dismiss(requireContext())
        debouncedSearchRunnable.cancel()
        viewModel.setSearchFilters(filters)
        viewModel.isQuickFiltersExpanded = false
        // Clicking an already selected checkable chip must keep its visible state in sync.
        updateQuickFilters()
    }

    private fun onSearchViewExpandedChanged(expanded: Boolean) {
        updateQuickFilters()
        updateViewSortMenuItems()
        updateBreadcrumbVisibility()
    }

    private fun updateBreadcrumbVisibility() {
        binding.breadcrumbLayout.isVisible =
            !viewModel.isRecentFiles && binding.toolbar.subtitle == null && !viewModel.isSearchViewExpanded
        binding.root.findViewById<View>(R.id.recentFilesTitle).isVisible =
            viewModel.isRecentFiles && binding.toolbar.subtitle == null && !viewModel.isSearchViewExpanded
    }

    private fun onFileListChanged(stateful: Stateful<List<FileItem>>) {
        dragSelection.stop()
        val files = stateful.value
        preview.refresh(files.orEmpty())
        val isSearching = viewModel.searchState.isSearching
        // The breadcrumb shares the toolbar row with the subtitle: loading feedback comes from
        // the progress indicator and the swipe refresh spinner, so only an error takes over
        // the toolbar, with the breadcrumb yielding while the subtitle is showing.
        if (stateful is Failure) {
            binding.toolbar.setSubtitle(R.string.error)
        } else {
            binding.toolbar.subtitle = null
        }
        updateBreadcrumbVisibility()
        val hasFiles = !files.isNullOrEmpty()
        binding.swipeRefreshLayout.isRefreshing = stateful is Loading &&
            !viewModel.recentFilesLoadingMore && (hasFiles || isSearching)
        updateRecentFooter()
        binding.progress.fadeToVisibilityUnsafe(stateful is Loading && !(hasFiles || isSearching))
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && !hasFiles)
        val throwable = (stateful as? Failure)?.throwable
        if (throwable != null) {
            throwable.printStackTrace()
            val error = throwable.toString()
            if (hasFiles) {
                showToast(error)
            } else {
                binding.errorText.text = error
            }
        }
        FileEmptyStateBinding.bind(binding.emptyView).apply {
            stateTitle.setText(when {
                isSearching -> R.string.fw_search_no_results
                viewModel.isRecentFiles -> R.string.fw_recent_empty
                else -> R.string.file_list_empty
            })
            stateHint.setText(when {
                isSearching -> R.string.fw_search_no_results_hint
                viewModel.isRecentFiles -> R.string.fw_recent_empty_hint
                else -> R.string.fw_directory_empty_hint
            })
        }
        binding.emptyView.fadeToVisibilityUnsafe(stateful is Success && !hasFiles)
        if (files != null) {
            updateAdapterFileList()
        } else {
            // This resets animation as well.
            adapter.clear()
        }
        if (stateful is Success) {
            if (!viewModel.isRecentFiles) viewModel.pendingState?.let { layoutManager.onRestoreInstanceState(it) }
        }
    }

    private fun updateRecentFooter() {
        recentFooterAdapter.update(
            viewModel.isRecentFiles && viewModel.recentFilesHasMore,
            viewModel.recentFilesLoading,
            viewModel.recentFilesLoadingMore,
            viewModel.recentFilesLoadMoreFailed
        )
    }

    private fun onViewTypeChanged(viewType: FileViewType) {
        dragSelection.stop()
        preview.clear()
        updatePreviewLayout()
        updateSpanCount()
        adapter.viewType = viewType
        updateViewSortMenuItems()
    }

    private fun updatePreviewLayout() {
        val panes = binding.root.findViewById<View>(R.id.filePanes)
        val density = resources.displayMetrics.density
        val visible = FileListLayout.showPreview(
            viewModel.viewType, panes.width / density,
            resources.configuration.fontScale, viewModel.pickOptions != null
        )
        preview.setVisible(visible)
        val params = binding.swipeRefreshLayout.layoutParams as android.widget.LinearLayout.LayoutParams
        val listWidth = if (visible) maxOf(
            (320 * resources.configuration.fontScale.coerceAtLeast(1f) * density).toInt(),
            (panes.width * 0.4f).toInt()
        ) else 0
        val weight = if (visible) 0f else 1f
        if (params.width != listWidth || params.weight != weight) {
            params.width = listWidth
            params.weight = weight
            binding.swipeRefreshLayout.layoutParams = params
        }
        preview.resize(panes.height)
    }

    override fun onStop() {
        dragSelection.stop()
        preview.clear()
        super.onStop()
    }

    private fun updateSpanCount() {
        val recyclerView = binding.recyclerView
        val width = recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight
        if (width <= 0) {
            return
        }
        val configuration = resources.configuration
        val spanCount = FileListLayout.spanCount(
            viewModel.viewType, width / resources.displayMetrics.density, configuration.fontScale
        )
        if (layoutManager.spanCount == spanCount) {
            return
        }
        // Keep the current file in view when folding, resizing or toggling the side navigation.
        val position = layoutManager.findFirstVisibleItemPosition()
        val offset = layoutManager.findViewByPosition(position)?.let {
            layoutManager.getDecoratedTop(it) - recyclerView.paddingTop
        } ?: 0
        layoutManager.spanCount = spanCount
        if (position != RecyclerView.NO_POSITION) {
            layoutManager.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun onSortOptionsChanged(sortOptions: FileSortOptions) {
        dragSelection.stop()
        adapter.sortOptions = sortOptions
        updateViewSortMenuItems()
    }

    private fun onViewSortPathSpecificChanged(pathSpecific: Boolean) {
        updateViewSortMenuItems()
    }

    private fun updateViewSortMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val searchViewExpanded = viewModel.isSearchViewExpanded
        menuBinding.menu.findItem(R.id.action_view_model).subMenu!!.setGroupVisible(R.id.group_sort, !viewModel.isRecentFiles)
        menuBinding.viewSortPathSpecificItem.isVisible = !viewModel.isRecentFiles
        updateToolbarActions()
        if (searchViewExpanded) {
            return
        }
        val viewType = viewModel.viewType
        val checkedViewTypeItem = when (viewType) {
            FileViewType.LIST -> menuBinding.viewListItem
            FileViewType.GRID -> menuBinding.viewGridItem
            FileViewType.COMPACT -> menuBinding.menu.findItem(R.id.action_view_compact)
            FileViewType.PREVIEW -> menuBinding.menu.findItem(R.id.action_view_preview)
        }
        checkedViewTypeItem.isChecked = true
        menuBinding.menu.findItem(R.id.action_show_details).apply {
            isChecked = Settings.FILE_LIST_SHOW_DETAILS.valueCompat
            isEnabled = viewType != FileViewType.GRID
        }
        val sortOptions = viewModel.sortOptions
        val checkedSortByItem = when (sortOptions.by) {
            By.NAME -> menuBinding.sortByNameItem
            By.TYPE -> menuBinding.sortByTypeItem
            By.SIZE -> menuBinding.sortBySizeItem
            By.LAST_MODIFIED -> menuBinding.sortByLastModifiedItem
        }
        checkedSortByItem.isChecked = true
        menuBinding.sortOrderAscendingItem.isChecked = sortOptions.order == Order.ASCENDING
        menuBinding.sortDirectoriesFirstItem.isChecked = sortOptions.isDirectoriesFirst
        menuBinding.viewSortPathSpecificItem.isChecked = viewModel.isViewSortPathSpecific
    }

    private fun navigateUp() {
        collapseSearchView()
        viewModel.navigateUp()
    }

    private fun showNavigateToPathDialog() {
        NavigateToPathDialogFragment.show(currentPath, this)
    }

    private fun newTask() {
        openInNewTask(currentPath)
    }

    private fun refresh() {
        viewModel.reload()
    }

    private fun setShowHiddenFiles(showHiddenFiles: Boolean) {
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(showHiddenFiles)
    }

    private fun onShowHiddenFilesChanged(showHiddenFiles: Boolean) {
        if (viewModel.isRecentFiles) {
            // Hidden rows must not fill the visible recent page or produce a false footer.
            viewModel.reload()
        } else {
            updateAdapterFileList()
        }
        updateShowHiddenFilesMenuItem()
    }

    private fun updateAdapterFileList() {
        var files = viewModel.fileListStateful.value ?: return
        if (!Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat) {
            files = files.filterNot { it.isHidden }
        }
        // Type-only filtering keeps normal directory ordering; recent always preserves index order.
        adapter.replaceListAndIsSearching(files, viewModel.searchState.query.isNotEmpty())
    }

    private fun updateShowHiddenFilesMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        menuBinding.showHiddenFilesItem.isChecked = showHiddenFiles
    }

    private fun share() {
        shareFile(currentPath, MimeType.DIRECTORY)
    }

    private fun copyPath() {
        copyPath(currentPath)
    }

    private fun openInTerminal() {
        val path = currentPath
        if (path.isLinuxPath) {
            Terminal.open(path.toFile().path, requireContext())
        } else {
            // TODO
        }
    }

    override fun navigateTo(path: Path) {
        collapseSearchView()
        if (viewModel.isRecentFiles) {
            viewModel.isRecentFiles = false
            viewModel.resetTo(path)
            return
        }
        val state = layoutManager.onSaveInstanceState()
        viewModel.navigateTo(state!!, path)
    }

    override fun showPath(path: Path) {
        NavigateToPathDialogFragment.show(path, this)
    }

    override fun copyPath(path: Path) {
        clipboardManager.copyText(path.toUserFriendlyString(), requireContext())
    }

    override fun openInNewTask(path: Path) {
        val intent = FileListActivity.createViewIntent(path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivitySafe(intent)
    }

    private fun onPickOptionsChanged(pickOptions: PickOptions?) {
        val title = if (pickOptions == null) {
            getString(if (viewModel.isRecentFiles) R.string.fw_recent_files else R.string.file_list_title)
        } else {
            val count = if (pickOptions.allowMultiple) Int.MAX_VALUE else 1
            when (pickOptions.mode) {
                PickOptions.Mode.OPEN_FILE ->
                    getQuantityString(R.plurals.file_list_title_open_file, count)
                PickOptions.Mode.CREATE_FILE -> getString(R.string.file_list_title_create_file)
                PickOptions.Mode.OPEN_DIRECTORY ->
                    getQuantityString(R.plurals.file_list_title_open_directory, count)
            }
        }
        requireActivity().title = title
        binding.toolbar.title = if (pickOptions != null) title else null
        if (pickOptions != null && binding.breadcrumbLayout.parent !== binding.appBarLayout) {
            // A picker has a task title as well as a location. Give each a real row rather
            // than drawing the breadcrumb over “Select a file” in the toolbar title area.
            (binding.breadcrumbLayout.parent as ViewGroup).removeView(binding.breadcrumbLayout)
            binding.appBarLayout.addView(binding.breadcrumbLayout, 1,
                com.google.android.material.appbar.AppBarLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (48 * resources.displayMetrics.density).toInt()
                ).apply {
                    marginStart = (16 * resources.displayMetrics.density).toInt()
                    marginEnd = marginStart
                })
        }
        updatePickerTools()
        updateSelectAllMenuItem()
        updateOverlayToolbar()
        updateBottomToolbar()
        adapter.pickOptions = pickOptions
    }

    private fun updateSelectAllMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val pickOptions = viewModel.pickOptions
        menuBinding.selectAllItem.isVisible = pickOptions == null || pickOptions.allowMultiple
    }

    private fun pickFiles(files: FileItemSet) {
        pickPaths(files.mapTo(linkedSetOf()) { it.path })
    }

    private fun pickPaths(paths: LinkedHashSet<Path>) {
        val intent = Intent().apply {
            val pickOptions = viewModel.pickOptions!!
            if (paths.size == 1) {
                val path = paths.single()
                data = path.fileProviderUri
                extraPath = path
            } else {
                val mimeTypes = pickOptions.mimeTypes.map { it.value }
                val items = paths.map { ClipData.Item(it.fileProviderUri) }
                clipData = ClipData::class.create(null, mimeTypes, items)
                extraPathList = paths.toList()
            }
            var flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (!pickOptions.readOnly) {
                flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            if (pickOptions.mode == PickOptions.Mode.OPEN_DIRECTORY) {
                flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            }
            addFlags(flags)
        }
        requireActivity().run {
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
    }

    private fun onSelectedFilesChanged(files: FileItemSet) {
        updateQuickFilters()
        updateOverlayToolbar()
        adapter.replaceSelectedFiles(files)
    }

    private fun updateOverlayToolbar() {
        val files = viewModel.selectedFiles
        if (files.isEmpty()) {
            if (overlayActionMode.isActive) {
                overlayActionMode.finish()
            }
            return
        }
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_pick)
            val menu = overlayActionMode.menu
            val isOpen = when (pickOptions.mode) {
                PickOptions.Mode.OPEN_FILE, PickOptions.Mode.OPEN_DIRECTORY -> true
                PickOptions.Mode.CREATE_FILE -> false
            }
            menu.findItem(R.id.action_open).isVisible = isOpen
            menu.findItem(R.id.action_create).isVisible = !isOpen
            menu.findItem(R.id.action_select_all).isVisible = pickOptions.allowMultiple
        } else {
            overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_select)
            val menu = overlayActionMode.menu
            val isAnyFileReadOnly = files.any { it.path.fileSystem.isReadOnly }
            menu.findItem(R.id.action_cut).isVisible = !isAnyFileReadOnly
            menu.findItem(R.id.action_fw_send_mac).isVisible = files.none { it.path.isArchivePath }
            val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
            menu.findItem(R.id.action_copy)
                .setIcon(
                    if (areAllFilesArchivePaths) {
                        R.drawable.extract_icon_control_normal_24dp
                    } else {
                        R.drawable.copy_icon_control_normal_24dp
                    }
                )
                .setTitle(
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_select_action_extract
                    } else {
                        R.string.copy
                    }
                )
            menu.findItem(R.id.action_delete).isVisible = !isAnyFileReadOnly
            val areAllFilesArchiveFiles = files.all { it.isArchiveFile }
            menu.findItem(R.id.action_extract).isVisible = areAllFilesArchiveFiles
            val isCurrentPathReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            menu.findItem(R.id.action_archive).isVisible = !isCurrentPathReadOnly
        }
        if (!overlayActionMode.isActive) {
            binding.appBarLayout.setExpanded(true)
            binding.appBarLayout.addOnOffsetChangedListener(
                AppBarLayoutExpandHackListener(binding.recyclerView)
            )
            overlayActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onOverlayActionModeMenuItemClicked(item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onOverlayActionModeFinished()
                }
            })
        }
    }

    private fun onOverlayActionModeMenuItemClicked(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_selection_more -> {
                val selected = viewModel.selectedFiles.toSet()
                ActionPanel(requireContext(), viewLifecycleOwner, overlayActionMode.title ?: "",
                    overlayActionMode.menu.findItem(R.id.action_selection_model).subMenu!!,
                    keepOpen = { it.itemId == R.id.action_archive },
                    isValid = { viewModel.selectedFiles.toSet() == selected }) { onOverlayActionModeMenuItemClicked(it) }.show()
                true
            }

            R.id.action_open -> {
                pickFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_create -> {
                confirmReplaceFile(viewModel.selectedFiles.single())
                true
            }
            R.id.action_cut -> {
                cutFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_copy -> {
                copyFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_delete -> {
                confirmDeleteFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_extract -> {
                extractFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_archive -> {
                showCreateArchiveDialog(viewModel.selectedFiles)
                true
            }
            R.id.action_fw_send_mac -> {
                startActivity(Intent(requireContext(), me.zhanghai.android.files.bridge.SendFileActivity::class.java).apply { extraPathList = viewModel.selectedFiles.map { it.path } })
                true
            }
            R.id.action_lan_share -> {
                shareOnLan(viewModel.selectedFiles.map { it.path })
                true
            }
            R.id.action_share -> {
                shareFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_select_all -> {
                selectAllFiles()
                true
            }
            else -> false
        }

    private fun onOverlayActionModeFinished() {
        viewModel.clearSelectedFiles()
    }

    private fun confirmReplaceFile(file: FileItem, setFileName: Boolean = true) {
        if (setFileName) {
            val fileName = file.name
            binding.bottomCreateFileNameEdit.setText(fileName)
            binding.bottomCreateFileNameEdit.setSelection(
                0, fileName.asFileName().baseName.length
            )
        }
        ConfirmReplaceFileDialogFragment.show(file, this)
    }

    override fun replaceFile(file: FileItem) {
        pickFiles(fileItemSetOf(file))
    }

    private fun cutFiles(files: FileItemSet) {
        viewModel.addToPasteState(false, files)
        viewModel.selectFiles(files, false)
    }

    private fun copyFiles(files: FileItemSet) {
        viewModel.addToPasteState(true, files)
        viewModel.selectFiles(files, false)
    }

    private fun confirmDeleteFiles(files: FileItemSet) {
        ConfirmDeleteFilesDialogFragment.show(files, this)
    }

    override fun deleteFiles(files: FileItemSet) {
        ActionPanel.dismiss(requireContext())
        FileJobService.delete(makePathListForJob(files), requireContext())
        viewModel.selectFiles(files, false)
    }

    private fun extractFiles(files: FileItemSet) {
        copyFiles(files.mapTo(fileItemSetOf()) { it.createDummyArchiveRoot() })
        viewModel.selectFiles(files, false)
    }

    private fun showCreateArchiveDialog(files: FileItemSet) {
        if (viewModel.isRecentFiles) files.firstOrNull()?.path?.parent?.let { navigateToRoot(it) }
        CreateArchiveDialogFragment.show(files, this)
    }

    override fun archive(
        files: FileItemSet,
        name: String,
        format: Int,
        filter: Int,
        password: String?
    ) {
        ActionPanel.dismiss(requireContext())
        val archiveFile = viewModel.currentPath.resolve(name)
        FileJobService.archive(
            makePathListForJob(files), archiveFile, format, filter, password, requireContext()
        )
        viewModel.selectFiles(files, false)
    }

    private fun shareFiles(files: FileItemSet) {
        shareFiles(files.map { it.path }, files.map { it.mimeType })
        viewModel.selectFiles(files, false)
    }

    private fun selectAllFiles() {
        adapter.selectAllFiles()
    }

    private fun onPasteStateChanged(pasteState: PasteState) {
        updateBottomToolbar()
    }

    private fun updateBottomToolbar() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            bottomActionMode.setMenuResource(R.menu.file_list_pick_bottom)
            val menu = bottomActionMode.menu
            when (pickOptions.mode) {
                PickOptions.Mode.CREATE_FILE -> {
                    bottomActionMode.title = null
                    binding.bottomCreateFileNameEdit.isVisible = true
                    val createMenuItem = menu.findItem(R.id.action_create)
                    binding.bottomCreateFileNameEdit.setOnEditorConfirmActionListener {
                        onBottomActionModeMenuItemClicked(createMenuItem)
                    }
                    if (!viewModel.isCreateFileNameEditInitialized) {
                        val fileName = pickOptions.fileName!!
                        binding.bottomCreateFileNameEdit.setText(fileName)
                        binding.bottomCreateFileNameEdit.setSelection(
                            0, fileName.asFileName().baseName.length
                        )
                        binding.bottomCreateFileNameEdit.requestFocus()
                        viewModel.isCreateFileNameEditInitialized = true
                    }
                    menu.findItem(R.id.action_open).isVisible = false
                    createMenuItem.isVisible = true
                }
                PickOptions.Mode.OPEN_DIRECTORY -> {
                    val path = viewModel.currentPath
                    val navigationRoot = NavigationRootMapLiveData.valueCompat[path]
                    val name = navigationRoot?.getName(requireContext()) ?: path.name
                    bottomActionMode.title =
                        getString(R.string.file_list_open_current_directory_format, name)
                    binding.bottomCreateFileNameEdit.isVisible = false
                    menu.findItem(R.id.action_open).isVisible = true
                    menu.findItem(R.id.action_create).isVisible = false
                }
                else -> {
                    if (bottomActionMode.isActive) {
                        bottomActionMode.finish()
                    }
                    return
                }
            }
        } else {
            val pasteState = viewModel.pasteState
            val files = pasteState.files
            if (files.isEmpty()) {
                if (bottomActionMode.isActive) {
                    bottomActionMode.finish()
                }
                return
            }
            val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
            bottomActionMode.title = getString(
                if (pasteState.copy) {
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_paste_extract_title_format
                    } else {
                        R.string.file_list_paste_copy_title_format
                    }
                } else {
                    R.string.file_list_paste_move_title_format
                }, files.size
            )
            binding.bottomCreateFileNameEdit.isVisible = false
            bottomActionMode.setMenuResource(R.menu.file_list_paste)
            val isCurrentPathReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            bottomActionMode.menu.findItem(R.id.action_paste)
                .setTitle(
                    if (areAllFilesArchivePaths) R.string.file_list_paste_action_extract_here else R.string.paste
                )
                .isEnabled = !isCurrentPathReadOnly && !viewModel.isRecentFiles
        }
        if (!bottomActionMode.isActive) {
            bottomActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarNavigationIconClicked(toolbarActionMode: ToolbarActionMode) {
                    onBottomToolbarNavigationIconClicked()
                }

                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onBottomActionModeMenuItemClicked(item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onBottomActionModeFinished()
                }
            })
        }
    }

    private fun onBottomToolbarNavigationIconClicked() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            requireActivity().finish()
        } else {
            bottomActionMode.finish()
        }
    }

    private fun onBottomActionModeMenuItemClicked(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_open -> {
                pickPaths(linkedSetOf(viewModel.currentPath))
                true
            }
            R.id.action_create -> {
                val fileName = binding.bottomCreateFileNameEdit.text.toString()
                if (fileName.isEmpty()) {
                    showToast(R.string.file_list_create_file_name_error_empty)
                } else if (fileName.asFileNameOrNull() == null) {
                    showToast(R.string.file_list_create_file_name_error_invalid)
                } else {
                    val file = getFileWithName(fileName)
                    if (file != null) {
                        confirmReplaceFile(file, false)
                    } else {
                        val path = viewModel.currentPath.resolve(fileName)
                        pickPaths(linkedSetOf(path))
                    }
                }
                true
            }
            R.id.action_lan_share_copied -> {
                // Use the app's copied/cut file paths, never clipboard text or paste semantics.
                val paths = viewModel.pasteState.files.map { it.path }
                if (paths.isNotEmpty()) shareOnLan(paths)
                true
            }
            R.id.action_paste -> {
                pasteFiles(currentPath)
                true
            }
            else -> false
        }

    private fun onBottomActionModeFinished() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions == null) {
            viewModel.clearPasteState()
        }
    }

    private fun pasteFiles(targetDirectory: Path) {
        val pasteState = viewModel.pasteState
        if (viewModel.pasteState.copy) {
            FileJobService.copy(
                makePathListForJob(pasteState.files), targetDirectory, requireContext()
            )
        } else {
            FileJobService.move(
                makePathListForJob(pasteState.files), targetDirectory, requireContext()
            )
        }
        viewModel.clearPasteState()
    }

    private fun makePathListForJob(files: FileItemSet): List<Path> =
        files.map { it.path }.sortedBy { it.toUri() }

    private fun onFileNameEllipsizeChanged(fileNameEllipsize: TextUtils.TruncateAt) {
        adapter.nameEllipsize = fileNameEllipsize
    }

    override fun clearSelectedFiles() {
        viewModel.clearSelectedFiles()
    }

    override fun startDragSelection(position: Int) {
        dragSelection.start(position)
    }

    override fun selectFile(file: FileItem, selected: Boolean) {
        viewModel.selectFile(file, selected)
    }

    override fun selectFiles(files: FileItemSet, selected: Boolean) {
        viewModel.selectFiles(files, selected)
    }

    override fun openFile(file: FileItem) {
        if (preview.isVisible && viewModel.pickOptions == null) {
            preview.show(file)
        } else {
            openFileDirect(file)
        }
    }

    private fun openFileDirect(file: FileItem) {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            if (file.attributes.isDirectory) {
                navigateTo(file.path)
            } else {
                when (pickOptions.mode) {
                    PickOptions.Mode.OPEN_FILE -> pickFiles(fileItemSetOf(file))
                    PickOptions.Mode.CREATE_FILE -> confirmReplaceFile(file)
                    PickOptions.Mode.OPEN_DIRECTORY -> {}
                }
            }
            return
        }
        if (file.mimeType.isApk) {
            openApk(file)
            return
        }
        if (file.isListable) {
            navigateTo(file.listablePath)
            return
        }
        openFileWithIntent(file, false)
    }

    private fun openApk(file: FileItem) {
        if (!file.isListable) {
            installApk(file)
            return
        }
        when (Settings.OPEN_APK_DEFAULT_ACTION.valueCompat) {
            OpenApkDefaultAction.INSTALL -> installApk(file)
            OpenApkDefaultAction.VIEW -> viewApk(file)
            OpenApkDefaultAction.ASK -> OpenApkDialogFragment.show(file, this)
        }
    }

    override fun installApk(file: FileItem) {
        val path = file.path
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!path.isArchivePath) path.fileProviderUri else null
        } else {
            // PackageInstaller only supports file URI before N.
            if (path.isLinuxPath) Uri.fromFile(path.toFile()) else null
        }
        if (uri != null) {
            startActivitySafe(uri.createInstallPackageIntent())
        } else {
            FileJobService.installApk(path, requireContext())
        }
    }

    override fun viewApk(file: FileItem) {
        navigateTo(file.listablePath)
    }

    override fun encryptFile(file: FileItem) {
        startActivity(Intent(requireContext(), me.zhanghai.android.files.vault.VaultActivity::class.java).apply { extraPath = file.path })
    }
    override fun shareOnLan(file: FileItem) = shareOnLan(listOf(file.path))

    private fun shareOnLan(paths: List<Path>) {
        startActivity(Intent(requireContext(), me.zhanghai.android.files.lanshare.LanShareActivity::class.java).apply { extraPathList = paths })
    }

    override fun sendToMac(file: FileItem) {
        startActivity(Intent(requireContext(), me.zhanghai.android.files.bridge.BridgeActivity::class.java).apply { extraPath = file.path })
    }

    override fun openFileWith(file: FileItem) {
        openFileWithIntent(file, true)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openFileWithIntent(file: FileItem, withChooser: Boolean) {
        val path = file.path
        val mimeType = file.mimeType
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        if (!withChooser && !path.isArchivePath && (extension in setOf("txt", "md", "xml", "log", "html", "htm"))) {
            startActivitySafe(Intent(requireContext(), me.zhanghai.android.files.viewer.text.TextEditorActivity::class.java).apply { extraPath = path; putExtra(me.zhanghai.android.files.viewer.text.TextEditorActivity.EXTRA_PREVIEW, true) })
            return
        }
        if (!withChooser && !path.isArchivePath && (extension == "pdf" || mimeType.value == "application/pdf")) {
            startActivitySafe(Intent(requireContext(), me.zhanghai.android.files.viewer.document.PdfViewerActivity::class.java).apply { extraPath = path })
            return
        }
        if (!withChooser && !path.isArchivePath && mimeType.type == "audio") {
            val paths = (0 until adapter.itemCount).map { adapter.getItem(it) }
                .filter { it.mimeType.type == "audio" && !it.path.isArchivePath }.map { it.path }
            me.zhanghai.android.files.viewer.audio.AudioPlayback.play(requireContext(), if (path in paths) paths else listOf(path), path)
            return
        }
        if (!withChooser && mimeType.type == "video" && !path.isArchivePath) {
            startActivitySafe(Intent(requireContext(), me.zhanghai.android.files.viewer.video.VideoViewerActivity::class.java)
                .setType(mimeType.value).apply { extraPath = path })
            return
        }
        if (path.isArchivePath) {
            FileJobService.open(path, mimeType, withChooser, requireContext())
        } else {
            val intent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply {
                    extraPath = path
                    maybeAddImageViewerActivityExtras(this, path, mimeType)
                }
                .let {
                    if (withChooser) {
                        it.withChooser(
                            EditFileActivity::class.createIntent()
                                .putArgs(EditFileActivity.Args(path, mimeType)),
                            OpenFileAsDialogActivity::class.createIntent()
                                .putArgs(OpenFileAsDialogFragment.Args(path))
                        )
                    } else {
                        it
                    }
                }
            startActivitySafe(intent)
        }
    }

    private fun maybeAddImageViewerActivityExtras(intent: Intent, path: Path, mimeType: MimeType) {
        if (!mimeType.isImage) {
            return
        }
        var paths = mutableListOf<Path>()
        // We need the ordered list from our adapter instead of the list from FileListLiveData.
        for (index in 0..<adapter.itemCount) {
            val file = adapter.getItem(index)
            val filePath = file.path
            if (file.mimeType.isImage || filePath == path) {
                paths.add(filePath)
            }
        }
        var position = paths.indexOf(path)
        if (position == -1) {
            return
        }
        // HACK: Don't send too many paths to avoid TransactionTooLargeException.
        if (paths.size > IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX) {
            val start = (position - IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX / 2)
                .coerceIn(0, paths.size - IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX)
            paths = paths.subList(start, start + IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX)
            position -= start
        }
        ImageViewerActivity.putExtras(intent, paths, position)
    }

    override fun cutFile(file: FileItem) {
        cutFiles(fileItemSetOf(file))
    }

    override fun copyFile(file: FileItem) {
        copyFiles(fileItemSetOf(file))
    }

    override fun confirmDeleteFile(file: FileItem) {
        confirmDeleteFiles(fileItemSetOf(file))
    }

    override fun showRenameFileDialog(file: FileItem) {
        RenameFileDialogFragment.show(file, this)
    }

    override fun hasFileWithName(name: String): Boolean = getFileWithName(name) != null

    private fun getFileWithName(name: String): FileItem? {
        val fileListData = viewModel.fileListStateful
        if (fileListData !is Success) {
            return null
        }
        return fileListData.value.find { it.name == name }
    }

    override fun renameFile(file: FileItem, newName: String) {
        ActionPanel.dismiss(requireContext())
        FileJobService.rename(file.path, newName, requireContext())
        viewModel.selectFile(file, false)
    }

    override fun extractFile(file: FileItem) {
        copyFile(file.createDummyArchiveRoot())
    }

    override fun showCreateArchiveDialog(file: FileItem) {
        showCreateArchiveDialog(fileItemSetOf(file))
    }

    override fun shareFile(file: FileItem) {
        shareFile(file.path, file.mimeType)
    }

    private fun shareFile(path: Path, mimeType: MimeType) {
        shareFiles(listOf(path), listOf(mimeType))
    }

    private fun shareFiles(paths: List<Path>, mimeTypes: List<MimeType>) {
        val uris = paths.map { it.fileProviderUri }
        val intent = uris.createSendStreamIntent(mimeTypes)
            .withChooser()
        startActivitySafe(intent)
    }

    override fun copyPath(file: FileItem) {
        copyPath(file.path)
    }

    override fun addBookmark(file: FileItem) {
        addBookmark(file.path)
    }

    private fun addBookmark() {
        addBookmark(currentPath)
    }

    private fun addBookmark(path: Path) {
        BookmarkDirectories.add(BookmarkDirectory(null, path))
        showToast(R.string.file_add_bookmark_success)
    }

    override fun createShortcut(file: FileItem) {
        createShortcut(file.path, file.mimeType)
    }

    private fun createShortcut() {
        createShortcut(currentPath, MimeType.DIRECTORY)
    }

    private fun createShortcut(path: Path, mimeType: MimeType) {
        val context = requireContext()
        val isDirectory = mimeType == MimeType.DIRECTORY
        val shortcutInfo = ShortcutInfoCompat.Builder(context, path.toString())
            .setShortLabel(path.name)
            .setIntent(
                if (isDirectory) {
                    FileListActivity.createViewIntent(path)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                } else {
                    OpenFileActivity.createIntent(path, mimeType)
                }
            )
            .setIcon(
                IconCompat.createWithResource(
                    context, if (isDirectory) {
                        R.mipmap.directory_shortcut_icon
                    } else {
                        R.mipmap.file_shortcut_icon
                    }
                )
            )
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.shortcut_created)
        }
    }

    override fun showPropertiesDialog(file: FileItem) {
        FilePropertiesDialogFragment.show(file, this)
    }

    private fun showCreateFileDialog() {
        CreateFileDialogFragment.show(this)
    }

    override fun createFile(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, false, requireContext())
    }

    private fun showCreateDirectoryDialog() {
        CreateDirectoryDialogFragment.show(this)
    }

    override fun createDirectory(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, true, requireContext())
    }

    override val currentPath: Path
        get() = viewModel.currentPath

    override fun navigateToRoot(path: Path) {
        collapseSearchView()
        viewModel.isRecentFiles = false
        viewModel.resetTo(path)
    }

    override fun navigateToDefaultRoot() {
        navigateToRoot(Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat)
    }

    override val isRecentFilesAvailable: Boolean get() = viewModel.pickOptions == null

    override val isRecentFiles: Boolean get() = viewModel.isRecentFiles

    override fun navigateToRecentFiles() {
        if (viewModel.pickOptions != null) return
        collapseSearchView()
        viewModel.clearSelectedFiles()
        viewModel.isRecentFiles = true
        refresh()
    }

    override fun configureRecentFiles() {
        if (childFragmentManager.findFragmentByTag("recent_files_settings") == null) {
            RecentFilesSettingsDialogFragment().show(childFragmentManager, "recent_files_settings")
        }
    }

    override fun observeRecentFiles(owner: LifecycleOwner, observer: (Boolean) -> Unit) {
        viewModel.recentFilesLiveData.observe(owner, observer)
    }

    private fun onRecentFilesChanged(recent: Boolean) {
        adapter.isRecentFiles = recent
        updateQuickFilters()
        updateRecentFooter()
        binding.toolbar.title = null
        binding.root.findViewById<View>(R.id.recentFilesTitle).isVisible = recent
        binding.speedDialView.isVisible = !recent
        if (recent) binding.speedDialView.close()
        preview.clear()
        updateBreadcrumbVisibility()
        // The recent flag can dispatch before the mapped current path during picker startup.
        // onCurrentPathChanged updates the destination toolbar once that path is available.
        if (viewModel.currentPathLiveData.value != null) updateBottomToolbar()
        requireActivity().invalidateOptionsMenu()
    }

    override fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit) {
        viewModel.currentPathLiveData.observe(owner, observer)
    }

    override fun closeNavigationDrawer() {
        binding.drawerLayout?.closeDrawer(GravityCompat.START)
    }

    private fun ensureStorageAccess() {
        if (viewModel.isStorageAccessRequested) {
            return
        }
        if (Environment::class.supportsExternalStorageManager()) {
            if (!Environment.isExternalStorageManager()) {
                ShowRequestAllFilesAccessRationaleDialogFragment.show(this)
                viewModel.isStorageAccessRequested = true
            }
        } else if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (shouldShowRequestPermissionRationale(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )) {
                ShowRequestStoragePermissionRationaleDialogFragment.show(this)
            } else {
                requestStoragePermission()
            }
            viewModel.isStorageAccessRequested = true
        }
    }

    override fun onShowRequestAllFilesAccessRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestAllFilesAccess()
        } else {
            viewModel.isStorageAccessRequested = false
            // This isn't an onActivityResult() callback so it's not delivered before calling
            // onResume(), and we need to do this manually.
            ensureNotificationPermission()
        }
    }

    private fun requestAllFilesAccess() {
        requestAllFilesAccessLauncher.launch(Unit)
    }

    private fun onRequestAllFilesAccessResult(isGranted: Boolean) {
        viewModel.isStorageAccessRequested = false
        if (isGranted) {
            refresh()
        }
    }

    override fun onShowRequestStoragePermissionRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestStoragePermission()
        } else {
            viewModel.isStorageAccessRequested = false
        }
    }

    private fun requestStoragePermission() {
        requestStoragePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun onRequestStoragePermissionResult(isGranted: Boolean) {
        if (isGranted) {
            viewModel.isStorageAccessRequested = false
            refresh()
        } else if (shouldShowRequestPermissionRationale(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )) {
            ShowRequestStoragePermissionRationaleDialogFragment.show(this)
        } else {
            ShowRequestStoragePermissionInSettingsRationaleDialogFragment.show(this)
        }
    }

    override fun onShowRequestStoragePermissionInSettingsRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestStoragePermissionInSettings()
        } else {
            viewModel.isStorageAccessRequested = false
        }
    }

    private fun requestStoragePermissionInSettings() {
        requestStoragePermissionInSettingsLauncher.launch(Unit)
    }

    private fun onRequestStoragePermissionInSettingsResult(isGranted: Boolean) {
        viewModel.isStorageAccessRequested = false
        if (isGranted) {
            refresh()
        }
    }

    private fun ensureNotificationPermission() {
        if (viewModel.isNotificationPermissionRequested) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )) {
                    ShowRequestNotificationPermissionRationaleDialogFragment.show(this)
                } else {
                    requestNotificationPermission()
                }
                viewModel.isNotificationPermissionRequested = true
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onShowRequestNotificationPermissionRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestNotificationPermission()
        } else {
            viewModel.isNotificationPermissionRequested = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestNotificationPermission() {
        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun onRequestNotificationPermissionResult(isGranted: Boolean) {
        if (isGranted) {
            viewModel.isNotificationPermissionRequested = false
        } else if (shouldShowRequestPermissionRationale(
            android.Manifest.permission.POST_NOTIFICATIONS
        )) {
            ShowRequestNotificationPermissionRationaleDialogFragment.show(this)
        } else {
            ShowRequestNotificationPermissionInSettingsRationaleDialogFragment.show(this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onShowRequestNotificationPermissionInSettingsRationaleResult(
        shouldRequest: Boolean
    ) {
        if (shouldRequest) {
            requestNotificationPermissionInSettings()
        } else {
            viewModel.isNotificationPermissionRequested = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestNotificationPermissionInSettings() {
        requestNotificationPermissionInSettingsLauncher.launch(Unit)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun onRequestNotificationPermissionInSettingsResult(isGranted: Boolean) {
        if (isGranted) {
            viewModel.isNotificationPermissionRequested = false
        }
    }

    companion object {
        private const val ACTION_VIEW_DOWNLOADS =
            "me.zhanghai.android.files.intent.action.VIEW_DOWNLOADS"

        private const val IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX = 1000
    }

    private class RequestAllFilesAccessContract : ActivityResultContract<Unit, Boolean>() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun createIntent(context: Context, input: Unit): Intent =
            Environment::class.createManageAppAllFilesAccessPermissionIntent(context.packageName)

        @RequiresApi(Build.VERSION_CODES.R)
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            Environment.isExternalStorageManager()
    }

    private class RequestPermissionInSettingsContract(private val permissionName: String)
        : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            application.checkSelfPermissionCompat(permissionName) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Parcelize
    class Args(val intent: Intent) : ParcelableArgs

    private class Binding private constructor(
        val root: View,
        val drawerLayout: DrawerLayout? = null,
        val persistentDrawerLayout: PersistentDrawerLayout? = null,
        val persistentBarLayout: PersistentBarLayout,
        val appBarLayout: CoordinatorAppBarLayout,
        val toolbar: Toolbar,
        val overlayToolbar: Toolbar,
        val breadcrumbLayout: BreadcrumbLayout,
        val contentLayout: ViewGroup,
        val progress: ProgressBar,
        val errorText: TextView,
        val emptyView: View,
        val swipeRefreshLayout: SwipeRefreshLayout,
        val recyclerView: RecyclerView,
        val bottomBarLayout: ViewGroup,
        val bottomToolbar: Toolbar,
        val bottomCreateFileNameEdit: EditText,
        val speedDialView: SpeedDialView
    ) {
        companion object {
            fun inflate(
                inflater: LayoutInflater,
                root: ViewGroup?,
                attachToRoot: Boolean
            ): Binding {
                val binding = FileListFragmentBinding.inflate(inflater, root, attachToRoot)
                val bindingRoot = binding.root
                val includeBinding = FileListFragmentIncludeBinding.bind(bindingRoot)
                val appBarBinding = FileListFragmentAppBarIncludeBinding.bind(bindingRoot)
                val contentBinding = FileListFragmentContentIncludeBinding.bind(bindingRoot)
                val bottomBarBinding = FileListFragmentBottomBarIncludeBinding.bind(bindingRoot)
                val speedDialBinding = FileListFragmentSpeedDialIncludeBinding.bind(bindingRoot)
                return Binding(
                    bindingRoot, includeBinding.drawerLayout, includeBinding.persistentDrawerLayout,
                    includeBinding.persistentBarLayout, appBarBinding.appBarLayout,
                    appBarBinding.toolbar, appBarBinding.overlayToolbar,
                    appBarBinding.breadcrumbLayout, contentBinding.contentLayout,
                    contentBinding.progress, contentBinding.errorText, contentBinding.emptyView.root,
                    contentBinding.swipeRefreshLayout, contentBinding.recyclerView,
                    bottomBarBinding.bottomBarLayout, bottomBarBinding.bottomToolbar,
                    bottomBarBinding.bottomCreateFileNameEdit, speedDialBinding.speedDialView
                )
            }
        }
    }

    private class MenuBinding private constructor(
        val menu: Menu,
        val searchItem: MenuItem,
        val viewSortItem: MenuItem,
        val viewListItem: MenuItem,
        val viewGridItem: MenuItem,
        val sortByNameItem: MenuItem,
        val sortByTypeItem: MenuItem,
        val sortBySizeItem: MenuItem,
        val sortByLastModifiedItem: MenuItem,
        val sortOrderAscendingItem: MenuItem,
        val sortDirectoriesFirstItem: MenuItem,
        val viewSortPathSpecificItem: MenuItem,
        val selectAllItem: MenuItem,
        val showHiddenFilesItem: MenuItem
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.file_list, menu)
                return MenuBinding(
                    menu, menu.findItem(R.id.action_search), menu.findItem(R.id.action_view_sort),
                    menu.findItem(R.id.action_view_list), menu.findItem(R.id.action_view_grid),
                    menu.findItem(R.id.action_sort_by_name),
                    menu.findItem(R.id.action_sort_by_type),
                    menu.findItem(R.id.action_sort_by_size),
                    menu.findItem(R.id.action_sort_by_last_modified),
                    menu.findItem(R.id.action_sort_order_ascending),
                    menu.findItem(R.id.action_sort_directories_first),
                    menu.findItem(R.id.action_view_sort_path_specific),
                    menu.findItem(R.id.action_select_all),
                    menu.findItem(R.id.action_show_hidden_files)
                )
            }
        }
    }
}
