package net.browselotus.spiderbot

import net.browselotus.spiderbot.AppState.ecs
import net.browselotus.spiderbot.kinematic_chain_visualizer.KinematicChainVisualizer
import net.browselotus.spiderbot.kinematic_chain_visualizer.setupChainVisualizer
import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.components.rendering.SpiderRenderer
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.gui.setupSpiderCustomizationGUI
import net.browselotus.spiderbot.spider.saveSpiderOptions
import net.browselotus.spiderbot.spider.setupSpider
import net.browselotus.spiderbot.spider.setupSpiderOwnership
import net.browselotus.spiderbot.laser.setupLaserPointer
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.events.onSpawnEntity
import net.browselotus.spiderbot.utilities.events.onTick
import net.browselotus.spiderbot.utilities.setupCoreUtils
import net.browselotus.spiderbot.utilities.shutdownCoreUtils
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

@Suppress("unused")
class SpiderAnimationPlugin : JavaPlugin() {
    fun writeAndSaveConfig() {
        // Persist every online player's current spider customization to disk right away,
        // rather than waiting for them to leave or the server to shut down.
        for (player in Bukkit.getOnlinePlayers()) {
            val entity = AppState.findSpiderByOwner(player.uniqueId) ?: continue
            val options = entity.query<SpiderOptions>() ?: continue
            saveSpiderOptions(player.uniqueId, options)
        }
    }

    override fun onDisable() {
        logger.info("Disabling Spider Animation plugin")
        shutdownCoreUtils()
    }

    override fun onEnable() {
        logger.info("Enabling Spider Animation plugin")

        setupCoreUtils()

        setupCommands(this)
        setupItems()
        setupSpider(ecs)
        setupChainVisualizer(ecs)
        setupLaserPointer(ecs)
        setupSpiderOwnership()
        setupSpiderCustomizationGUI()

        ecs.start()
        onTick {
            // sync AppState properties
            ecs.query<ECSEntity, SpiderBody>().forEach { (entity, spider) ->
                entity.query<SpiderRenderer>()?.renderDebugVisuals = AppState.renderDebugVisuals
            }

            ecs.update()
            ecs.render()
        }


        onSpawnEntity { entity ->
            // Use this command to spawn a chain visualizer
            // /summon minecraft:area_effect_cloud ~ ~ ~ {Tags:["spider.chain_visualizer"]}
            if (!entity.scoreboardTags.contains("spider.chain_visualizer")) return@onSpawnEntity

            val oldVisualizer = ecs.query<ECSEntity, KinematicChainVisualizer>().firstOrNull()?.first
            if (oldVisualizer == null) {
                AppState.createChainVisualizer(entity.location)
            } else {
                oldVisualizer.remove()
            }

            entity.remove()
        }
    }
}