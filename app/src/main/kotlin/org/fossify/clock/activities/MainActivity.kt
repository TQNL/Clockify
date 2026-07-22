package org.fossify.clock.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.tabs.TabLayout
import me.grantland.widget.AutofitHelper
import org.fossify.clock.BuildConfig
import org.fossify.clock.R
import org.fossify.clock.adapters.ViewPagerAdapter
import org.fossify.clock.databinding.ActivityMainBinding
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.getEnabledAlarms
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.fragments.AlarmFragment
import org.fossify.clock.fragments.FolderFragment
import org.fossify.clock.selection.SelectionAction
import org.fossify.clock.selection.SelectionSnapshot
import org.fossify.clock.selection.actionDrawableRes
import org.fossify.clock.selection.actionLabel
import org.fossify.clock.selection.computeActions
import org.fossify.clock.helpers.INVALID_TIMER_ID
import org.fossify.clock.helpers.OPEN_TAB
import org.fossify.clock.helpers.PICK_AUDIO_FILE_INTENT_ID
import org.fossify.clock.helpers.STOPWATCH_SHORTCUT_ID
import org.fossify.clock.helpers.STOPWATCH_TOGGLE_ACTION
import org.fossify.clock.helpers.TABS_COUNT
import org.fossify.clock.helpers.TAB_ALARM
import org.fossify.clock.helpers.TAB_ALARM_INDEX
import org.fossify.clock.helpers.TAB_CLOCK
import org.fossify.clock.helpers.TAB_CLOCK_INDEX
import org.fossify.clock.helpers.TAB_STOPWATCH
import org.fossify.clock.helpers.TAB_STOPWATCH_INDEX
import org.fossify.clock.helpers.TAB_TIMER
import org.fossify.clock.helpers.TAB_TIMER_INDEX
import org.fossify.clock.helpers.TIMER_ID
import org.fossify.clock.helpers.TOGGLE_STOPWATCH
import org.fossify.commons.databinding.BottomTablayoutItemBinding
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.getBottomNavigationBackgroundColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.onPageChangeListener
import org.fossify.commons.extensions.shortcutManager
import org.fossify.commons.extensions.storeNewYourAlarmSound
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateBottomTabItemColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import org.fossify.commons.helpers.LICENSE_NUMBER_PICKER
import org.fossify.commons.helpers.LICENSE_RTL
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FAQItem
import java.lang.ref.WeakReference
import java.time.temporal.WeekFields
import java.util.Locale

class MainActivity : SimpleActivity() {
    private var storedTextColor = 0
    private var storedBackgroundColor = 0
    private var storedPrimaryColor = 0
    private lateinit var folderBackCallback: OnBackPressedCallback
    private val binding: ActivityMainBinding by viewBinding(ActivityMainBinding::inflate)

    // Samsung-style selection tab mode
    private var isSelectionTabMode = false
    private var isRebuildingTabs = false
    private var selectionAlarmFragment: WeakReference<AlarmFragment>? = null
    private var lastSelectionSnapshot: SelectionSnapshot? = null
    private var selectionActions: List<SelectionAction> = emptyList()

    /** Shared listener for navigation tabs and user-triggered selection actions. */
    private val mainTabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            if (isRebuildingTabs) return

            if (isSelectionTabMode) {
                executeSelectionAction(tab)
                return
            }

            val drawableId = getSelectedTabDrawableIds().getOrNull(tab.position) ?: return
            binding.viewPager.currentItem = tab.position
            updateBottomTabItemColors(tab.customView, true, drawableId)
            tab.customView?.let {
                val itemBinding = BottomTablayoutItemBinding.bind(it)
                itemBinding.tabItemIcon.applyColorFilter(getProperPrimaryColor())
                itemBinding.tabItemLabel.setTextColor(getProperPrimaryColor())
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab) {
            if (isRebuildingTabs || isSelectionTabMode) return
            val drawableId = getDeselectedTabDrawableIds().getOrNull(tab.position) ?: return
            updateBottomTabItemColors(tab.customView, false, drawableId)
        }

