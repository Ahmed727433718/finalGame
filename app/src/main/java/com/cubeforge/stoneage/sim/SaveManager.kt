package com.cubeforge.stoneage.sim

import android.content.Context
import com.cubeforge.stoneage.model.BigNumber
import com.cubeforge.stoneage.model.VesselState
import com.cubeforge.stoneage.model.WarehouseState
import com.cubeforge.stoneage.model.WorkerRouteState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Versioned atomic JSON persistence (saveVersion = 2) with full backward compatibility
 * for legacy saveVersion = 1 files without wiping player progress.
 */
object SaveManager {

    private const val SAVE_VERSION = 2
    private const val SAVE_FILE = "stone_age_save_v1.json"
    private const val TEMP_FILE = "stone_age_save_v1.tmp"

    fun save(context: Context, engine: GameEngine) {
        val json = synchronized(engine.lock) {
            JSONObject().apply {
                put("saveVersion", SAVE_VERSION)
                put("timestampMs", System.currentTimeMillis())
                put("currencyM", engine.currency.mantissa)
                put("currencyE", engine.currency.exponent)
                put("totalEarnedM", engine.totalEarned.mantissa)
                put("totalEarnedE", engine.totalEarned.exponent)
                put("selectedFactoryIndex", engine.selectedFactoryIndex)

                // Central Warehouse state
                val wh = engine.logistics.warehouse
                val whObj = JSONObject().apply {
                    put("level", wh.level)
                    put("totalCapacity", wh.totalCapacity)
                    val invObj = JSONObject()
                    for ((k, v) in wh.inventory) {
                        if (v > 1e-4) invObj.put(k, v)
                    }
                    put("inventory", invObj)
                }
                put("warehouse", whObj)

                val facArr = JSONArray()
                for (idx in engine.factories.indices) {
                    val fac = engine.factories[idx]
                    val log = engine.logistics.factoryStates[idx]
                    val fObj = JSONObject().apply {
                        put("id", fac.def.id)
                        put("unlocked", fac.unlocked)
                        put("storedCrates", log.vessel.stock)
                        put("deliveredToNext", fac.deliveredToNext)
                        put("totalProduced", fac.totalProduced)
                        val levelsArr = JSONArray()
                        for (m in fac.machines) {
                            levelsArr.put(m.level)
                        }
                        put("machineLevels", levelsArr)

                        // v2 Logistics fields
                        put("vesselStock", log.vessel.stock)
                        put("vesselCapacity", log.vessel.capacity)
                        put("vesselLevel", log.vessel.level)
                        put("inputStock", log.inputStock)
                        put("workerCount", log.workerRoute.workerCount)
                        put("workerSpeedLevel", log.workerRoute.speedLevel)
                        put("workerCapacityLevel", log.workerRoute.capacityLevel)
                        put("managerHired", log.manager.hired)
                    }
                    facArr.put(fObj)
                }
                put("factories", facArr)
            }
        }

        try {
            val tmp = File(context.filesDir, TEMP_FILE)
            val dst = File(context.filesDir, SAVE_FILE)
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
        } catch (_: Exception) {
            // Safe swallow to avoid crashing on low storage
        }
    }

