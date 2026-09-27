package com.heledron.spideranimation.spider

import com.heledron.spideranimation.spider.components.body.setupSpiderBody
import com.heledron.spideranimation.spider.components.*
import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.components.rendering.setupRenderer
import com.heledron.spideranimation.utilities.ecs.ECS
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import org.bukkit.Bukkit

// How close an owned spider tries to stay to its owner, in blocks.
private const val FOLLOW_OWNER_DISTANCE = 3.0

fun setupSpider(app: ECS) {
    setupSpiderBody(app)
    setupBehaviours(app)

    // Default behaviour: owned spiders follow their owner around; spiders
    // without an owner (e.g. spawned via the spider item) just stay still.
    // This can still be overridden later in the tick (e.g. by mounting or
    // using the laser pointer/come-here item).
    app.onTick {
        for ((entity, _) in app.query<ECSEntity, SpiderBody>()) {
            val ownerUUID = entity.query<Owner>()?.playerUUID
            val ownerPlayer = ownerUUID?.let { Bukkit.getPlayer(it) }

            if (ownerPlayer != null) {
                entity.replaceComponent<SpiderBehaviour>(
                    TargetBehaviour(ownerPlayer.location.toVector(), FOLLOW_OWNER_DISTANCE)
                )
            } else {
                entity.replaceComponent<SpiderBehaviour>(StayStillBehaviour())
            }
        }
    }

    // Attacking overrides the default follow/stay-still behaviour above whenever the spider
    // has a target, but is itself overridden by rider control (set up by setupMountable below).
    setupSpiderAttack(app)

    setupCloak(app)
    setupMountable(app)
    setupPointDetector(app)
    setupSoundAndParticles(app)
    setupTridentHitDetector(app)
    setupSpiderProjectiles(app)
    setupRenderer(app)
}