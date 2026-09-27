package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.maths.UP_VECTOR
import net.browselotus.spiderbot.utilities.overloads.position
import org.bukkit.entity.Trident

class TridentHitEvent(val entity: ECSEntity, val spider: SpiderBody, val trident: Trident)

class TridentHitDetector : Component {
    var stunned = false
}


fun setupTridentHitDetector(app: ECS) {
    app.onTick {
        for ((entity, spider, options, _) in app.query<ECSEntity, SpiderBody, SpiderOptions, TridentHitDetector>()) {
            val rider = entity.query<Mountable>()?.getRider()

            val location = spider.position.toLocation(spider.world)
            val tridents = spider.world.getNearbyEntities(location, 1.5, 1.5, 1.5)
                .filterIsInstance<Trident>()

            for (trident in tridents) {
                if (rider !== null && trident.shooter == rider) continue

                if (trident.velocity.length() < 2.0) continue

                val tridentDirection = trident.velocity.normalize()

                trident.velocity = tridentDirection.clone().multiply(-.3)
                app.emit(TridentHitEvent(entity = entity, spider = spider, trident = trident))

                spider.velocity.add(tridentDirection.multiply(options.gait.tridentKnockBack))

                // apply rotational acceleration
                val hitDirection = spider.position.clone().subtract(trident.position).normalize()
                val axis = UP_VECTOR.crossProduct(tridentDirection)
                val angle = hitDirection.angle(UP_VECTOR)

                val accelerationMagnitude = angle * options.gait.tridentRotationalKnockBack.toFloat()

                spider.accelerateRotation(axis, accelerationMagnitude)
            }
        }
    }
}