    /**
     * Loads saved state (supports saveVersion 1 and 2 seamlessly) and returns elapsed offline seconds.
     */
    fun load(context: Context, engine: GameEngine): Long {
        val file = File(context.filesDir, SAVE_FILE)
        if (!file.exists()) return 0L

        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val version = root.optInt("saveVersion", 0)
            if (version != 1 && version != SAVE_VERSION) return 0L

            val savedTimeMs = root.optLong("timestampMs", System.currentTimeMillis())
            val elapsedSec = ((System.currentTimeMillis() - savedTimeMs) / 1000L).coerceAtLeast(0L)

            synchronized(engine.lock) {
                engine.currency = BigNumber.normalize(
                    root.optDouble("currencyM", 5.0),
                    root.optLong("currencyE", 1L)
                )
                engine.totalEarned = BigNumber.normalize(
                    root.optDouble("totalEarnedM", 5.0),
                    root.optLong("totalEarnedE", 1L)
                )
                engine.selectedFactoryIndex = root.optInt("selectedFactoryIndex", 0)
                    .coerceIn(0, engine.factories.size - 1)

                // Load v2 warehouse if present
                val whObj = root.optJSONObject("warehouse")
                if (whObj != null) {
                    val wh = engine.logistics.warehouse
                    wh.level = whObj.optInt("level", 1).coerceAtLeast(1)
                    wh.totalCapacity = whObj.optDouble("totalCapacity", WarehouseState.BASE_CAPACITY)
                        .coerceAtLeast(WarehouseState.BASE_CAPACITY)
                    wh.inventory.clear()
                    val invObj = whObj.optJSONObject("inventory")
                    if (invObj != null) {
                        val keys = invObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = invObj.optDouble(k, 0.0)
                            if (v > 1e-4) {
                                wh.inventory[k] = v
                            }
                        }
                    }
                    wh.recomputeTotalStored()
                }

                val facArr = root.optJSONArray("factories")
                if (facArr != null) {
                    for (i in 0 until facArr.length()) {
                        val fObj = facArr.getJSONObject(i)
                        val id = fObj.optString("id", "")
                        val matchIdx = engine.factories.indexOfFirst { it.def.id == id }
                        if (matchIdx < 0) continue
                        val fac = engine.factories[matchIdx]
                        val log = engine.logistics.factoryStates[matchIdx]

                        fac.unlocked = fObj.optBoolean("unlocked", fac.def.index == 1)
                        val legacyStored = fObj.optDouble("storedCrates", 0.0).coerceAtLeast(0.0)
                        fac.storedCrates = legacyStored
                        fac.deliveredToNext = fObj.optDouble("deliveredToNext", 0.0).coerceAtLeast(0.0)
                        fac.totalProduced = fObj.optDouble("totalProduced", 0.0).coerceAtLeast(0.0)

                        val levelsArr = fObj.optJSONArray("machineLevels")
                        if (levelsArr != null) {
                            for (m in 0 until minOf(levelsArr.length(), fac.machines.size)) {
                                fac.machines[m].level = levelsArr.optInt(m, 1).coerceAtLeast(1)
                            }
                        }

                        if (version >= 2) {
                            log.vessel.level = fObj.optInt("vesselLevel", 1).coerceAtLeast(1)
                            log.vessel.capacity = fObj.optDouble("vesselCapacity", VesselState.BASE_CAPACITY)
                                .coerceAtLeast(VesselState.BASE_CAPACITY)
                            log.vessel.stock = fObj.optDouble("vesselStock", legacyStored)
                                .coerceIn(0.0, log.vessel.capacity)
                            log.inputStock = fObj.optDouble("inputStock", 0.0)
                                .coerceIn(0.0, LogisticsEngine.MAX_INPUT_STOCK)
                            log.workerRoute.workerCount = fObj.optInt("workerCount", 0)
                                .coerceIn(0, WorkerRouteState.MAX_WORKERS)
                            log.workerRoute.speedLevel = fObj.optInt("workerSpeedLevel", 1).coerceAtLeast(1)
                            log.workerRoute.capacityLevel = fObj.optInt("workerCapacityLevel", 1)
                                .coerceIn(1, WorkerRouteState.MAX_CARRY_CAPACITY)
                            log.manager.hired = fObj.optBoolean("managerHired", false)
                        } else {
                            // Seamless v1 -> v2 migration
                            log.vessel.level = 1
                            log.vessel.capacity = VesselState.BASE_CAPACITY
                            log.vessel.stock = min(legacyStored, log.vessel.capacity)
                            log.inputStock = if (matchIdx > 0 && fac.unlocked) 5.0 else 0.0
                        }
                    }
                }
                engine.recalculateIncomePerSec()
            }
            elapsedSec
        } catch (_: Exception) {
            0L
        }
    }
}
