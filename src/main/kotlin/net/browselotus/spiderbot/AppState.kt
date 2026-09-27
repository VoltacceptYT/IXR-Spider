package net.browselotus.spiderbot

import net.browselotus.spiderbot.kinematic_chain_visualizer.KinematicChainVisualizer
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.BodyPlan
import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.components.Cloak
import net.browselotus.spiderbot.spider.components.Mountable
import net.browselotus.spiderbot.spider.components.Owner
import net.browselotus.spiderbot.spider.components.PointDetector
import net.browselotus.spiderbot.spider.components.SoundsAndParticles
import net.browselotus.spiderbot.spider.components.SpiderAttacker
import net.browselotus.spiderbot.spider.components.TridentHitDetector
import net.browselotus.spiderbot.spider.presets.hexBot
import net.browselotus.spiderbot.spider.components.rendering.SpiderRenderer
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import org.bukkit.Location
import org.bukkit.entity.Player

object AppState {
    var miscOptions = MiscellaneousOptions()
    var renderDebugVisuals = false

    val ecs = ECS()

    var target: Location? = null

    fun createSpider(location: Location, options: SpiderOptions, ownerUUID: java.util.UUID? = null): ECSEntity {
        location.y += options.walkGait.stationary.bodyHeight
        val entity = ecs.spawn(
            SpiderBody.fromLocation(location),
            options,
            TridentHitDetector(),
            Cloak(),
            SoundsAndParticles(),
            Mountable(),
            PointDetector(),
            SpiderAttacker(),
            SpiderRenderer(),
        )

        if (ownerUUID != null) entity.addComponent(Owner(ownerUUID))

        return entity
    }

    fun findSpiderByUUID(uuid: java.util.UUID): ECSEntity? {
        return ecs.query<ECSEntity, SpiderBody>().find { it.second.uuid == uuid }?.first
    }

    fun findSpiderByOwner(playerUUID: java.util.UUID): ECSEntity? {
        return ecs.query<ECSEntity, Owner>().find { it.second.playerUUID == playerUUID }?.first
    }

    fun findNearestSpider(player: Player): ECSEntity? {
        return findNearestSpider(player.location)
    }

    fun findNearestSpider(location: Location): ECSEntity? {
        return ecs.query<ECSEntity, SpiderBody>()
            .filter { it.second.world == location.world }
            .minByOrNull { it.second.position.distanceSquared(location.toVector()) }
            ?.first
    }

    fun createChainVisualizer(location: Location, bodyPlan: BodyPlan = hexBot(4, 1.0).bodyPlan): ECSEntity {
        val segmentPlans = bodyPlan.legs.lastOrNull()?.segments ?: throw Error("Cannot find segment plans")

        return ecs.spawn(KinematicChainVisualizer.create(
            segmentPlans = segmentPlans,
            root = location.toVector(),
            world = location.world ?: throw Error("location.world is null"),
            straightenRotation = 0f,
        ).apply {
            detailed = renderDebugVisuals
        })
    }
}

class MiscellaneousOptions {
    var showLaser = true
}