package com.heledron.spideranimation.spider

import com.heledron.spideranimation.AppState
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.spider.presets.hexBot
import com.heledron.spideranimation.utilities.currentTick
import com.heledron.spideranimation.utilities.events.onPlayerJoin
import com.heledron.spideranimation.utilities.events.onPlayerQuit
import com.heledron.spideranimation.utilities.events.onTick
import com.heledron.spideranimation.utilities.onPluginShutdown
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/** How often (in ticks) every online player's spider options are saved as a safety net, in case the server never shuts down cleanly. */
private const val AUTOSAVE_INTERVAL_TICKS = 20 * 60 * 5 // 5 minutes

/**
 * Gives every player their own robo-spider: one is spawned next to them when
 * they join (restoring their saved customization if they have any), it
 * follows them around, and it's saved and despawned when they leave.
 */
fun setupSpiderOwnership() {
    fun spawnSpiderFor(player: Player) {
        // Don't spawn a second spider if this player already has one
        // (e.g. this fires on plugin reload while they're still online).
        if (AppState.findSpiderByOwner(player.uniqueId) != null) return

        val options = loadSpiderOptions(player.uniqueId) ?: hexBot(4, 1.0)
        AppState.createSpider(player.location, options, player.uniqueId)
    }

    fun saveSpiderFor(player: Player) {
        val entity = AppState.findSpiderByOwner(player.uniqueId) ?: return
        val options = entity.query<SpiderOptions>() ?: return
        saveSpiderOptions(player.uniqueId, options)
    }

    fun despawnSpiderFor(player: Player) {
        saveSpiderFor(player)
        AppState.findSpiderByOwner(player.uniqueId)?.remove()
    }

    // Cover the case where the plugin is enabled/reloaded while players are
    // already online, so nobody is left without a spider.
    for (player in Bukkit.getOnlinePlayers()) spawnSpiderFor(player)

    onPlayerJoin { player -> spawnSpiderFor(player) }
    onPlayerQuit { player -> despawnSpiderFor(player) }

    // Safety net: periodically save every online player's spider, in case the
    // server stops without going through a clean shutdown or player quit.
    onTick {
        if (currentTick % AUTOSAVE_INTERVAL_TICKS == 0) {
            for (player in Bukkit.getOnlinePlayers()) saveSpiderFor(player)
        }
    }

    // Also save on a clean plugin shutdown (e.g. /stop or /reload), before
    // the spiders are torn down.
    onPluginShutdown {
        for (player in Bukkit.getOnlinePlayers()) saveSpiderFor(player)
    }
}
