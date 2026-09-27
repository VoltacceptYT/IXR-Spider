package com.heledron.spideranimation.spider

import com.heledron.spideranimation.AppState
import com.heledron.spideranimation.spider.presets.hexBot
import com.heledron.spideranimation.utilities.events.onPlayerJoin
import com.heledron.spideranimation.utilities.events.onPlayerQuit
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * Gives every player their own robo-spider: one is spawned next to them when
 * they join, it follows them around, and it despawns when they leave.
 */
fun setupSpiderOwnership() {
    fun spawnSpiderFor(player: Player) {
        // Don't spawn a second spider if this player already has one
        // (e.g. this fires on plugin reload while they're still online).
        if (AppState.findSpiderByOwner(player.uniqueId) != null) return

        val options = hexBot(4, 1.0)
        AppState.createSpider(player.location, options, player.uniqueId)
    }

    fun despawnSpiderFor(player: Player) {
        AppState.findSpiderByOwner(player.uniqueId)?.remove()
    }

    // Cover the case where the plugin is enabled/reloaded while players are
    // already online, so nobody is left without a spider.
    for (player in Bukkit.getOnlinePlayers()) spawnSpiderFor(player)

    onPlayerJoin { player -> spawnSpiderFor(player) }
    onPlayerQuit { player -> despawnSpiderFor(player) }
}
