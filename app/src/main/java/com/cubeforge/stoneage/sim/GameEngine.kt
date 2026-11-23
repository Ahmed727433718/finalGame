package com.cubeforge.stoneage.sim

import android.graphics.Color
import com.cubeforge.stoneage.model.BigNumber
import com.cubeforge.stoneage.model.Era
import com.cubeforge.stoneage.model.FactoryState
import com.cubeforge.stoneage.model.GoldenAnomaly
import com.cubeforge.stoneage.model.MachineState
import com.cubeforge.stoneage.model.VisualCrate
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class OfflineReport(
    val elapsedSeconds: Long,
    val earned: BigNumber
)

/**
 * Core simulation engine (Era & Section agnostic).
 * Integrated with shared LogisticsEngine (vessels, central warehouse, input hoppers, workers, managers).
 * Thread-safe via internal lock so OpenGL ES render thread and Android UI thread stay synchronized.
 */
class GameEngine(val era: Era) {

    val lock = Any()

    val section = era.sections.first()
    val factories: List<FactoryState>
    val visualCrates: Array<VisualCrate> = Array(96) { VisualCrate() }
    val goldenAnomaly = GoldenAnomaly()
    val logistics: LogisticsEngine

    var currency: BigNumber = BigNumber.fromDouble(50.0)
    var totalEarned: BigNumber = BigNumber.fromDouble(50.0)
    var selectedFactoryIndex: Int = 0

    // Overclock 2x state
    var overclockRemainingSec: Float = 0f
    var overclockCooldownSec: Float = 0f
    val overclockDurationSec: Float = 15f
    val overclockMaxCooldownSec: Float = 20f

    private var nextAnomalyTimerSec: Float = 35f

    var simTimeSec: Float = 0f
        private set

    var cachedIncomePerSec: BigNumber = BigNumber.ZERO
        private set

    private var crateSpawnCursor: Int = 0
    private val crateSpawnAccumulator: FloatArray

    companion object {
        const val FACTORY_SPACING_Z = 7.4f
        const val FACTORY_HALF_WIDTH = 5.2f
        const val MAX_OFFLINE_SECONDS = 4L * 3600L // 4 hours cap
    }

    init {
        val count = section.factories.size
        val startZ = -((count - 1) * FACTORY_SPACING_Z) * 0.5f

        factories = section.factories.mapIndexed { idx, fDef ->
            val mStates = fDef.machines.map { mDef ->
                MachineState(
                    def = mDef,
                    level = 1,
                    baseUpgradeCost = fDef.baseUpgradeCost
                )
            }
            FactoryState(
                def = fDef,
                machines = mStates,
                unlocked = (idx == 0),
                worldX = 0f,
                worldZ = startZ + idx * FACTORY_SPACING_Z,
                facingRight = true
            )
        }
        crateSpawnAccumulator = FloatArray(count)
        logistics = LogisticsEngine(this)
        recalculateIncomePerSec()
    }

    /**
     * Final factory in the section grants a global speed multiplier.
     */
    fun communityFireMultiplier(): Double {
        val lastFactory = factories.lastOrNull() ?: return 1.0
        if (!lastFactory.unlocked || lastFactory.totalProduced <= 0.0) return 1.0
        return 1.0 + 0.15 * ln(1.0 + lastFactory.totalProduced)
    }

    fun overclockMultiplier(): Double = if (overclockRemainingSec > 0f) 2.0 else 1.0

    fun globalSpeedMultiplier(): Double = communityFireMultiplier() * overclockMultiplier()

