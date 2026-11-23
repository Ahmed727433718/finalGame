package com.cubeforge.stoneage

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cubeforge.stoneage.model.BigNumber
import com.cubeforge.stoneage.model.EraRepository
import com.cubeforge.stoneage.model.WorkerRouteState
import com.cubeforge.stoneage.render.GameGLSurfaceView
import com.cubeforge.stoneage.sim.GameEngine
import com.cubeforge.stoneage.sim.SaveManager
import com.cubeforge.stoneage.ui.FloatingCashOverlay
import kotlin.random.Random

/**
 * Portrait-first Arabic RTL Activity with integrated Era-Agnostic Logistics HUD:
 *  - Compact Warehouse badge (📦 المخزن: X/Y) + quick «جمع الكل» button in top HUD.
 *  - Collapsible bottom drawer with 2 tabs: [الآلات] | [النقل والعمال].
 *  - Zero hardcoded era or section names.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: GameEngine
    private lateinit var glView: GameGLSurfaceView
    private lateinit var floatingOverlay: FloatingCashOverlay

    // Top HUD views
    private lateinit var tvCurrency: TextView
    private lateinit var tvIncome: TextView
    private lateinit var tvSpeedMult: TextView
    private lateinit var tvSectionProgress: TextView
    private lateinit var btnOverclock: Button
    private lateinit var btnWarehouseBadge: Button
    private lateinit var btnCollectAll: Button

    // Collapsible bottom drawer views
    private lateinit var bottomPanel: LinearLayout
    private lateinit var drawerBodyContainer: LinearLayout
    private lateinit var btnToggleDrawer: Button
    private lateinit var tvFactoryTitle: TextView
    private lateinit var tvFactoryStats: TextView
    private lateinit var btnUnlockFactory: Button
    private lateinit var tabRow: LinearLayout
    private lateinit var btnTabMachines: Button
    private lateinit var btnTabLogistics: Button
    private lateinit var machineListContainer: LinearLayout
    private lateinit var logisticsListContainer: LinearLayout
    private lateinit var factoryNavContainer: LinearLayout
    private lateinit var btnBuyMult: Button

    // Logistics tab controls (reused without reallocating views on every tick)
    private lateinit var tvVesselInfo: TextView
    private lateinit var btnCollectVessel: Button
    private lateinit var btnUpgradeVessel: Button
    private lateinit var feederCard: LinearLayout
    private lateinit var tvFeederInfo: TextView
    private lateinit var btnFeedFactory: Button
    private lateinit var tvWorkersInfo: TextView
    private lateinit var btnHireWorker: Button
    private lateinit var btnUpgradeWorkerSpeed: Button
    private lateinit var btnUpgradeWorkerCap: Button
    private lateinit var tvManagerInfo: TextView
    private lateinit var btnHireManager: Button

    private val factoryNavButtons = ArrayList<Button>()
    private lateinit var factoryNavStates: IntArray
    private var lastSelectedNavIndex: Int = -1

    private var buyMultiplier: Int = 1
    private var activeTab: Int = 0 // 0 = الآلات, 1 = النقل والعمال
    private var isDrawerExpanded: Boolean = true
    private var drawerAnimator: ValueAnimator? = null
    private var expandedDrawerHeightPx: Int = 0
    private var collapsedDrawerHeightPx: Int = 0

    private var lastObservedTotalEarned: BigNumber = BigNumber.ZERO
    private var cashPopupTickCounter: Int = 0
    private var autoSaveCounter: Int = 0

    private val uiHandler = Handler(Looper.getMainLooper())

    private val uiTicker = object : Runnable {
        override fun run() {
            refreshHud()
            autoSaveCounter++
            if (autoSaveCounter >= 75) {
                autoSaveCounter = 0
                SaveManager.save(this@MainActivity, engine)
            }
            uiHandler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val era = EraRepository.loadEraFromAssets(this, "data/stone_age_sec1.json")
        engine = GameEngine(era)

        val offlineSec = SaveManager.load(this, engine)
        val offlineReport = engine.applyOfflineEarnings(offlineSec)

        synchronized(engine.lock) {
            lastObservedTotalEarned = engine.totalEarned
        }
        factoryNavStates = IntArray(engine.factories.size) { -1 }
        expandedDrawerHeightPx = dp(312)
        collapsedDrawerHeightPx = dp(64)

        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        glView = GameGLSurfaceView(
            context = this,
            engine = engine,
            onFactorySelected = { idx ->
                performHaptic(glView, strong = false)
                rebuildMachineRows(idx)
                setDrawerExpanded(true, animate = true)
                refreshHud()
            },
            onAnomalyCollected = { reward, tapX, tapY ->
                performHaptic(glView, strong = true)
                synchronized(engine.lock) {
                    lastObservedTotalEarned = engine.totalEarned
                }
                floatingOverlay.spawnAtPx(
                    text = "✨ +${reward.format()}",
                    xPx = tapX,
                    yPx = tapY - dp(16),
                    style = FloatingCashOverlay.STYLE_ANOMALY
                )
                refreshHud()
            },
            onVesselCollected = { _, result, tapX, tapY ->
                performHaptic(glView, strong = false)
                synchronized(engine.lock) {
                    lastObservedTotalEarned = engine.totalEarned
                }
                val coinSuffix = if (!result.earnedCoins.isZero()) " · +${result.earnedCoins.format()}🪙" else ""
                floatingOverlay.spawnAtPx(
                    text = "📦 +${result.collectedUnits.toInt()}$coinSuffix",
                    xPx = tapX,
                    yPx = tapY - dp(18),
                    style = FloatingCashOverlay.STYLE_CASH
                )
                refreshHud()
            },
            onFactoryFed = { _, fedAmount, tapX, tapY ->
                performHaptic(glView, strong = false)
                floatingOverlay.spawnAtPx(
                    text = "📥 تغذية +${fedAmount.toInt()}",
                    xPx = tapX,
                    yPx = tapY - dp(18),
                    style = FloatingCashOverlay.STYLE_UPGRADE
                )
                refreshHud()
            },
            onWarehouseTapped = {
                performHaptic(glView, strong = false)
                showWarehouseDialog()
            }
        )
        root.addView(
            glView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(buildTopHud())
        root.addView(buildBottomPanel())

        floatingOverlay = FloatingCashOverlay(this)
        root.addView(
            floatingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
        buildFactoryNavButtons()
        rebuildMachineRows(engine.selectedFactoryIndex)
        selectTab(0)
        refreshHud()

        if (offlineReport != null) {
            showOfflineDialog(offlineReport.elapsedSeconds, offlineReport.earned.format())
        }
    }

    private fun buildTopHud(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(28), dp(10), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_panel_stone)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tvCurrency = TextView(this).apply {
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFD166"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvIncome = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#E0E0E0"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        leftCol.addView(tvCurrency)
        leftCol.addView(tvIncome)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        tvSectionProgress = TextView(this).apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#A7F3D0"))
            maxLines = 1
        }
        tvSpeedMult = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#FDBA74"))
            maxLines = 1
        }
        rightCol.addView(tvSectionProgress)
        rightCol.addView(tvSpeedMult)

        btnOverclock = Button(this).apply {
            text = "⚡ 2×"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_amber)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            ).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                if (engine.triggerOverclock()) {
                    performHaptic(this, strong = true)
                    floatingOverlay.spawnNormalized(
                        text = "⚡ شحن فائق 2×!",
                        normX = 0.5f,
                        normY = 0.24f,
                        style = FloatingCashOverlay.STYLE_OVERCLOCK
                    )
                    refreshHud()
                }
            }
        }

        bar.addView(leftCol)
        bar.addView(rightCol)
        bar.addView(btnOverclock)
        wrapper.addView(bar)

        // Compact Logistics Strip: Warehouse Badge + Quick "Collect All"
        val logisticsStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }

        btnWarehouseBadge = Button(this).apply {
            text = "📦 المخزن: 0/100"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FEF3C7"))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_panel_stone)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                performHaptic(this, strong = false)
                glView.focusOnWarehouse()
                showWarehouseDialog()
            }
        }

        btnCollectAll = Button(this).apply {
            text = "📥 جمع الكل"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_emerald)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(32)
            )
            setOnClickListener {
                val res = engine.logistics.collectAllVesselsManually()
                if (res.collectedUnits > 0.0) {
                    performHaptic(this, strong = true)
                    synchronized(engine.lock) {
                        lastObservedTotalEarned = engine.totalEarned
                    }
                    val coinText = if (!res.earnedCoins.isZero()) " · +${res.earnedCoins.format()}🪙" else ""
                    floatingOverlay.spawnNormalized(
                        text = "📦 جمع الكل +${res.collectedUnits.toInt()}$coinText",
                        normX = 0.5f,
                        normY = 0.30f,
                        style = FloatingCashOverlay.STYLE_CASH
                    )
                    refreshHud()
                }
            }
        }

        logisticsStrip.addView(btnWarehouseBadge)
        logisticsStrip.addView(btnCollectAll)
        wrapper.addView(logisticsStrip)

        val navScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(5), 0, 0)
        }
        factoryNavContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        navScroll.addView(factoryNavContainer)
        wrapper.addView(navScroll)

        return wrapper
    }

    private fun buildBottomPanel(): View {
        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_panel_stone)
            setPadding(dp(12), dp(8), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                expandedDrawerHeightPx,
                Gravity.BOTTOM
            ).apply {
                setMargins(dp(8), 0, dp(8), dp(10))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
            setOnClickListener {
                performHaptic(this, strong = false)
                setDrawerExpanded(!isDrawerExpanded, animate = true)
            }
        }

        btnToggleDrawer = Button(this).apply {
            text = "▼"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_locked)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(34)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                performHaptic(this, strong = false)
                setDrawerExpanded(!isDrawerExpanded, animate = true)
            }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvFactoryTitle = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvFactoryStats = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#D6D3D1"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(tvFactoryTitle)
        titleCol.addView(tvFactoryStats)

        btnBuyMult = Button(this).apply {
            text = "×1"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_amber)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(34)).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                performHaptic(this, strong = false)
                buyMultiplier = when (buyMultiplier) {
                    1 -> 10
                    10 -> 25
                    else -> 1
                }
                text = "×$buyMultiplier"
                refreshHud()
            }
        }

        header.addView(btnToggleDrawer)
        header.addView(titleCol)
        header.addView(btnBuyMult)
        bottomPanel.addView(header)

        drawerBodyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // 2-Tab Bar: [الآلات] | [النقل والعمال]
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply {
                topMargin = dp(4)
            }
        }

        btnTabMachines = Button(this).apply {
            text = "⚙ الآلات"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(4)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                performHaptic(this, strong = false)
                selectTab(0)
            }
        }

        btnTabLogistics = Button(this).apply {
            text = "📦 النقل والعمال"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(4)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                performHaptic(this, strong = false)
                selectTab(1)
            }
        }

        tabRow.addView(btnTabMachines)
        tabRow.addView(btnTabLogistics)
        drawerBodyContainer.addView(tabRow)

        btnUnlockFactory = Button(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_emerald)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                topMargin = dp(8)
            }
            visibility = View.GONE
            setOnClickListener {
                val idx = engine.selectedFactoryIndex
                if (engine.unlockFactory(idx)) {
                    performHaptic(this, strong = true)
                    val unlockedName = engine.factories[idx].def.nameAr
                    floatingOverlay.spawnNormalized(
                        text = "🔓 تم فتح: $unlockedName!",
                        normX = 0.5f,
                        normY = 0.42f,
                        style = FloatingCashOverlay.STYLE_OVERCLOCK
                    )
                    rebuildMachineRows(idx)
                    refreshHud()
                }
            }
        }
        drawerBodyContainer.addView(btnUnlockFactory)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(6)
            }
        }

        val contentStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        machineListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        logisticsListContainer = buildLogisticsTabContent()

        contentStack.addView(machineListContainer)
        contentStack.addView(logisticsListContainer)
        scroll.addView(contentStack)
        drawerBodyContainer.addView(scroll)
        bottomPanel.addView(drawerBodyContainer)

        return bottomPanel
    }

    private fun buildLogisticsTabContent(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Card 1: Collection Vessel
        val vesselCard = makeCardContainer()
        tvVesselInfo = makeCardTitle()
        vesselCard.addView(tvVesselInfo)
        val vesselBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
            ).apply { topMargin = dp(5) }
        }
        btnCollectVessel = makeActionButton("تفريغ للمخزن", R.drawable.bg_btn_amber).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(4)
            }
            setOnClickListener {
                val idx = engine.selectedFactoryIndex
                val res = engine.logistics.collectVesselManually(idx)
                if (res.collectedUnits > 0.0) {
                    performHaptic(this, strong = false)
                    synchronized(engine.lock) { lastObservedTotalEarned = engine.totalEarned }
                    val coinSuffix = if (!res.earnedCoins.isZero()) " · +${res.earnedCoins.format()}🪙" else ""
                    floatingOverlay.spawnNormalized(
                        text = "📦 +${res.collectedUnits.toInt()}$coinSuffix",
                        normX = 0.5f,
                        normY = 0.45f,
                        style = FloatingCashOverlay.STYLE_CASH
                    )
                    refreshHud()
                }
            }
        }
        btnUpgradeVessel = makeActionButton("ترقية السعة", R.drawable.bg_btn_emerald).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(4)
            }
            setOnClickListener {
                val idx = engine.selectedFactoryIndex
                if (engine.logistics.upgradeVesselCapacity(idx)) {
                    performHaptic(this, strong = false)
                    floatingOverlay.spawnNormalized("⬆ سعة الوعاء!", 0.5f, 0.48f, FloatingCashOverlay.STYLE_UPGRADE)
                    refreshHud()
                }
            }
        }
        vesselBtnRow.addView(btnCollectVessel)
        vesselBtnRow.addView(btnUpgradeVessel)
        vesselCard.addView(vesselBtnRow)
        container.addView(vesselCard)

        // Card 2: Input Feeder (shown for factories >= 2)
        feederCard = makeCardContainer()
        val feederRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvFeederInfo = makeCardTitle().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnFeedFactory = makeActionButton("تغذية الآن", R.drawable.bg_btn_amber).apply {
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(34)).apply { marginStart = dp(6) }
            setOnClickListener {
                val idx = engine.selectedFactoryIndex
                val fed = engine.logistics.feedFactoryManually(idx)
                if (fed > 0.0) {
                    performHaptic(this, strong = false)
                    floatingOverlay.spawnNormalized("📥 تغذية +${fed.toInt()}", 0.5f, 0.45f, FloatingCashOverlay.STYLE_UPGRADE)
                    refreshHud()
                }
            }
        }
        feederRow.addView(tvFeederInfo)
        feederRow.addView(btnFeedFactory)
        feederCard.addView(feederRow)
        container.addView(feederCard)

        // Card 3: Transport Workers
        val workersCard = makeCardContainer()
        tvWorkersInfo = makeCardTitle()
        workersCard.addView(tvWorkersInfo)
        val workersBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply { topMargin = dp(5) }
        }
        btnHireWorker = makeActionButton("توظيف", R.drawable.bg_btn_emerald).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dp(3)
            }
            setOnClickListener {
                val idx = engine.selectedFactoryIndex
                if (engine.logistics.hireWorker(idx)) {
                    performHaptic(this, strong = true)
                    floatingOverlay.spawnNormalized("👷 عامل نقل جديد!", 0.5f, 0.46f, FloatingCashOverlay.STYLE_UPGRADE)
                    refreshHud()
                }
            }
        }
        btnUpgradeWorkerSpeed = makeActionButton("السرعة", R.drawable.bg_btn_amber).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            setOnClickListener {
                val idx = engine.selectedFactoryIndex
                if (engine.logistics.upgradeWorkerSpeed(idx)) {
                    performHaptic(this, strong = false)
                    floatingOverlay.spawnNormalized("⚡ سرعة العمال!", 0.5f, 0.48f, FloatingCashOverlay.STYLE_UPGRADE)
                    refreshHud()
                }
            }
        }
        btnUpgradeWorkerCap = makeActionButton("السعة", R.drawable.bg_btn_amber).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(3)
            }
            setOnClickListener {
                val idx = engine.selectedFactoryIndex
                if (engine.logistics.upgradeWorkerCapacity(idx)) {
                    performHaptic(this, strong = true)
                    val heavy = synchronized(engine.lock) { engine.logistics.factoryStates[idx].workerRoute.isHeavyCarrier }
                    val msg = if (heavy) "🛷 حامل ثقيل!" else "📦 سعة حمل +1"
                    floatingOverlay.spawnNormalized(msg, 0.5f, 0.48f, FloatingCashOverlay.STYLE_OVERCLOCK)
                    refreshHud()
                }
            }
        }
        workersBtnRow.addView(btnHireWorker)
        workersBtnRow.addView(btnUpgradeWorkerSpeed)
        workersBtnRow.addView(btnUpgradeWorkerCap)
        workersCard.addView(workersBtnRow)
        container.addView(workersCard)

        // Card 4: Factory Manager
        val mgrCard = makeCardContainer()
        val mgrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvManagerInfo = makeCardTitle().apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnHireManager = makeActionButton("تعيين المدير", R.drawable.bg_btn_emerald).apply {
            layoutParams = LinearLayout.LayoutParams(dp(116), dp(36)).apply { marginStart = dp(6) }
            setOnClickListener {
                val idx = engine.selectedFactoryIndex
                if (engine.logistics.hireManager(idx)) {
                    performHaptic(this, strong = true)
                    floatingOverlay.spawnNormalized("👑 مدير المصنع +25%!", 0.5f, 0.42f, FloatingCashOverlay.STYLE_OVERCLOCK)
                    refreshHud()
                }
            }
        }
        mgrRow.addView(tvManagerInfo)
        mgrRow.addView(btnHireManager)
        mgrCard.addView(mgrRow)
        container.addView(mgrCard)

        return container
    }

    private fun makeCardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(Color.parseColor("#2A2623"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(5) }
    }

    private fun makeCardTitle(): TextView = TextView(this).apply {
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun makeActionButton(label: String, bgRes: Int): Button = Button(this).apply {
        text = label
        textSize = 10.5f
        setTextColor(Color.WHITE)
        background = ContextCompat.getDrawable(this@MainActivity, bgRes)
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    private fun selectTab(tabIndex: Int) {
        activeTab = tabIndex
        btnTabMachines.background = ContextCompat.getDrawable(
            this,
            if (tabIndex == 0) R.drawable.bg_btn_amber else R.drawable.bg_btn_locked
        )
        btnTabLogistics.background = ContextCompat.getDrawable(
            this,
            if (tabIndex == 1) R.drawable.bg_btn_amber else R.drawable.bg_btn_locked
        )
        btnBuyMult.visibility = if (isDrawerExpanded && tabIndex == 0) View.VISIBLE else View.GONE
        refreshHud()
    }

    private fun setDrawerExpanded(expand: Boolean, animate: Boolean) {
        if (isDrawerExpanded == expand && drawerAnimator == null) return
        isDrawerExpanded = expand
        btnToggleDrawer.text = if (expand) "▼" else "▲"
        btnBuyMult.visibility = if (expand && activeTab == 0) View.VISIBLE else View.GONE

        val targetHeight = if (expand) expandedDrawerHeightPx else collapsedDrawerHeightPx
        val lp = bottomPanel.layoutParams

        drawerAnimator?.cancel()
        if (!animate) {
            lp.height = targetHeight
            bottomPanel.layoutParams = lp
            drawerBodyContainer.visibility = if (expand) View.VISIBLE else View.GONE
            drawerBodyContainer.alpha = if (expand) 1f else 0f
            return
        }

        if (expand) {
            drawerBodyContainer.visibility = View.VISIBLE
        }

        val startHeight = lp.height
        drawerAnimator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
            duration = 230L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                lp.height = h
                bottomPanel.layoutParams = lp
                val frac = anim.animatedFraction
                drawerBodyContainer.alpha = if (expand) frac else (1f - frac)
                if (!expand && frac >= 0.99f) {
                    drawerBodyContainer.visibility = View.GONE
                }
            }
            start()
        }
    }

    private fun buildFactoryNavButtons() {
        factoryNavContainer.removeAllViews()
        factoryNavButtons.clear()

        for (i in engine.factories.indices) {
            val fac = engine.factories[i]
            val btn = Button(this).apply {
                text = "${i + 1}. ${fac.def.nameAr}"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_locked)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(32)
                ).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    performHaptic(this, strong = false)
                    synchronized(engine.lock) {
                        engine.selectedFactoryIndex = i
                    }
                    glView.focusOnFactory(i)
                    rebuildMachineRows(i)
                    setDrawerExpanded(true, animate = true)
                    refreshHud()
                }
            }
            factoryNavButtons.add(btn)
            factoryNavContainer.addView(btn)
        }
    }

    private fun syncFactoryNavButtonsLocked(selectedIdx: Int) {
        for (i in factoryNavButtons.indices) {
            val fac = engine.factories[i]
            val state = when {
                fac.unlocked -> 2
                engine.canUnlockFactory(i) -> 1
                else -> 0
            }
            val btn = factoryNavButtons[i]
            if (factoryNavStates[i] != state) {
                val becameReady = (factoryNavStates[i] == 0 && state == 1)
                factoryNavStates[i] = state
                val bgRes = when (state) {
                    2 -> R.drawable.bg_btn_amber
                    1 -> R.drawable.bg_btn_emerald
                    else -> R.drawable.bg_btn_locked
                }
                btn.background = ContextCompat.getDrawable(this, bgRes)
                if (becameReady) {
                    btn.scaleX = 1.12f
                    btn.scaleY = 1.12f
                    btn.animate().scaleX(1f).scaleY(1f).setDuration(260L).start()
                }
            }
            if (lastSelectedNavIndex != selectedIdx) {
                btn.alpha = if (i == selectedIdx) 1.0f else 0.80f
                btn.setTypeface(null, if (i == selectedIdx) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        lastSelectedNavIndex = selectedIdx
    }

    private fun rebuildMachineRows(factoryIndex: Int) {
        machineListContainer.removeAllViews()
        val fac = engine.factories.getOrNull(factoryIndex) ?: return

        for (mIdx in fac.machines.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(Color.parseColor("#2A2623"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(5)
                }
                tag = mIdx
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(this).apply {
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                tag = "name"
            }
            val tvMeta = TextView(this).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#A8A29E"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                tag = "meta"
            }
            infoCol.addView(tvName)
            infoCol.addView(tvMeta)

            val btnUpgrade = Button(this).apply {
                textSize = 11.5f
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_btn_emerald)
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(36)).apply {
                    marginStart = dp(6)
                }
                tag = "btn"
                setOnClickListener {
                    if (engine.upgradeMachine(factoryIndex, mIdx, buyMultiplier)) {
                        performHaptic(this, strong = false)
                        glView.triggerMachineUpgradeFlash(factoryIndex, mIdx)
                        val newLevel = synchronized(engine.lock) {
                            engine.factories[factoryIndex].machines[mIdx].level
                        }
                        floatingOverlay.spawnNormalized(
                            text = "⬆ Lv.$newLevel",
                            normX = 0.50f + (Random.nextFloat() - 0.5f) * 0.22f,
                            normY = 0.52f,
                            style = FloatingCashOverlay.STYLE_UPGRADE
                        )
                        refreshHud()
                    }
                }
            }

            row.addView(infoCol)
            row.addView(btnUpgrade)
            machineListContainer.addView(row)
        }
    }

    private fun refreshHud() {
        var earnedDeltaText: String? = null

        synchronized(engine.lock) {
            cashPopupTickCounter++
            if (cashPopupTickCounter >= 3) {
                cashPopupTickCounter = 0
                if (engine.totalEarned > lastObservedTotalEarned) {
                    val delta = (engine.totalEarned - lastObservedTotalEarned).clampNonNegative()
                    lastObservedTotalEarned = engine.totalEarned
                    if (!delta.isZero()) {
                        earnedDeltaText = "+${delta.format()}"
                    }
                }
            }

            tvCurrency.text = "🪙 ${engine.currency.format()}"
            tvIncome.text = "الدخل: ${engine.cachedIncomePerSec.format()}/ث"
            tvSectionProgress.text = "${engine.section.nameAr}: ${engine.unlockedFactoryCount()}/${engine.factories.size}"
            tvSpeedMult.text = String.format("السرعة: %.2f×", engine.globalSpeedMultiplier())

            val wh = engine.logistics.warehouse
            btnWarehouseBadge.text = "📦 المخزن: ${wh.totalStored().toInt()}/${wh.totalCapacity.toInt()}"

            val anyCollectable = engine.logistics.hasAnyCollectableVesselLocked()
            val anyFull = engine.logistics.hasAnyFullVesselLocked()
            btnCollectAll.isEnabled = anyCollectable
            btnCollectAll.alpha = if (anyCollectable) 1.0f else 0.45f
            btnCollectAll.text = if (anyFull) "📥 جمع الكل!" else "📥 جمع الكل"
            btnCollectAll.background = ContextCompat.getDrawable(
                this,
                if (anyFull) R.drawable.bg_btn_amber else R.drawable.bg_btn_emerald
            )

            btnOverclock.text = when {
                engine.overclockRemainingSec > 0f -> "🔥 ${engine.overclockRemainingSec.toInt()}ث"
                engine.overclockCooldownSec > 0f -> "⏳ ${engine.overclockCooldownSec.toInt()}ث"
                else -> "⚡ 2×"
            }
            btnOverclock.isEnabled = (engine.overclockRemainingSec <= 0f && engine.overclockCooldownSec <= 0f)

            val idx = engine.selectedFactoryIndex
            syncFactoryNavButtonsLocked(idx)

            val fac = engine.factories[idx]
            val logState = engine.logistics.factoryStates[idx]
            val rate = fac.baseRatePerSecond() * engine.globalSpeedMultiplier() * logState.manager.speedMultiplier()

            tvFactoryTitle.text = "مصنع ${fac.def.index}: ${fac.def.nameAr}"

            if (!fac.unlocked) {
                val prev = engine.factories[idx - 1]
                val delivered = prev.deliveredToNext.toInt()
                val needed = fac.def.unlockCratesRequired
                tvFactoryStats.text = "مغلق — المطلوب: $delivered / $needed من (${prev.def.nameAr})"
                tabRow.visibility = View.GONE
                btnUnlockFactory.visibility = View.VISIBLE
                val ready = engine.canUnlockFactory(idx)
                btnUnlockFactory.isEnabled = ready
                btnUnlockFactory.text = if (ready) {
                    "🔓 فتح مصنع ${fac.def.nameAr} الآن"
                } else {
                    "🔒 متبقي ${(needed - delivered).coerceAtLeast(0)} من ${prev.def.nameAr}"
                }
                machineListContainer.visibility = View.GONE
                logisticsListContainer.visibility = View.GONE
            } else {
                val vesselStatus = if (logState.vessel.isFull) "⚠ ممتلئ" else "${logState.vessel.stock.toInt()}/${logState.vessel.capacity.toInt()}"
                val nextFac = engine.factories.getOrNull(idx + 1)
                val extraInfo = if (nextFac != null && !nextFac.unlocked) {
                    " | للفتح التالي: ${fac.deliveredToNext.toInt()}/${nextFac.def.unlockCratesRequired}"
                } else {
                    " | الوعاء: $vesselStatus"
                }
                tvFactoryStats.text = String.format("السرعة: %.2f/ث | القيمة: %.1f%s", rate, fac.def.baseCrateValue, extraInfo)

                tabRow.visibility = View.VISIBLE
                btnUnlockFactory.visibility = View.GONE
                machineListContainer.visibility = if (activeTab == 0) View.VISIBLE else View.GONE
                logisticsListContainer.visibility = if (activeTab == 1) View.VISIBLE else View.GONE

                if (activeTab == 0) {
                    val bottleneckIdx = fac.bottleneckMachineIndex()
                    for (mIdx in 0 until machineListContainer.childCount) {
                        val row = machineListContainer.getChildAt(mIdx) as LinearLayout
                        val machine = fac.machines[mIdx]
                        val tvName = row.findViewWithTag<TextView>("name")
                        val tvMeta = row.findViewWithTag<TextView>("meta")
                        val btnUp = row.findViewWithTag<Button>("btn")

                        val bottleneckBadge = if (mIdx == bottleneckIdx) " ⚠ عنق الزجاجة" else ""
                        tvName.text = "${mIdx + 1}. ${machine.def.nameAr} (Lv.${machine.level})$bottleneckBadge"
                        tvName.setTextColor(if (mIdx == bottleneckIdx) Color.parseColor("#FDBA74") else Color.WHITE)

                        tvMeta.text = String.format(
                            "%s | %.2f/ث | ×2 عند Lv.%d",
                            machine.def.archetype.labelAr,
                            machine.effectiveSpeed(),
                            machine.nextMilestoneLevel()
                        )

                        val cost = machine.upgradeCost()
                        val canAfford = engine.currency >= cost
                        btnUp.text = "ترقية\n${cost.format()}"
                        btnUp.isEnabled = canAfford
                        btnUp.alpha = if (canAfford) 1.0f else 0.45f
                    }
                } else {
                    refreshLogisticsTabLocked(idx, fac, logState)
                }
            }
        }

        if (earnedDeltaText != null) {
            val jitterX = 0.48f + (Random.nextFloat() - 0.5f) * 0.28f
            val baseY = if (isDrawerExpanded) 0.44f else 0.56f
            floatingOverlay.spawnNormalized(
                text = earnedDeltaText!!,
                normX = jitterX,
                normY = baseY,
                style = FloatingCashOverlay.STYLE_CASH
            )
        }
    }

    private fun refreshLogisticsTabLocked(
        idx: Int,
        fac: com.cubeforge.stoneage.model.FactoryState,
        logState: com.cubeforge.stoneage.model.FactoryLogisticsState
    ) {
        // 1. Vessel card
        val vessel = logState.vessel
        val fullTag = if (vessel.isFull) " ⚠ ممتلئ!" else ""
        tvVesselInfo.text = "🧺 وعاء التجميع (Lv.${vessel.level}): ${vessel.stock.toInt()}/${vessel.capacity.toInt()}$fullTag"
        tvVesselInfo.setTextColor(if (vessel.isFull) Color.parseColor("#FDBA74") else Color.WHITE)

        val canCollect = vessel.stock >= 0.5
        btnCollectVessel.isEnabled = canCollect
        btnCollectVessel.alpha = if (canCollect) 1.0f else 0.45f

        val vesselCost = vessel.upgradeCost(fac.def.baseUpgradeCost)
        val canUpVessel = engine.currency >= vesselCost
        btnUpgradeVessel.text = "سعة ${vessel.nextCapacity().toInt()} · ${vesselCost.format()}"
        btnUpgradeVessel.isEnabled = canUpVessel
        btnUpgradeVessel.alpha = if (canUpVessel) 1.0f else 0.45f

        // 2. Feeder card (factories >= 2)
        if (idx > 0 && fac.def.input != null) {
            feederCard.visibility = View.VISIBLE
            val prevFac = engine.factories[idx - 1]
            val availInWh = engine.logistics.warehouse.getStock(prevFac.def.output).toInt()
            val needTag = if (logState.inputStock < 1.0) " ⚠ ينتظر خام" else ""
            tvFeederInfo.text = "📥 خزان المدخلات (${prevFac.def.nameAr}): ${logState.inputStock.toInt()} | بالمخزن: $availInWh$needTag"
            tvFeederInfo.setTextColor(if (logState.inputStock < 1.0) Color.parseColor("#7DD3FC") else Color.WHITE)
            val canFeed = availInWh >= 1
            btnFeedFactory.isEnabled = canFeed
            btnFeedFactory.alpha = if (canFeed) 1.0f else 0.45f
        } else {
            feederCard.visibility = View.GONE
        }

        // 3. Workers card
        val route = logState.workerRoute
        val heavyBadge = if (route.isHeavyCarrier) " · 🛷 حامل ثقيل" else ""
        tvWorkersInfo.text = "👷 العمال: ${route.workerCount}/${WorkerRouteState.MAX_WORKERS} | سرعة Lv.${route.speedLevel} | سعة ${route.carryCapacity}/${WorkerRouteState.MAX_CARRY_CAPACITY}$heavyBadge"

        if (route.workerCount >= WorkerRouteState.MAX_WORKERS) {
            btnHireWorker.text = "مكتمل (${WorkerRouteState.MAX_WORKERS})"
            btnHireWorker.isEnabled = false
            btnHireWorker.alpha = 0.5f
        } else {
            val hireCost = route.hireWorkerCost(fac.def.baseUpgradeCost)
            val canHire = engine.currency >= hireCost
            btnHireWorker.text = "+عامل\n${hireCost.format()}"
            btnHireWorker.isEnabled = canHire
            btnHireWorker.alpha = if (canHire) 1.0f else 0.45f
        }

        val spdCost = route.speedUpgradeCost(fac.def.baseUpgradeCost)
        val canSpd = route.workerCount > 0 && engine.currency >= spdCost
        btnUpgradeWorkerSpeed.text = "سرعة\n${spdCost.format()}"
        btnUpgradeWorkerSpeed.isEnabled = canSpd
        btnUpgradeWorkerSpeed.alpha = if (canSpd) 1.0f else 0.45f

        if (route.capacityLevel >= WorkerRouteState.MAX_CARRY_CAPACITY) {
            btnUpgradeWorkerCap.text = "سعة قصوى"
            btnUpgradeWorkerCap.isEnabled = false
            btnUpgradeWorkerCap.alpha = 0.5f
        } else {
            val capCost = route.capacityUpgradeCost(fac.def.baseUpgradeCost)
            val canCap = route.workerCount > 0 && engine.currency >= capCost
            btnUpgradeWorkerCap.text = "سعة +1\n${capCost.format()}"
            btnUpgradeWorkerCap.isEnabled = canCap
            btnUpgradeWorkerCap.alpha = if (canCap) 1.0f else 0.45f
        }

        // 4. Manager card
        val mgr = logState.manager
        if (mgr.hired) {
            tvManagerInfo.text = "👑 المدير: معيّن (+25% سرعة + أتمتة وأرباح الغياب)"
            tvManagerInfo.setTextColor(Color.parseColor("#A7F3D0"))
            btnHireManager.text = "✅ مفعّل"
            btnHireManager.isEnabled = false
            btnHireManager.alpha = 0.65f
        } else {
            val mgrCost = mgr.hireCost(fac.def.baseUpgradeCost)
            val canMgr = engine.currency >= mgrCost
            tvManagerInfo.text = "👑 مدير المصنع (+25% سرعة + أتمتة الغياب)"
            tvManagerInfo.setTextColor(Color.WHITE)
            btnHireManager.text = "تعيين\n${mgrCost.format()}"
            btnHireManager.isEnabled = canMgr
            btnHireManager.alpha = if (canMgr) 1.0f else 0.45f
        }
    }

    private fun showWarehouseDialog() {
        val sb = StringBuilder()
        val upgradeCostText: String
        val nextCapInt: Int
        val canUpgrade: Boolean
        val hasItems: Boolean

        synchronized(engine.lock) {
            val wh = engine.logistics.warehouse
            sb.append("السعة: ${wh.totalStored().toInt()} / ${wh.totalCapacity.toInt()} (Lv.${wh.level})\n\n")
            if (wh.inventory.isEmpty()) {
                sb.append("المخزن فارغ حالياً — اجمع من أوعية المصانع أو وظّف عمالاً للنقل.\n")
                hasItems = false
            } else {
                hasItems = true
                for ((outputId, qty) in wh.inventory) {
                    val label = engine.logistics.productDisplayNameLocked(outputId)
                    sb.append("• $label: ${qty.toInt()} وحدة\n")
                }
            }
            sb.append("\n(يتم بيع الفائض تلقائياً عند امتلاء السعة أو عدم الحاجة إليه)")
            val cost = wh.upgradeCost()
            upgradeCostText = cost.format()
            nextCapInt = wh.nextCapacity().toInt()
            canUpgrade = engine.currency >= cost
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("📦 المخزن المركزي · ${engine.era.nameAr}")
            .setMessage(sb.toString())
            .setNegativeButton("إغلاق", null)

        if (hasItems) {
            builder.setNeutralButton("بيع الفائض 🪙") { _, _ ->
                val earned = engine.logistics.sellSurplus()
                if (!earned.isZero()) {
                    performHaptic(glView, strong = true)
                    synchronized(engine.lock) { lastObservedTotalEarned = engine.totalEarned }
                    floatingOverlay.spawnNormalized(
                        text = "🪙 بيع الفائض +${earned.format()}",
                        normX = 0.5f,
                        normY = 0.34f,
                        style = FloatingCashOverlay.STYLE_CASH
                    )
                    refreshHud()
                }
            }
        }

        builder.setPositiveButton("ترقية السعة إلى $nextCapInt ($upgradeCostText)") { _, _ ->
            if (canUpgrade && engine.logistics.upgradeWarehouseCapacity()) {
                performHaptic(glView, strong = true)
                floatingOverlay.spawnNormalized("📦 ترقية المخزن!", 0.5f, 0.32f, FloatingCashOverlay.STYLE_UPGRADE)
                refreshHud()
            }
        }

        builder.show()
    }

    private fun performHaptic(view: View, strong: Boolean) {
        val code = if (strong) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(code)
    }

    private fun showOfflineDialog(elapsedSec: Long, earnedText: String) {
        val mins = elapsedSec / 60L
        AlertDialog.Builder(this)
            .setTitle("أهلاً بعودتك إلى ${engine.era.nameAr}!")
            .setMessage("عملت مصانعك المُدارة أثناء غيابك لمدة $mins دقيقة وأنتجت:\n\n🪙 +$earnedText")
            .setPositiveButton("جمع الأرباح", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        uiHandler.post(uiTicker)
    }

    override fun onPause() {
        super.onPause()
        drawerAnimator?.cancel()
        uiHandler.removeCallbacks(uiTicker)
        SaveManager.save(this, engine)
        glView.onPause()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
