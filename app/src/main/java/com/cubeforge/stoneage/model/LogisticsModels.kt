package com.cubeforge.stoneage.model

/**
 * Era-agnostic logistics data models shared across every Era and Section.
 * Zero hardcoded era, section, or product names.
 */

enum class WorkerPhase {
    IDLE,
    TO_VESSEL,
    TO_WAREHOUSE,
    TO_NEXT_FEEDER
}

/**
 * Output collection vessel sitting at the end of each factory's belt.
 */
class VesselState(
    var stock: Double = 0.0,
    var capacity: Double = BASE_CAPACITY,
    var level: Int = 1
) {
    companion object {
        const val BASE_CAPACITY = 20.0
        const val CAPACITY_STEP = 15.0
    }

    val isFull: Boolean
        get() = stock >= capacity - 1e-6

    val fillRatio: Float
        get() = if (capacity > 0.0) (stock / capacity).toFloat().coerceIn(0f, 1f) else 0f

    fun nextCapacity(): Double = BASE_CAPACITY + level * CAPACITY_STEP

    fun upgradeCost(factoryBaseCost: Double): BigNumber {
        val base = (factoryBaseCost * 1.35).coerceAtLeast(15.0)
        return BigNumber.fromDouble(base) * BigNumber.pow(1.48, level - 1)
    }
}

/**
 * Central Era/Tribe Warehouse storing outputs keyed dynamically by FactoryDef.output.
 */
class WarehouseState(
    val inventory: MutableMap<String, Double> = LinkedHashMap(),
    var totalCapacity: Double = BASE_CAPACITY,
    var level: Int = 1,
    var worldX: Float = 4.4f,
    var worldZ: Float = -30.2f
) {
    companion object {
        const val BASE_CAPACITY = 100.0
        const val CAPACITY_STEP = 75.0
    }

    @Volatile
    private var cachedTotalStored: Double = 0.0

    fun getStock(outputId: String): Double = inventory[outputId] ?: 0.0

    fun setStock(outputId: String, newValue: Double) {
        if (newValue <= 1e-4) {
            inventory.remove(outputId)
        } else {
            inventory[outputId] = newValue
        }
        recomputeTotalStored()
    }

    fun recomputeTotalStored() {
        var sum = 0.0
        for (v in inventory.values) {
            sum += v
        }
        cachedTotalStored = sum
    }

    fun totalStored(): Double = cachedTotalStored

    fun remainingCapacity(): Double = (totalCapacity - totalStored()).coerceAtLeast(0.0)

    fun fillRatio(): Float =
        if (totalCapacity > 0.0) (totalStored() / totalCapacity).toFloat().coerceIn(0f, 1f) else 0f

    fun nextCapacity(): Double = BASE_CAPACITY + level * CAPACITY_STEP

    fun upgradeCost(): BigNumber {
        return BigNumber.fromDouble(120.0) * BigNumber.pow(1.65, level - 1)
    }
}

/**
 * Pre-allocated 3D transport worker agent moving continuously between vessel, warehouse, and next feeder.
 */
class WorkerAgent {
    var active: Boolean = false
    var factoryIndex: Int = 0
    var phase: WorkerPhase = WorkerPhase.TO_VESSEL
    var worldX: Float = 0f
    var worldZ: Float = 0f
    var facingAngleDeg: Float = 0f
    var walkPhase: Float = 0f
    var carrying: Boolean = false
    var carryAmount: Double = 0.0
    var carryOutputId: String = ""
    var carryColorR: Float = 0.8f
    var carryColorG: Float = 0.5f
    var carryColorB: Float = 0.2f
}

/**
 * Per-factory worker fleet configuration and pre-allocated agent slots (1..3 workers).
 */
class WorkerRouteState {
    companion object {
        const val MAX_WORKERS = 3
        const val MAX_CARRY_CAPACITY = 5
        const val HEAVY_CARRIER_THRESHOLD = 3
    }

    var workerCount: Int = 0
    var speedLevel: Int = 1
    var capacityLevel: Int = 1
    val agents: Array<WorkerAgent> = Array(MAX_WORKERS) { WorkerAgent() }

    val carryCapacity: Int
        get() = capacityLevel.coerceIn(1, MAX_CARRY_CAPACITY)

    val isHeavyCarrier: Boolean
        get() = carryCapacity >= HEAVY_CARRIER_THRESHOLD

    fun moveSpeedUnitsPerSec(managerMult: Double): Double {
        return (3.6 + (speedLevel - 1) * 0.75) * managerMult
    }

    fun hireWorkerCost(factoryBaseCost: Double): BigNumber {
        val base = (factoryBaseCost * 2.5).coerceAtLeast(25.0)
        return BigNumber.fromDouble(base) * BigNumber.pow(2.4, workerCount)
    }

    fun speedUpgradeCost(factoryBaseCost: Double): BigNumber {
        val base = (factoryBaseCost * 1.8).coerceAtLeast(20.0)
        return BigNumber.fromDouble(base) * BigNumber.pow(1.52, speedLevel - 1)
    }

    fun capacityUpgradeCost(factoryBaseCost: Double): BigNumber {
        val base = (factoryBaseCost * 3.2).coerceAtLeast(35.0)
        return BigNumber.fromDouble(base) * BigNumber.pow(2.15, capacityLevel - 1)
    }
}

/**
 * Per-factory overseer manager granting +25% speed to machines & workers plus full automation & offline earnings.
 */
class ManagerState(
    var hired: Boolean = false
) {
    companion object {
        const val SPEED_BONUS_MULT = 1.25
    }

    fun speedMultiplier(): Double = if (hired) SPEED_BONUS_MULT else 1.0

    fun hireCost(factoryBaseCost: Double): BigNumber {
        val base = (factoryBaseCost * 14.0).coerceAtLeast(150.0)
        return BigNumber.fromDouble(base)
    }
}

/**
 * Object-pooled parabolic flying crate animation (32 slots, zero allocations in onDrawFrame).
 */
class FlyingCrateAnim {
    var active: Boolean = false
    var startX: Float = 0f
    var startY: Float = 0.75f
    var startZ: Float = 0f
    var endX: Float = 0f
    var endY: Float = 0.75f
    var endZ: Float = 0f
    var t: Float = 0f
    var duration: Float = 0.52f
    var peakHeight: Float = 2.6f
    var colorR: Float = 0.85f
    var colorG: Float = 0.55f
    var colorB: Float = 0.22f
}

/**
 * Bundled logistics state & pre-computed 3D anchor coordinates for a single factory.
 */
class FactoryLogisticsState(
    val factoryIndex: Int,
    val vessel: VesselState = VesselState(),
    var inputStock: Double = 0.0,
    val workerRoute: WorkerRouteState = WorkerRouteState(),
    val manager: ManagerState = ManagerState(),
    var vesselX: Float = 0f,
    var vesselZ: Float = 0f,
    var feederX: Float = 0f,
    var feederZ: Float = 0f,
    var managerX: Float = 0f,
    var managerZ: Float = 0f,
    var colorR: Float = 0.8f,
    var colorG: Float = 0.5f,
    var colorB: Float = 0.2f
)

/**
 * Result of manual vessel collection for immediate UI & floating popup feedback.
 */
data class CollectResult(
    val collectedUnits: Double,
    val earnedCoins: BigNumber
)
