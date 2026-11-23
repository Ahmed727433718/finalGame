package com.cubeforge.stoneage.model

import kotlin.math.pow

enum class MachineArchetype(val labelAr: String) {
    CUTTER("قاطع"),
    CRUSHER("طارق/كاسر"),
    WASHER("غسّال/إخماد"),
    DRYER("مجفّف"),
    SORTER("فارز"),
    STACKER("مُغذّي/مكدّس"),
    FURNACE("موقد/فرن"),
    MILL("مطحنة"),
    MIXER("خلّاط"),
    MOLDER("قالب/مشكّل"),
    BINDER("رابط/خيّاط"),
    TESTER("مختبر/فاحص");

    companion object {
        fun fromString(raw: String): MachineArchetype {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CUTTER
        }
    }
}

enum class MachinePlacement {
    FEEDER,
    OVERHEAD,
    SIDE;

    companion object {
        fun fromString(raw: String): MachinePlacement {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OVERHEAD
        }
    }
}

data class Era(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val prestigeLevel: Int,
    val sections: List<Section>
)

data class Section(
    val id: String,
    val index: Int,
    val nameAr: String,
    val nameEn: String,
    val goalAr: String,
    val factories: List<FactoryDef>
)

data class FactoryDef(
    val id: String,
    val index: Int,
    val nameAr: String,
    val nameEn: String,
    val input: String?,
    val output: String,
    val colorHex: String,
    val crateModelId: String,
    val baseCrateValue: Double,
    val unlockCratesRequired: Int,
    val baseUpgradeCost: Double,
    val machines: List<MachineDef>
)

data class MachineDef(
    val id: String,
    val nameAr: String,
    val archetype: MachineArchetype,
    val modelId: String,
    val placement: MachinePlacement,
    val localX: Float,
    val localZ: Float,
    val baseSpeed: Double
)

data class MachineState(
    val def: MachineDef,
    var level: Int = 1,
    val baseUpgradeCost: Double
) {
    fun effectiveSpeed(): Double {
        val milestoneMult = 2.0.pow((level / 25).toDouble())
        return def.baseSpeed * (1.0 + (level - 1) * 0.18) * milestoneMult
    }

    fun upgradeCost(): BigNumber {
        return BigNumber.fromDouble(baseUpgradeCost) * BigNumber.pow(1.15, level - 1)
    }

    fun nextMilestoneLevel(): Int = ((level / 25) + 1) * 25
}

data class FactoryState(
    val def: FactoryDef,
    val machines: List<MachineState>,
    var unlocked: Boolean,
    var storedCrates: Double = 0.0,
    var deliveredToNext: Double = 0.0,
    var totalProduced: Double = 0.0,
    var productionProgress: Double = 0.0,
    var worldX: Float = 0f,
    var worldZ: Float = 0f,
    var facingRight: Boolean = true
) {
    fun bottleneckMachineIndex(): Int {
        var minIdx = 0
        var minSpeed = Double.MAX_VALUE
        for (i in machines.indices) {
            val spd = machines[i].effectiveSpeed()
            if (spd < minSpeed) {
                minSpeed = spd
                minIdx = i
            }
        }
        return minIdx
    }

    fun baseRatePerSecond(): Double {
        if (machines.isEmpty()) return 1.0
        return machines[bottleneckMachineIndex()].effectiveSpeed()
    }
}

data class VisualCrate(
    var active: Boolean = false,
    var fromFactoryIndex: Int = 0,
    var toFactoryIndex: Int = 1,
    var t: Float = 0f,
    var colorR: Float = 0.8f,
    var colorG: Float = 0.5f,
    var colorB: Float = 0.2f
)

data class GoldenAnomaly(
    var active: Boolean = false,
    var factoryIndex: Int = 0,
    var timeRemaining: Float = 0f,
    var bobPhase: Float = 0f
)
