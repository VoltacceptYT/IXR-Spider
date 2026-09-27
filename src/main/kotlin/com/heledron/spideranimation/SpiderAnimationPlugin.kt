package com.heledron.spideranimation

import com.heledron.spideranimation.AppState.ecs
import com.heledron.spideranimation.kinematic_chain_visualizer.KinematicChainVisualizer
import com.heledron.spideranimation.kinematic_chain_visualizer.setupChainVisualizer
import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.components.rendering.SpiderRenderer
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.spider.gui.setupSpiderCustomizationGUI
import com.heledron.spideranimation.spider.saveSpiderOptions
import com.heledron.spideranimation.spider.setupSpider
import com.heledron.spideranimation.spider.setupSpiderOwnership
import com.heledron.spideranimation.laser.setupLaserPointer
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import com.heledron.spideranimation.utilities.events.onSpawnEntity
import com.heledron.spideranimation.utilities.events.onTick
import com.heledron.spideranimation.utilities.setupCoreUtils
import com.heledron.spideranimation.utilities.shutdownCoreUtils
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