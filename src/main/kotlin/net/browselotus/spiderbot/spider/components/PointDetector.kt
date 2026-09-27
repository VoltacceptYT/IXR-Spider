package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.Leg
import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.lookingAtPoint
import net.browselotus.spiderbot.utilities.overloads.direction
import net.browselotus.spiderbot.utilities.overloads.eyePosition
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class PointDetector : Component {
    var checkPlayers = setOf<Player>()
    val selectedLeg = mutableMapOf<Player, Leg>()
}

fun setupPointDetector(app: ECS) {
    fun rayCastLeg(spider: SpiderBody, options: SpiderOptions, world: World, rayOrigin: Vector, rayDirection: Vector): Leg? {
        if (spider.world != world) return null

        val tolerance = options.walkGait.stationary.bodyHeight * .15
        for (leg in spider.legs) {
            val lookingAt = lookingAtPoint(rayOrigin, rayDirection, leg.endEffector, tolerance)
            if (lookingAt) return leg
        }
        return null
    }

    app.onTick {
        for ((spider, pointDetector, options) in app.query<SpiderBody, PointDetector, SpiderOptions>()) {
            pointDetector.selectedLeg.clear()

            for (player in pointDetector.checkPlayers) {
                val leg = rayCastLeg(spider, options, player.world, player.eyePosition, player.direction) ?: continue
                pointDetector.selectedLeg[player] = leg
            }
        }
    }
}