        override fun onTabReselected(tab: TabLayout.Tab) {
            if (!isRebuildingTabs && isSelectionTabMode) {
                executeSelectionAction(tab)
            }
        }
    }

    private fun executeSelectionAction(tab: TabLayout.Tab) {
        val action = selectionActions.getOrNull(tab.position) ?: return
        selectionAlarmFragment?.get()?.executeSelectionAction(action)
    }

    private inline fun rebuildTabsWithoutCallbacks(block: () -> Unit) {
        isRebuildingTabs = true
        try {
            block()
        } finally {
            isRebuildingTabs = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        folderBackCallback = onBackPressedDispatcher.addCallback(this, false) {
            closeFolderFragment()
        }
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder))

        // B2 fix: stable status bar insets — prevent content shift on system bar visibility changes
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.mainCoordinator) { v, insets ->
            val statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Apply stable top padding for status bar only (don't react to every change)
            if (statusBarInsets.top > 0) {
                // Only apply when non-zero to avoid collapse; use max to prevent oscillation
                val currentTop = v.paddingTop
                if (kotlin.math.abs(currentTop - statusBarInsets.top) > 5) {
                    v.setPadding(
                        v.paddingStart,
                        statusBarInsets.top,
                        v.paddingEnd,
                        statusBarInsets.bottom
                    )
                }
            }
            androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets)
        }

        storeStateVariables()
        initFragments()
        setupTabs()
        restoreFolderView()
        updateWidgets()
        migrateFirstDayOfWeek()

        ensureBackgroundThread {
            alarmController.rescheduleEnabledAlarms()
        }

        getEnabledAlarms { enabledAlarms ->
            if (!enabledAlarms.isNullOrEmpty()) {
                handleFullScreenNotificationsPermission {
                    if (!it) {
                        toast(org.fossify.commons.R.string.notifications_disabled)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.mainAppbar, topBarColor = getProperBackgroundColor())
        val configTextColor = getProperTextColor()
        if (storedTextColor != configTextColor) {
            getInactiveTabIndexes(binding.viewPager.currentItem).forEach {
                binding.mainTabsHolder.getTabAt(it)?.icon?.applyColorFilter(configTextColor)
            }
        }

        val configBackgroundColor = getProperBackgroundColor()
        if (storedBackgroundColor != configBackgroundColor) {
            binding.mainTabsHolder.background = configBackgroundColor.toDrawable()
        }

        val configPrimaryColor = getProperPrimaryColor()
        if (storedPrimaryColor != configPrimaryColor) {
            binding.mainTabsHolder.setSelectedTabIndicatorColor(getProperPrimaryColor())
            binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.icon
                ?.applyColorFilter(getProperPrimaryColor())
        }

        if (config.preventPhoneFromSleeping) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setupTabColors()
        checkShortcuts()
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (config.lastHandledShortcutColor != appIconColor) {
            val stopWatchShortcutInfo = getLaunchStopwatchShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(stopWatchShortcutInfo)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchStopwatchShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.start_stopwatch)
        val drawable = resources.getDrawable(R.drawable.shortcut_stopwatch)
        (drawable as LayerDrawable)
            .findDrawableByLayerId(R.id.shortcut_stopwatch_background)
            .applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, SplashActivity::class.java).apply {
            putExtra(OPEN_TAB, TAB_STOPWATCH)
            putExtra(TOGGLE_STOPWATCH, true)
            action = STOPWATCH_TOGGLE_ACTION
        }

        return ShortcutInfo.Builder(this, STOPWATCH_SHORTCUT_ID)
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        if (config.preventPhoneFromSleeping) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    private fun setupOptionsMenu() {
        binding.mainToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> {
                    if (binding.fragmentContainer.visibility == android.view.View.VISIBLE) {
                        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? AlarmFragment)
                            ?.showSortingDialog()
                    } else when (binding.viewPager.currentItem) {
                        TAB_ALARM_INDEX -> getViewPagerAdapter()?.showAlarmSortDialog()
                        TAB_TIMER_INDEX -> getViewPagerAdapter()?.showTimerSortDialog()
                    }
                }

                R.id.create_folder -> {
                    getViewPagerAdapter()?.showAlarmCreateFolderDialog()
                }

                R.id.add_alarm -> {
                    if (binding.fragmentContainer.visibility == android.view.View.VISIBLE) {
                        // Folder view — find the FolderFragment in the container
                        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? AlarmFragment)
                            ?.addAlarm()
                    } else {
                        getViewPagerAdapter()?.showAlarmAddDialog()
                    }
                }

                R.id.turn_all_on -> {
                    (supportFragmentManager.findFragmentById(R.id.fragment_container) as? AlarmFragment)
                        ?.turnAllAlarms(enabled = true)
                }

                R.id.turn_all_off -> {
                    (supportFragmentManager.findFragmentById(R.id.fragment_container) as? AlarmFragment)
                        ?.turnAllAlarms(enabled = false)
                }

                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.mainToolbar.menu.apply {
            // During selection mode, hide all toolbar menu items
            if (isSelectionTabMode) {
                findItem(R.id.sort).isVisible = false
                findItem(R.id.create_folder).isVisible = false
                findItem(R.id.add_alarm).isVisible = false
                findItem(R.id.turn_all_on).isVisible = false
                findItem(R.id.turn_all_off).isVisible = false
                findItem(R.id.more_apps_from_us).isVisible = false
                return
            }
            val isAlarmTab = binding.viewPager.currentItem == getTabIndex(TAB_ALARM)
            val isFolderView = binding.fragmentContainer.visibility == android.view.View.VISIBLE
            findItem(R.id.sort).isVisible = isAlarmTab || isFolderView
                || binding.viewPager.currentItem == getTabIndex(TAB_TIMER)
            findItem(R.id.create_folder).isVisible = isAlarmTab && !isFolderView
            findItem(R.id.add_alarm).isVisible = isAlarmTab || isFolderView
            // Samsung-style group-level controls: only inside a folder view.
            findItem(R.id.turn_all_on).isVisible = isFolderView
            findItem(R.id.turn_all_off).isVisible = isFolderView
            findItem(R.id.more_apps_from_us).isVisible =
                !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.extras?.containsKey(OPEN_TAB) == true) {
            val tabToOpen = intent.getIntExtra(OPEN_TAB, TAB_CLOCK)
            binding.viewPager.setCurrentItem(getTabIndex(tabToOpen), false)
            if (tabToOpen == TAB_TIMER) {
                val timerId = intent.getIntExtra(TIMER_ID, INVALID_TIMER_ID)
                (binding.viewPager.adapter as ViewPagerAdapter).updateTimerPosition(timerId)
            }
            if (tabToOpen == TAB_STOPWATCH) {
                if (intent.getBooleanExtra(TOGGLE_STOPWATCH, false)) {
                    (binding.viewPager.adapter as ViewPagerAdapter).startStopWatch()
                }
            }
        }
        super.onNewIntent(intent)
    }

    private fun storeStateVariables() {
        storedTextColor = getProperTextColor()
        storedBackgroundColor = getProperBackgroundColor()
        storedPrimaryColor = getProperPrimaryColor()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        when {
            requestCode == PICK_AUDIO_FILE_INTENT_ID && resultCode == RESULT_OK && resultData != null -> {
                storeNewAlarmSound(resultData)
            }
        }
    }

    private fun storeNewAlarmSound(resultData: Intent) {
        val newAlarmSound = storeNewYourAlarmSound(resultData)

        when (binding.viewPager.currentItem) {
            TAB_ALARM_INDEX -> getViewPagerAdapter()?.updateAlarmTabAlarmSound(newAlarmSound)
            TAB_TIMER_INDEX -> getViewPagerAdapter()?.updateTimerTabAlarmSound(newAlarmSound)
        }
    }

    fun updateClockTabAlarm() {
        getViewPagerAdapter()?.updateClockTabAlarm()
    }

    private fun getViewPagerAdapter() = binding.viewPager.adapter as? ViewPagerAdapter

    private fun initFragments() {
        val viewPagerAdapter = ViewPagerAdapter(supportFragmentManager)
        binding.viewPager.adapter = viewPagerAdapter
        binding.viewPager.onPageChangeListener {
            binding.mainTabsHolder.getTabAt(it)?.select()
            refreshMenuItems()
        }
        // Block ViewPager swiping during selection mode
        binding.viewPager.setOnTouchListener { _, _ -> isSelectionTabMode }

        // Smooth crossfade + slide transition between fragments (B6 fix)
        binding.viewPager.setPageTransformer(false, androidx.viewpager.widget.ViewPager.PageTransformer { page, position ->
            val absPos = kotlin.math.abs(position)
            page.alpha = 1.0f - absPos * 0.3f
            page.translationX = 0f
            page.scaleX = 1.0f - absPos * 0.03f
            page.scaleY = 1.0f - absPos * 0.03f
        })

        val tabToOpen = intent.getIntExtra(OPEN_TAB, config.defaultTab)
        intent.removeExtra(OPEN_TAB)
        if (tabToOpen == TAB_TIMER) {
            val timerId = intent.getIntExtra(TIMER_ID, INVALID_TIMER_ID)
            viewPagerAdapter.updateTimerPosition(timerId)
        }

        if (tabToOpen == TAB_STOPWATCH) {
            config.toggleStopwatch = intent.getBooleanExtra(TOGGLE_STOPWATCH, false)
        }

        binding.viewPager.offscreenPageLimit = TABS_COUNT - 1
        binding.viewPager.currentItem = getTabIndex(tabToOpen)
    }

    private fun setupTabs() {
        // Add the shared listener once, never re-add after tab rebuilds
        binding.mainTabsHolder.addOnTabSelectedListener(mainTabListener)
        rebuildTabs()
    }

    /** Entry point for all tab rebuilds — delegates to nav or selection depending on mode. */
    private fun rebuildTabs() {
        if (isSelectionTabMode) {
            rebuildSelectionTabs()
        } else {
            rebuildNavigationTabs()
        }
    }

    /** Build the standard 4-tab navigation layout. */
    private fun rebuildNavigationTabs() {
        rebuildTabsWithoutCallbacks {
            binding.mainTabsHolder.apply {
                tabMode = TabLayout.MODE_FIXED
                removeAllTabs()
            }

            val tabDrawables = arrayOf(
                R.drawable.ic_clock_vector,
                R.drawable.ic_alarm_vector,
                R.drawable.ic_stopwatch_vector,
                R.drawable.ic_hourglass_vector
            )
            val tabLabels = arrayOf(
                R.string.clock,
                org.fossify.commons.R.string.alarm,
                R.string.stopwatch,
                R.string.timer
            )

            tabDrawables.forEachIndexed { i, drawableId ->
                binding.mainTabsHolder.newTab()
                    .setCustomView(org.fossify.commons.R.layout.bottom_tablayout_item)
                    .apply tab@{
                        customView?.let { BottomTablayoutItemBinding.bind(it) }?.apply {
                            tabItemIcon.setImageDrawable(getDrawable(drawableId))
                            tabItemLabel.setText(tabLabels[i])
                            AutofitHelper.create(tabItemLabel)
                            binding.mainTabsHolder.addTab(this@tab, false)
                        }
                    }
            }

            binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.select()
        }
        setupTabColors()
    }

    /** Build selection-mode action tabs from [lastSelectionSnapshot]. */
    private fun rebuildSelectionTabs() {
        val snapshot = lastSelectionSnapshot ?: return
        selectionActions = computeActions(snapshot).toList()

        rebuildTabsWithoutCallbacks {
            binding.mainTabsHolder.apply {
                tabMode = TabLayout.MODE_SCROLLABLE
                removeAllTabs()
            }

            for (action in selectionActions) {
                val drawableId = actionDrawableRes(action)
                val label = actionLabel(action, snapshot)

                binding.mainTabsHolder.newTab()
                    .setCustomView(org.fossify.commons.R.layout.bottom_tablayout_item)
                    .apply tab@{
                        customView?.let { BottomTablayoutItemBinding.bind(it) }?.apply {
                            tabItemIcon.setImageDrawable(getDrawable(drawableId))
                            tabItemLabel.text = label
                            AutofitHelper.create(tabItemLabel)
                            // Action tabs are buttons, not persistent navigation state.
                            binding.mainTabsHolder.addTab(this@tab, false)
                        }
                    }
            }
        }
        setupSelectionTabColors()
    }

    /** Apply proper text/icon colors to selection-mode action tabs. */
    private fun setupSelectionTabColors() {
        val textColor = getProperTextColor()
        for (i in 0 until binding.mainTabsHolder.tabCount) {
            val customView = binding.mainTabsHolder.getTabAt(i)?.customView ?: continue
            val itemBinding = BottomTablayoutItemBinding.bind(customView)
            itemBinding.tabItemIcon.applyColorFilter(textColor)
            itemBinding.tabItemLabel.setTextColor(textColor)
        }
    }

    /** AlarmFragment calls this when entering selection mode. */
    fun enterSelectionTabMode(fragment: AlarmFragment) {
        if (isSelectionTabMode) return
        isSelectionTabMode = true
        selectionAlarmFragment = WeakReference(fragment)
        lastSelectionSnapshot = null
        selectionActions = emptyList()
        rebuildTabsWithoutCallbacks {
            binding.mainTabsHolder.apply {
                tabMode = TabLayout.MODE_SCROLLABLE
                removeAllTabs()
            }
        }
        binding.mainTabsHolder.visibility = android.view.View.VISIBLE
        refreshMenuItems()
    }

    /** AlarmFragment calls this when exiting selection mode. */
    fun exitSelectionTabMode() {
        if (!isSelectionTabMode) return
        isSelectionTabMode = false
        lastSelectionSnapshot = null
        selectionActions = emptyList()
        selectionAlarmFragment = null
        rebuildNavigationTabs()
        refreshMenuItems()
    }

    /** AlarmFragment calls this on every selection change so we can rebuild action tabs. */
    fun updateSelectionTabs(snapshot: SelectionSnapshot) {
        if (!isSelectionTabMode) return
        lastSelectionSnapshot = snapshot
        rebuildTabs()
    }

    private fun setupTabColors() {
        if (isSelectionTabMode) {
            setupSelectionTabColors()
            return
        }

        val primaryColor = getProperPrimaryColor()
        val textColor = getProperTextColor()

        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(
            view = activeView,
            isActive = true,
            drawableId = getSelectedTabDrawableIds()[binding.viewPager.currentItem]
        )
        // B4/B5: Apply primary color as active tint for strong brand visibility
        activeView?.let {
            val activeBinding = BottomTablayoutItemBinding.bind(it)
            activeBinding.tabItemIcon.applyColorFilter(primaryColor)
            activeBinding.tabItemLabel.setTextColor(primaryColor)
        }

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
            // B4/B5: Dim inactive tabs with text color at lower alpha
            inactiveView?.let {
                val inactiveBinding = BottomTablayoutItemBinding.bind(it)
                inactiveBinding.tabItemIcon.applyColorFilter(primaryColor and 0x00FFFFFF or (0x66 shl 24)) // 40% alpha
                inactiveBinding.tabItemLabel.setTextColor(textColor and 0x00FFFFFF or (0x99 shl 24)) // 60% alpha
            }
        }

        binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.select()
        val bottomBarColor = getBottomNavigationBackgroundColor()
        binding.mainTabsHolder.setBackgroundColor(bottomBarColor)
    }

    private fun getInactiveTabIndexes(activeIndex: Int): List<Int> {
        return arrayListOf(0, 1, 2, 3).filter { it != activeIndex }
    }

    private fun getSelectedTabDrawableIds() = arrayOf(
        R.drawable.ic_clock_filled_vector,
        R.drawable.ic_alarm_filled_vector,
        R.drawable.ic_stopwatch_filled_vector,
        R.drawable.ic_hourglass_filled_vector
    )

    private fun getDeselectedTabDrawableIds() = arrayOf(
        org.fossify.commons.R.drawable.ic_clock_vector,
        R.drawable.ic_alarm_vector,
        R.drawable.ic_stopwatch_vector,
        R.drawable.ic_hourglass_vector
    )

    private fun launchSettings() {
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses =
            LICENSE_NUMBER_PICKER or LICENSE_RTL or LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(
                title = R.string.faq_1_title,
                text = R.string.faq_1_text
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_1_title_commons,
                text = org.fossify.commons.R.string.faq_1_text_commons
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_4_title_commons,
                text = org.fossify.commons.R.string.faq_4_text_commons
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_9_title_commons,
                text = org.fossify.commons.R.string.faq_9_text_commons
            )
        )

        if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_2_title_commons,
                    text = org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_6_title_commons,
                    text = org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    @Deprecated("Remove this method in future releases")
    private fun migrateFirstDayOfWeek() {
        if (config.migrateFirstDayOfWeek) {
            config.migrateFirstDayOfWeek = false
            config.firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
        }
    }

    private fun getTabIndex(tabId: Int): Int {
        return when (tabId) {
            TAB_CLOCK -> TAB_CLOCK_INDEX
            TAB_ALARM -> TAB_ALARM_INDEX
            TAB_STOPWATCH -> TAB_STOPWATCH_INDEX
            TAB_TIMER -> TAB_TIMER_INDEX
            else -> config.lastUsedViewPagerPage
        }
    }

    fun openFolderFragment(folderId: Int, folderName: String) {
        if (binding.fragmentContainer.visibility == android.view.View.VISIBLE) return

        showFolderView(folderName)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, FolderFragment.newInstance(folderId, folderName))
            .addToBackStack("folder")
            .commit()
        // Make the folder transaction visible to an immediate system-back press.
        supportFragmentManager.executePendingTransactions()
    }

    private fun restoreFolderView() {
        val folderFragment = supportFragmentManager
            .findFragmentById(R.id.fragment_container) as? FolderFragment ?: return
        showFolderView(folderFragment.displayName)
    }

    private fun showFolderView(folderName: String) {
        binding.mainToolbar.title = folderName
        binding.mainToolbar.setNavigationIcon(R.drawable.ic_chevron_left_vector)
        binding.mainToolbar.setNavigationOnClickListener { closeFolderFragment() }
        binding.mainTabsHolder.visibility = android.view.View.GONE
        binding.mainHolder.visibility = android.view.View.GONE
        binding.fragmentContainer.visibility = android.view.View.VISIBLE
        folderBackCallback.isEnabled = true
        refreshMenuItems()
    }

    /** Hide/show the top toolbar (including the CAB) during Samsung-style selection mode. */
    fun setToolbarVisible(visible: Boolean) {
        val visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        binding.mainToolbar.visibility = visibility
        binding.mainAppbar.visibility = visibility
    }

    /** Hide/show the bottom tab bar during Samsung-style selection mode. */
    fun setTabBarVisible(visible: Boolean) {
        binding.mainTabsHolder.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    fun closeFolderFragment() {
        if (binding.fragmentContainer.visibility != android.view.View.VISIBLE) return

        folderBackCallback.isEnabled = false
        binding.mainToolbar.title = getString(R.string.app_name)
        binding.mainToolbar.navigationIcon = null
        binding.mainToolbar.setNavigationOnClickListener(null)
        binding.mainTabsHolder.visibility = android.view.View.VISIBLE
        binding.mainHolder.visibility = android.view.View.VISIBLE
        binding.fragmentContainer.visibility = android.view.View.GONE
        supportFragmentManager.popBackStackImmediate(
            "folder",
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        refreshMenuItems()
    }
}
