package com.cubeforge.stoneage.model

import android.content.Context
import org.json.JSONObject

/**
 * Loads Era / Section / Factory / Machine definitions strictly from JSON in assets.
 * Supports both v1 and v2 schema fields (modelId, placement, localX, localZ, crateModelId).
 */
object EraRepository {

    fun loadEraFromAssets(context: Context, assetPath: String = "data/stone_age_sec1.json"): Era {
        val jsonText = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(jsonText)
        val eraObj = root.getJSONObject("era")
        val sectionsJson = eraObj.getJSONArray("sections")

        val sections = ArrayList<Section>(sectionsJson.length())
        for (s in 0 until sectionsJson.length()) {
            val secObj = sectionsJson.getJSONObject(s)
            val factoriesJson = secObj.getJSONArray("factories")
            val factories = ArrayList<FactoryDef>(factoriesJson.length())

            for (f in 0 until factoriesJson.length()) {
                val fObj = factoriesJson.getJSONObject(f)
                val machinesJson = fObj.getJSONArray("machines")
                val machines = ArrayList<MachineDef>(machinesJson.length())
                val mCount = machinesJson.length()

                for (m in 0 until mCount) {
                    val mObj = machinesJson.getJSONObject(m)
                    val archetype = MachineArchetype.fromString(mObj.getString("archetype"))
                    val defaultLocalX = if (mCount == 4) {
                        when (m) {
                            0 -> -3.0f
                            1 -> -0.4f
                            2 -> 1.8f
                            else -> 3.9f
                        }
                    } else {
                        -4.0f + m * 2.0f
                    }
                    val placement = MachinePlacement.fromString(
                        mObj.optString("placement", if (m == 0) "FEEDER" else "OVERHEAD")
                    )
                    val defaultLocalZ = if (placement == MachinePlacement.SIDE) -1.24f else 0f
                    machines.add(
                        MachineDef(
                            id = mObj.getString("id"),
                            nameAr = mObj.getString("nameAr"),
                            archetype = archetype,
                            modelId = mObj.optString("modelId", archetype.name.lowercase()),
                            placement = placement,
                            localX = mObj.optDouble("localX", defaultLocalX.toDouble()).toFloat(),
                            localZ = mObj.optDouble("localZ", defaultLocalZ.toDouble()).toFloat(),
                            baseSpeed = mObj.getDouble("baseSpeed")
                        )
                    )
                }

                val inputVal = if (fObj.isNull("input")) null else fObj.getString("input")
                factories.add(
                    FactoryDef(
                        id = fObj.getString("id"),
                        index = fObj.getInt("index"),
                        nameAr = fObj.getString("nameAr"),
                        nameEn = fObj.getString("nameEn"),
                        input = inputVal,
                        output = fObj.getString("output"),
                        colorHex = fObj.getString("colorHex"),
                        crateModelId = fObj.optString("crateModelId", "collection_crate"),
                        baseCrateValue = fObj.getDouble("baseCrateValue"),
                        unlockCratesRequired = fObj.getInt("unlockCratesRequired"),
                        baseUpgradeCost = fObj.getDouble("baseUpgradeCost"),
                        machines = machines
                    )
                )
            }

            sections.add(
                Section(
                    id = secObj.getString("id"),
                    index = secObj.getInt("index"),
                    nameAr = secObj.getString("nameAr"),
                    nameEn = secObj.getString("nameEn"),
                    goalAr = secObj.getString("goalAr"),
                    factories = factories
                )
            )
        }

        return Era(
            id = eraObj.getString("id"),
            nameAr = eraObj.getString("nameAr"),
            nameEn = eraObj.getString("nameEn"),
            prestigeLevel = eraObj.getInt("prestigeLevel"),
            sections = sections
        )
    }
}