    /**
     * Advances factory production and logistics simulation by dt seconds.
     */
    fun update(rawDt: Float) {
        val dt = rawDt.coerceIn(0.001f, 0.25f)
        synchronized(lock) {
            simTimeSec += dt

            if (overclockRemainingSec > 0f) {
                overclockRemainingSec = max(0f, overclockRemainingSec - dt)
            } else if (overclockCooldownSec > 0f) {
                overclockCooldownSec = max(0f, overclockCooldownSec - dt)
            }

            updateGoldenAnomaly(dt)

            val globalMult = globalSpeedMultiplier()

            for (i in factories.indices) {
                val fac = factories[i]
                if (!fac.unlocked) continue

                val logState = logistics.factoryStates[i]
                val vessel = logState.vessel
                // Pause accumulation while the collection vessel is full
                if (vessel.isFull) continue

                val mgrMult = logState.manager.speedMultiplier()
                val rate = fac.baseRatePerSecond() * globalMult * mgrMult
                val needsInput = (i > 0 && fac.def.input != null)
                val hasInput = !needsInput || (logState.inputStock >= 1.0)

                if (hasInput || fac.productionProgress > 0.0) {
                    if (needsInput && fac.productionProgress == 0.0) {
                        if (logState.inputStock >= 1.0) {
                            logState.inputStock -= 1.0
                        } else {
                            continue
                        }
                    }

                    fac.productionProgress += rate * dt
                    if (fac.productionProgress >= 1.0) {
                        var cycles = fac.productionProgress.toInt()
                        if (needsInput && cycles > 1) {
                            val extraAvailable = logState.inputStock.toInt()
                            val actualExtra = min(cycles - 1, extraAvailable)
                            logState.inputStock -= actualExtra.toDouble()
                            cycles = 1 + actualExtra
                        }
                        fac.productionProgress -= fac.productionProgress.toInt().toDouble()

                        val roomInVessel = (vessel.capacity - vessel.stock).coerceAtLeast(0.0)
                        val produced = min(cycles.toDouble(), roomInVessel)
                        if (produced > 0.0) {
                            vessel.stock = min(vessel.capacity, vessel.stock + produced)
                            fac.totalProduced += produced
                            fac.storedCrates = vessel.stock
                            trySpawnVisualCrate(i, i + 1, fac.def.colorHex, dt)
                        }
                    }
                }
            }

            // Advance 3D workers, manager automation, and flying parabolic crates
            logistics.updateLocked(dt, globalMult)

            // Update visual belt crates
            val crateSpeed = (0.55f * globalMult.toFloat()).coerceAtMost(2.2f)
            for (c in visualCrates) {
                if (!c.active) continue
                c.t += crateSpeed * dt
                if (c.t >= 1f) {
                    c.active = false
                }
            }

            recalculateIncomePerSec()
        }
    }

    private fun trySpawnVisualCrate(fromIdx: Int, toIdx: Int, colorHex: String, dt: Float) {
        crateSpawnAccumulator[fromIdx] += dt
        if (crateSpawnAccumulator[fromIdx] < 0.40f) return
        crateSpawnAccumulator[fromIdx] = 0f

        val slot = visualCrates[crateSpawnCursor]
        crateSpawnCursor = (crateSpawnCursor + 1) % visualCrates.size
        val argb = try {
            Color.parseColor(colorHex)
        } catch (_: Exception) {
            Color.rgb(210, 140, 60)
        }
        slot.active = true
        slot.fromFactoryIndex = fromIdx
        slot.toFactoryIndex = toIdx
        slot.t = 0f
        slot.colorR = Color.red(argb) / 255f
        slot.colorG = Color.green(argb) / 255f
        slot.colorB = Color.blue(argb) / 255f
    }

    private fun updateGoldenAnomaly(dt: Float) {
        if (goldenAnomaly.active) {
            goldenAnomaly.timeRemaining -= dt
            goldenAnomaly.bobPhase += dt * 3.5f
            if (goldenAnomaly.timeRemaining <= 0f) {
                goldenAnomaly.active = false
                nextAnomalyTimerSec = 55f + Random.nextFloat() * 20f
            }
        } else {
            nextAnomalyTimerSec -= dt
            if (nextAnomalyTimerSec <= 0f) {
                val unlockedCount = factories.count { it.unlocked }.coerceAtLeast(1)
                goldenAnomaly.factoryIndex = Random.nextInt(unlockedCount)
                goldenAnomaly.timeRemaining = 16f
                goldenAnomaly.bobPhase = 0f
                goldenAnomaly.active = true
            }
        }
    }

    fun collectGoldenAnomaly(): BigNumber? {
        synchronized(lock) {
            if (!goldenAnomaly.active) return null
            goldenAnomaly.active = false
            nextAnomalyTimerSec = 55f + Random.nextFloat() * 20f
            val baseReward = cachedIncomePerSec * 30.0
            val minReward = BigNumber.fromDouble(50.0)
            val reward = if (baseReward > minReward) baseReward else minReward
            addEarningsInternal(reward)
            return reward
        }
    }

    fun triggerOverclock(): Boolean {
        synchronized(lock) {
            if (overclockRemainingSec > 0f || overclockCooldownSec > 0f) return false
            overclockRemainingSec = overclockDurationSec
            overclockCooldownSec = overclockMaxCooldownSec
            return true
        }
    }

