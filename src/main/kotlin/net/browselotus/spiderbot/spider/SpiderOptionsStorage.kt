package net.browselotus.spiderbot.spider

import net.browselotus.spiderbot.spider.configuration.CreatureType
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.presets.DroidModels
import net.browselotus.spiderbot.utilities.Serializer
import net.browselotus.spiderbot.utilities.currentPlugin
import java.io.File
import java.util.UUID

/**
 * Persists each player's [SpiderOptions] (scale, gaits, colors, cloak, debug, sound, ...) to its
 * own JSON file under the plugin's data folder, so customization survives the player leaving and
 * rejoining, and survives server restarts.
 */
private fun spiderOptionsFile(playerUUID: UUID): File {
    val directory = File(currentPlugin.dataFolder, "spiders")
    if (!directory.exists()) directory.mkdirs()
    return File(directory, "$playerUUID.json")
}

fun loadSpiderOptions(playerUUID: UUID): SpiderOptions? {
    val file = spiderOptionsFile(playerUUID)
    if (!file.exists()) return null

    return try {
        val options = Serializer.gson.fromJson(file.readText(), SpiderOptions::class.java) ?: return null
        options.bodyPlan.restoreAfterLoad()

        // saved options carry the models they were saved with, so give droids their current, jointed look
        if (options.creature == CreatureType.DROID) DroidModels.rebuildVisuals(options)

        options
    } catch (e: Exception) {
        currentPlugin.logger.warning("Could not load saved spider options for $playerUUID, using defaults: $e")
        null
    }
}

fun saveSpiderOptions(playerUUID: UUID, options: SpiderOptions) {
    val file = spiderOptionsFile(playerUUID)

    try {
        file.writeText(Serializer.gson.toJson(options))
    } catch (e: Exception) {
        currentPlugin.logger.severe("Could not save spider options for $playerUUID: $e")
    }
}
