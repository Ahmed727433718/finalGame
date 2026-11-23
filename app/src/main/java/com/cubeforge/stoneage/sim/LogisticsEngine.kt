package com.cubeforge.stoneage.sim

import android.graphics.Color
import com.cubeforge.stoneage.model.BigNumber
import com.cubeforge.stoneage.model.CollectResult
import com.cubeforge.stoneage.model.FactoryLogisticsState
import com.cubeforge.stoneage.model.FactoryState
import com.cubeforge.stoneage.model.FlyingCrateAnim
import com.cubeforge.stoneage.model.ManagerState
import com.cubeforge.stoneage.model.VesselState
import com.cubeforge.stoneage.model.WarehouseState
import com.cubeforge.stoneage.model.WorkerPhase
import com.cubeforge.stoneage.model.WorkerRouteState
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shared Era-Agnostic Logistics Engine.
 * Handles vessels, central warehouse with auto-sell surplus, factory input hoppers,
 * 3D transport worker loops, managers, manual one-tap transfers, and pooled parabolic flying crates.
 */
class LogisticsEngine(
    private val engine: GameEngine
) {
    companion object {
        const val FLYING_CRATE_POOL_SIZE = 32
        const val MAX_INPUT_STOCK = 60.0
        const val MANUAL_FEED_BATCH = 20.0
        const val WORKER_FEED_THRESHOLD = 18.0
        const val RESERVE_FOR_UNLOCKED_NEXT = 24.0
        const val RESERVE_FOR_LOCKED_NEXT = 6.0
    }

    val warehouse = WarehouseState()
    val factoryStates: Array<FactoryLogisticsState>
    val flyingCrates: Array<FlyingCrateAnim> = Array(FLYING_CRATE_POOL_SIZE) { FlyingCrateAnim() }

    private var flyingCrateCursor: Int = 0
    private val managerAutoDispatchAccum: FloatArray

    init {
        val factories = engine.factories
        val count = factories.size

        // Position Central Warehouse cleanly beside Factory 1 so it is visible on frame 1 in every section
        val firstFac = factories.firstOrNull()
        warehouse.worldX = 7.8f
        warehouse.worldZ = 0.0f

        factoryStates = Array(count) { idx ->
            val fac = factories[idx]
            val argb = try {
                Color.parseColor(fac.def.colorHex)
            } catch (_: Exception) {
                Color.rgb(210, 140, 60)
            }
            val dir = if (fac.facingRight) 1f else -1f
            val vesselX = fac.worldX + 5.35f
            val feederX = fac.worldX - 4.65f
            val managerX = fac.worldX - 4.4f
            val managerZ = fac.worldZ + 2.05f

            FactoryLogisticsState(
                factoryIndex = idx,
                vessel = VesselState(),
                inputStock = 0.0,
                workerRoute = WorkerRouteState(),
                manager = ManagerState(),
                vesselX = vesselX,
                vesselZ = fac.worldZ,
                feederX = feederX,
                feederZ = fac.worldZ,
                managerX = managerX,
                managerZ = managerZ,
                colorR = Color.red(argb) / 255f,
                colorG = Color.green(argb) / 255f,
                colorB = Color.blue(argb) / 255f
            )
        }
        managerAutoDispatchAccum = FloatArray(count)
    }

    fun managerSpeedMultiplier(factoryIdx: Int): Double {
        return factoryStates.getOrNull(factoryIdx)?.manager?.speedMultiplier() ?: 1.0
    }

    /**
     * Advances 3D worker agents, manager auto-dispatch safety net, and parabolic flying crates.
     * Called strictly inside synchronized(engine.lock) from GameEngine.update(dt).
     */
    fun updateLocked(dt: Float, globalMult: Double) {
        val factories = engine.factories

        // 1. Update flying parabolic crates (zero allocations)
        for (i in 0 until FLYING_CRATE_POOL_SIZE) {
            val fc = flyingCrates[i]
            if (!fc.active) continue
            fc.t += dt / fc.duration
            if (fc.t >= 1f) {
                fc.active = false
            }
        }

        // 2. Update workers & manager automation per factory
        for (i in factories.indices) {
            val fac = factories[i]
            if (!fac.unlocked) continue
            val state = factoryStates[i]
            val route = state.workerRoute
            val mgrMult = state.manager.speedMultiplier()

            // Manager full-automation safety net: if hired and vessel is full (or no workers yet),
            // automatically ships small batches to warehouse so production never stalls.
            if (state.manager.hired && (route.workerCount == 0 || state.vessel.isFull)) {
                managerAutoDispatchAccum[i] += dt
                if (managerAutoDispatchAccum[i] >= 1.5f) {
                    managerAutoDispatchAccum[i] = 0f
                    if (state.vessel.stock >= 1.0) {
                        val batch = min(state.vessel.stock, max(2.0, state.vessel.capacity * 0.25))
                        state.vessel.stock -= batch
                        depositToWarehouseLocked(i, batch)
                        spawnFlyingCrateLocked(
                            state.vesselX, 0.85f, state.vesselZ,
                            warehouse.worldX, 1.0f, warehouse.worldZ,
                            state.colorR, state.colorG, state.colorB
                        )
                    }
                    // Also auto-feed this factory if it needs input and warehouse has it
                    if (i > 0 && fac.def.input != null && state.inputStock < 8.0) {
                        val prevOutputId = factories[i - 1].def.output
                        val avail = warehouse.getStock(prevOutputId)
                        if (avail >= 1.0) {
                            val pull = min(avail, 6.0)
                            setWarehouseStockLocked(prevOutputId, avail - pull)
                            state.inputStock = min(MAX_INPUT_STOCK, state.inputStock + pull)
                            val prevState = factoryStates[i - 1]
                            spawnFlyingCrateLocked(
                                warehouse.worldX, 1.0f, warehouse.worldZ,
                                state.feederX, 0.85f, state.feederZ,
                                prevState.colorR, prevState.colorG, prevState.colorB
                            )
                        }
                    }
                }
            }

            // Update active 3D WorkerAgents
            val activeWorkers = route.workerCount.coerceIn(0, WorkerRouteState.MAX_WORKERS)
            if (activeWorkers <= 0) continue

            val moveSpeed = (route.moveSpeedUnitsPerSec(mgrMult) * globalMult).toFloat().coerceIn(2.0f, 16.0f)
            val nextFac = factories.getOrNull(i + 1)
            val nextState = factoryStates.getOrNull(i + 1)

            for (w in 0 until activeWorkers) {
                val agent = route.agents[w]
                if (!agent.active) {
                    agent.active = true
                    agent.factoryIndex = i
                    agent.worldX = state.vesselX
                    agent.worldZ = state.vesselZ + (w - 1) * 0.55f
                    agent.phase = WorkerPhase.TO_VESSEL
                }

                when (agent.phase) {
                    WorkerPhase.IDLE, WorkerPhase.TO_VESSEL -> {
                        val arrived = moveAgentToward(agent, state.vesselX, state.vesselZ, moveSpeed, dt)
                        if (arrived) {
                            if (state.vessel.stock >= 1.0) {
                                val take = min(state.vessel.stock, route.carryCapacity.toDouble())
                                state.vessel.stock -= take
                                agent.carrying = true
                                agent.carryAmount = take
                                agent.carryOutputId = fac.def.output
                                agent.carryColorR = state.colorR
                                agent.carryColorG = state.colorG
                                agent.carryColorB = state.colorB
                                agent.phase = WorkerPhase.TO_WAREHOUSE
                            } else if (
                                nextFac != null &&
                                nextFac.unlocked &&
                                nextState != null &&
                                nextState.inputStock < WORKER_FEED_THRESHOLD &&
                                warehouse.getStock(fac.def.output) >= 1.0
                            ) {
                                // Vessel empty, go to warehouse to fetch material for next factory
                                agent.carrying = false
                                agent.carryAmount = 0.0
                                agent.phase = WorkerPhase.TO_WAREHOUSE
                            } else {
                                agent.phase = WorkerPhase.IDLE
                            }
                        }
                    }

                    WorkerPhase.TO_WAREHOUSE -> {
                        val arrived = moveAgentToward(agent, warehouse.worldX, warehouse.worldZ, moveSpeed, dt)
                        if (arrived) {
                            if (agent.carrying && agent.carryAmount > 0.0) {
                                depositToWarehouseLocked(i, agent.carryAmount)
                                agent.carrying = false
                                agent.carryAmount = 0.0
                            }
                            // Check if next factory needs raw material from warehouse
                            if (
                                nextFac != null &&
                                nextFac.unlocked &&
                                nextState != null &&
                                nextState.inputStock < WORKER_FEED_THRESHOLD
                            ) {
                                val avail = warehouse.getStock(fac.def.output)
                                if (avail >= 1.0) {
                                    val take = min(avail, route.carryCapacity.toDouble())
                                    setWarehouseStockLocked(fac.def.output, avail - take)
                                    agent.carrying = true
                                    agent.carryAmount = take
                                    agent.carryOutputId = fac.def.output
                                    agent.carryColorR = state.colorR
                                    agent.carryColorG = state.colorG
                                    agent.carryColorB = state.colorB
                                    agent.phase = WorkerPhase.TO_NEXT_FEEDER
                                    continue
                                }
                            }
                            agent.phase = WorkerPhase.TO_VESSEL
                        }
                    }

                    WorkerPhase.TO_NEXT_FEEDER -> {
                        if (nextFac == null || !nextFac.unlocked || nextState == null) {
                            // Fallback if next factory not available
                            if (agent.carrying && agent.carryAmount > 0.0) {
                                depositToWarehouseLocked(i, agent.carryAmount)
                                agent.carrying = false
                                agent.carryAmount = 0.0
                            }
                            agent.phase = WorkerPhase.TO_VESSEL
                        } else {
                            val arrived = moveAgentToward(agent, nextState.feederX, nextState.feederZ, moveSpeed, dt)
                            if (arrived) {
                                if (agent.carrying && agent.carryAmount > 0.0) {
                                    nextState.inputStock = min(MAX_INPUT_STOCK, nextState.inputStock + agent.carryAmount)
                                    agent.carrying = false
                                    agent.carryAmount = 0.0
                                }
                                agent.phase = WorkerPhase.TO_VESSEL
                            }
                        }
                    }
                }
            }
        }
    }

    private fun moveAgentToward(
        agent: com.cubeforge.stoneage.model.WorkerAgent,
        targetX: Float,
        targetZ: Float,
        speed: Float,
        dt: Float
    ): Boolean {
        val dx = targetX - agent.worldX
        val dz = targetZ - agent.worldZ
        val dist = hypot(dx.toDouble(), dz.toDouble()).toFloat()
        if (dist <= 0.35f) {
            agent.worldX = targetX
            agent.worldZ = targetZ
            return true
        }
        val step = min(dist, speed * dt)
        val inv = 1f / dist
        agent.worldX += dx * inv * step
        agent.worldZ += dz * inv * step
        agent.facingAngleDeg = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
        agent.walkPhase += dt * speed * 2.4f
        return dist - step <= 0.35f
    }

    /**
     * Deposits units from [factoryIdx] into Central Warehouse, updates unlock progress,
     * and automatically sells surplus above reserve/capacity so coins never stall.
     */
    internal fun depositToWarehouseLocked(factoryIdx: Int, amount: Double): BigNumber {
        if (amount <= 0.0) return BigNumber.ZERO
        val fac = engine.factories.getOrNull(factoryIdx) ?: return BigNumber.ZERO
        val nextFac = engine.factories.getOrNull(factoryIdx + 1)

        // Advance next factory unlock progress
        if (nextFac != null && !nextFac.unlocked) {
            fac.deliveredToNext += amount
        }

        val outputId = fac.def.output
        val currentStock = warehouse.getStock(outputId)
        val reserveTarget = when {
            nextFac == null -> 0.0 // Final factory ships directly for revenue
            nextFac.unlocked -> RESERVE_FOR_UNLOCKED_NEXT
            else -> RESERVE_FOR_LOCKED_NEXT
        }

        val needForReserve = (reserveTarget - currentStock).coerceAtLeast(0.0)
        val spaceLeft = warehouse.remainingCapacity()
        val toStore = min(amount, min(needForReserve, spaceLeft))
        val toAutoSell = (amount - toStore).coerceAtLeast(0.0)

        if (toStore > 0.0) {
            setWarehouseStockLocked(outputId, currentStock + toStore)
        }

        var earned = BigNumber.ZERO
        if (toAutoSell > 0.0) {
            earned = BigNumber.fromDouble(fac.def.baseCrateValue * toAutoSell)
            engine.addEarningsInternal(earned)
        }
        return earned
    }

    private fun setWarehouseStockLocked(outputId: String, newValue: Double) {
        warehouse.setStock(outputId, newValue)
    }

    private fun spawnFlyingCrateLocked(
        sx: Float, sy: Float, sz: Float,
        ex: Float, ey: Float, ez: Float,
        r: Float, g: Float, b: Float
    ) {
        val slot = flyingCrates[flyingCrateCursor]
        flyingCrateCursor = (flyingCrateCursor + 1) % FLYING_CRATE_POOL_SIZE
        slot.active = true
        slot.startX = sx
        slot.startY = sy
        slot.startZ = sz
        slot.endX = ex
        slot.endY = ey
        slot.endZ = ez
        slot.t = 0f
        slot.duration = 0.50f
        val dist = hypot((ex - sx).toDouble(), (ez - sz).toDouble()).toFloat()
        slot.peakHeight = (1.9f + dist * 0.14f).coerceIn(2.0f, 5.5f)
        slot.colorR = r
        slot.colorG = g
        slot.colorB = b
    }

    // ==================== MANUAL ONE-TAP TRANSFERS ====================

    /**
     * Empties factory [factoryIdx]'s collection vessel into the Central Warehouse and launches a flying crate.
     */
    fun collectVesselManually(factoryIdx: Int): CollectResult {
        synchronized(engine.lock) {
            val fac = engine.factories.getOrNull(factoryIdx) ?: return CollectResult(0.0, BigNumber.ZERO)
            if (!fac.unlocked) return CollectResult(0.0, BigNumber.ZERO)
            val state = factoryStates[factoryIdx]
            val amount = state.vessel.stock
            if (amount < 0.5) return CollectResult(0.0, BigNumber.ZERO)

            state.vessel.stock = 0.0
            val earned = depositToWarehouseLocked(factoryIdx, amount)
            spawnFlyingCrateLocked(
                state.vesselX, 0.85f, state.vesselZ,
                warehouse.worldX, 1.0f, warehouse.worldZ,
                state.colorR, state.colorG, state.colorB
            )
            return CollectResult(amount, earned)
        }
    }

    /**
     * Collects all unlocked factories whose vessels have >= 0.5 units in one tap.
     */
    fun collectAllVesselsManually(): CollectResult {
        synchronized(engine.lock) {
            var totalUnits = 0.0
            var totalEarned = BigNumber.ZERO
            for (i in engine.factories.indices) {
                val fac = engine.factories[i]
                if (!fac.unlocked) continue
                val state = factoryStates[i]
                if (state.vessel.stock < 0.5) continue
                val amount = state.vessel.stock
                state.vessel.stock = 0.0
                totalUnits += amount
                totalEarned += depositToWarehouseLocked(i, amount)
                spawnFlyingCrateLocked(
                    state.vesselX, 0.85f, state.vesselZ,
                    warehouse.worldX, 1.0f, warehouse.worldZ,
                    state.colorR, state.colorG, state.colorB
                )
            }
            return CollectResult(totalUnits, totalEarned)
        }
    }

    /**
     * Pulls available input product from the Central Warehouse into [factoryIdx]'s inputStock
     * and launches a parabolic flying crate so the factory begins manufacturing immediately.
     */
    fun feedFactoryManually(factoryIdx: Int): Double {
        synchronized(engine.lock) {
            if (factoryIdx <= 0 || factoryIdx >= engine.factories.size) return 0.0
            val fac = engine.factories[factoryIdx]
            if (!fac.unlocked || fac.def.input == null) return 0.0

            val state = factoryStates[factoryIdx]
            val room = (MAX_INPUT_STOCK - state.inputStock).coerceAtLeast(0.0)
            if (room < 0.5) return 0.0

            val prevFac = engine.factories[factoryIdx - 1]
            val prevOutputId = prevFac.def.output
            val avail = warehouse.getStock(prevOutputId)
            if (avail < 0.5) return 0.0

            val transfer = min(avail, min(room, MANUAL_FEED_BATCH))
            setWarehouseStockLocked(prevOutputId, avail - transfer)
            state.inputStock += transfer

            val prevState = factoryStates[factoryIdx - 1]
            spawnFlyingCrateLocked(
                warehouse.worldX, 1.0f, warehouse.worldZ,
                state.feederX, 0.85f, state.feederZ,
                prevState.colorR, prevState.colorG, prevState.colorB
            )
            return transfer
        }
    }

    /**
     * Sells surplus items stored in the Central Warehouse for immediate coins.
     */
    fun sellSurplus(): BigNumber {
        synchronized(engine.lock) {
            var earned = BigNumber.ZERO
            // First pass: sell everything above a small 5-unit operational buffer for unlocked consumers
            for (i in engine.factories.indices) {
                val fac = engine.factories[i]
                val outputId = fac.def.output
                val qty = warehouse.getStock(outputId)
                if (qty <= 0.0) continue
                val nextUnlocked = engine.factories.getOrNull(i + 1)?.unlocked == true
                val keep = if (nextUnlocked) 5.0 else 0.0
                val sellQty = (qty - keep).coerceAtLeast(0.0)
                if (sellQty > 0.0) {
                    setWarehouseStockLocked(outputId, qty - sellQty)
                    earned += BigNumber.fromDouble(fac.def.baseCrateValue * sellQty)
                }
            }
            // If everything was within the 5-unit buffer, allow selling all remaining stock on manual tap
            if (earned.isZero()) {
                for (i in engine.factories.indices) {
                    val fac = engine.factories[i]
                    val outputId = fac.def.output
                    val qty = warehouse.getStock(outputId)
                    if (qty > 0.0) {
                        setWarehouseStockLocked(outputId, 0.0)
                        earned += BigNumber.fromDouble(fac.def.baseCrateValue * qty)
                    }
                }
            }
            if (!earned.isZero()) {
                engine.addEarningsInternal(earned)
            }
            return earned
        }
    }

    // ==================== LOGISTICS UPGRADES ====================

    fun hireWorker(factoryIdx: Int): Boolean {
        synchronized(engine.lock) {
            val fac = engine.factories.getOrNull(factoryIdx) ?: return false
            if (!fac.unlocked) return false
            val state = factoryStates[factoryIdx]
            val route = state.workerRoute
            if (route.workerCount >= WorkerRouteState.MAX_WORKERS) return false

            val cost = route.hireWorkerCost(fac.def.baseUpgradeCost)
            if (engine.currency < cost) return false

            engine.currency = (engine.currency - cost).clampNonNegative()
            val slotIdx = route.workerCount
            route.workerCount += 1
            val agent = route.agents[slotIdx]
            agent.active = true
            agent.factoryIndex = factoryIdx
            agent.worldX = state.vesselX
            agent.worldZ = state.vesselZ + (slotIdx - 1) * 0.55f
            agent.phase = WorkerPhase.TO_VESSEL
            agent.carrying = false
            agent.carryAmount = 0.0
            engine.recalculateIncomePerSec()
            return true
        }
    }

    fun upgradeWorkerSpeed(factoryIdx: Int): Boolean {
        synchronized(engine.lock) {
            val fac = engine.factories.getOrNull(factoryIdx) ?: return false
            if (!fac.unlocked) return false
            val route = factoryStates[factoryIdx].workerRoute
            if (route.workerCount <= 0) return false

            val cost = route.speedUpgradeCost(fac.def.baseUpgradeCost)
            if (engine.currency < cost) return false

            engine.currency = (engine.currency - cost).clampNonNegative()
            route.speedLevel += 1
            return true
        }
    }

    fun upgradeWorkerCapacity(factoryIdx: Int): Boolean {
        synchronized(engine.lock) {
            val fac = engine.factories.getOrNull(factoryIdx) ?: return false
            if (!fac.unlocked) return false
            val route = factoryStates[factoryIdx].workerRoute
            if (route.workerCount <= 0 || route.capacityLevel >= WorkerRouteState.MAX_CARRY_CAPACITY) return false

            val cost = route.capacityUpgradeCost(fac.def.baseUpgradeCost)
            if (engine.currency < cost) return false

            engine.currency = (engine.currency - cost).clampNonNegative()
            route.capacityLevel += 1
            return true
        }
    }

    fun hireManager(factoryIdx: Int): Boolean {
        synchronized(engine.lock) {
            val fac = engine.factories.getOrNull(factoryIdx) ?: return false
            if (!fac.unlocked) return false
            val mgr = factoryStates[factoryIdx].manager
            if (mgr.hired) return false

            val cost = mgr.hireCost(fac.def.baseUpgradeCost)
            if (engine.currency < cost) return false

            engine.currency = (engine.currency - cost).clampNonNegative()
            mgr.hired = true
            engine.recalculateIncomePerSec()
            return true
        }
    }

    fun upgradeVesselCapacity(factoryIdx: Int): Boolean {
        synchronized(engine.lock) {
            val fac = engine.factories.getOrNull(factoryIdx) ?: return false
            if (!fac.unlocked) return false
            val vessel = factoryStates[factoryIdx].vessel

            val cost = vessel.upgradeCost(fac.def.baseUpgradeCost)
            if (engine.currency < cost) return false

            engine.currency = (engine.currency - cost).clampNonNegative()
            vessel.capacity = vessel.nextCapacity()
            vessel.level += 1
            return true
        }
    }

    fun upgradeWarehouseCapacity(): Boolean {
        synchronized(engine.lock) {
            val cost = warehouse.upgradeCost()
            if (engine.currency < cost) return false

            engine.currency = (engine.currency - cost).clampNonNegative()
            warehouse.totalCapacity = warehouse.nextCapacity()
            warehouse.level += 1
            return true
        }
    }

    // ==================== 3D PICKING HELPERS ====================

    fun pickWarehouseAt(wx: Float, wz: Float): Boolean {
        synchronized(engine.lock) {
            val dx = wx - warehouse.worldX
            val dz = wz - warehouse.worldZ
            return (dx * dx + dz * dz) <= 3.1f * 3.1f
        }
    }

    fun pickVesselAt(wx: Float, wz: Float): Int {
        synchronized(engine.lock) {
            for (i in factoryStates.indices) {
                if (!engine.factories[i].unlocked) continue
                val st = factoryStates[i]
                val dx = wx - st.vesselX
                val dz = wz - st.vesselZ
                if (dx * dx + dz * dz <= 1.65f * 1.65f) {
                    return i
                }
            }
            return -1
        }
    }

    fun pickFeederAt(wx: Float, wz: Float): Int {
        synchronized(engine.lock) {
            for (i in 1 until factoryStates.size) {
                if (!engine.factories[i].unlocked) continue
                val st = factoryStates[i]
                val dx = wx - st.feederX
                val dz = wz - st.feederZ
                if (dx * dx + dz * dz <= 1.65f * 1.65f) {
                    return i
                }
            }
            return -1
        }
    }

    /**
     * Returns true if at least one unlocked factory has a vessel >= 80% full or with collectable stock.
     */
    fun hasAnyCollectableVesselLocked(): Boolean {
        for (i in engine.factories.indices) {
            if (engine.factories[i].unlocked && factoryStates[i].vessel.stock >= 0.5) {
                return true
            }
        }
        return false
    }

    fun hasAnyFullVesselLocked(): Boolean {
        for (i in engine.factories.indices) {
            if (engine.factories[i].unlocked && factoryStates[i].vessel.fillRatio >= 0.80f) {
                return true
            }
        }
        return false
    }

    fun productDisplayNameLocked(outputId: String): String {
        val fac = engine.factories.firstOrNull { it.def.output == outputId }
        return fac?.def?.nameAr ?: outputId
    }
}