    fun upgradeMachine(factoryIdx: Int, machineIdx: Int, count: Int = 1): Boolean {
        synchronized(lock) {
            val fac = factories.getOrNull(factoryIdx) ?: return false
            if (!fac.unlocked) return false
            val machine = fac.machines.getOrNull(machineIdx) ?: return false

            var upgradedAny = false
            for (step in 0 until count) {
                val cost = machine.upgradeCost()
                if (currency >= cost) {
                    currency = (currency - cost).clampNonNegative()
                    machine.level += 1
                    upgradedAny = true
                } else {
                    break
                }
            }
            if (upgradedAny) {
                recalculateIncomePerSec()
            }
            return upgradedAny
        }
    }

    fun canUnlockFactory(factoryIdx: Int): Boolean {
        synchronized(lock) {
            val fac = factories.getOrNull(factoryIdx) ?: return false
            if (fac.unlocked || factoryIdx == 0) return false
            val prev = factories[factoryIdx - 1]
            return prev.unlocked && prev.deliveredToNext >= fac.def.unlockCratesRequired.toDouble()
        }
    }

    fun unlockFactory(factoryIdx: Int): Boolean {
        synchronized(lock) {
            if (!canUnlockFactory(factoryIdx)) return false
            val fac = factories[factoryIdx]
            val prev = factories[factoryIdx - 1]
            prev.deliveredToNext = max(0.0, prev.deliveredToNext - fac.def.unlockCratesRequired.toDouble())
            fac.unlocked = true
            // Seed starter inputStock so the newly unlocked factory animates immediately
            logistics.factoryStates[factoryIdx].inputStock += 4.0
            selectedFactoryIndex = factoryIdx
            recalculateIncomePerSec()
            return true
        }
    }

    fun unlockedFactoryCount(): Int {
        synchronized(lock) {
            return factories.count { it.unlocked }
        }
    }

    /**
     * Offline earnings are enabled per factory when its Manager is hired.
     */
    fun applyOfflineEarnings(elapsedSecondsRaw: Long): OfflineReport? {
        synchronized(lock) {
            val clamped = elapsedSecondsRaw.coerceIn(0L, MAX_OFFLINE_SECONDS)
            if (clamped < 30L) return null

            val baseGlobal = communityFireMultiplier()
            var totalOffline = BigNumber.ZERO

            for (i in factories.indices) {
                val fac = factories[i]
                if (!fac.unlocked) continue
                val logState = logistics.factoryStates[i]
                if (!logState.manager.hired) continue

                val rate = fac.baseRatePerSecond() * baseGlobal * logState.manager.speedMultiplier()
                val produced = rate * clamped.toDouble() * 0.70
                totalOffline += BigNumber.fromDouble(produced * fac.def.baseCrateValue)

                val nextFac = factories.getOrNull(i + 1)
                if (nextFac != null && !nextFac.unlocked) {
                    fac.deliveredToNext += produced * 0.5
                }
            }

            if (totalOffline.isZero()) return null
            addEarningsInternal(totalOffline)
            return OfflineReport(clamped, totalOffline)
        }
    }

    internal fun addEarningsInternal(amount: BigNumber) {
        if (amount.isZero()) return
        currency += amount
        totalEarned += amount
    }

    internal fun recalculateIncomePerSec() {
        val globalMult = globalSpeedMultiplier()
        var total = BigNumber.ZERO
        for (i in factories.indices) {
            val fac = factories[i]
            if (!fac.unlocked) continue
            val nextUnlocked = factories.getOrNull(i + 1)?.unlocked == true
            val mgrMult = logistics.managerSpeedMultiplier(i)
            val rate = fac.baseRatePerSecond() * globalMult * mgrMult
            if (!nextUnlocked) {
                total += BigNumber.fromDouble(rate * fac.def.baseCrateValue)
            }
        }
        cachedIncomePerSec = total
    }

    fun pickFactoryAt(wx: Float, wz: Float): Int {
        synchronized(lock) {
            for (i in factories.indices) {
                val f = factories[i]
                if (wx >= f.worldX - FACTORY_HALF_WIDTH - 0.8f &&
                    wx <= f.worldX + FACTORY_HALF_WIDTH + 0.8f &&
                    wz >= f.worldZ - 2.3f &&
                    wz <= f.worldZ + 2.3f
                ) {
                    return i
                }
            }
            return -1
        }
    }
}
