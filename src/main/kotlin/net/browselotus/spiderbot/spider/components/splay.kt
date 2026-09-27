package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.utilities.*
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.events.interval
import net.browselotus.spiderbot.utilities.events.runLater
import net.browselotus.spiderbot.utilities.maths.eased
import net.browselotus.spiderbot.utilities.maths.moveTowards
import net.browselotus.spiderbot.utilities.overloads.position
import net.browselotus.spiderbot.utilities.rendering.RenderEntityTracker
import org.bukkit.entity.BlockDisplay
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.random.Random

private fun Transformation.lerp(newTransform: Transformation, lerpAmount: Float): Transformation {

    this.translation.lerp(newTransform.translation, lerpAmount)
    this.scale.lerp(newTransform.scale, lerpAmount)
    this.leftRotation.slerp(newTransform.leftRotation, lerpAmount)
    this.rightRotation.slerp(newTransform.rightRotation, lerpAmount)

    return this
}

fun Transformation.clone() = Transformation(
    Vector3f(translation),
    Quaternionf(leftRotation),
    Vector3f(scale),
    Quaternionf(rightRotation)
)


fun splay(spiderEntity: ECSEntity) {
    val spider = spiderEntity.query<SpiderBody>() ?: return
    val options = spiderEntity.query<SpiderOptions>() ?: return
    spiderEntity.remove()

    // detach and get entities
    val entities = mutableListOf<BlockDisplay>()
    for ((id, entity) in RenderEntityTracker.getAll()) {
        if (entity !is BlockDisplay) continue
        entities += entity
        RenderEntityTracker.detach(id)
    }

    onPluginShutdown {
        for (entity in entities) entity.remove()
    }


    val pieces = mutableListOf<BlockDisplayModelPiece>()
    for (piece in options.bodyPlan.bodyModel.pieces) {
        pieces += piece
    }

    for ((legIndex, leg) in spider.legs.withIndex()) {
        for ((segmentIndex, segment) in leg.chain.segments.withIndex()) {
            val model = options.bodyPlan.legs[legIndex].segments[segmentIndex].model
            for (piece in model.pieces) pieces += piece
        }
    }

    for ((i, entity) in entities.withIndex().shuffled()) {
        val offset = entity.position.subtract(spider.position).toVector3f()
        runLater(3L + i / 4) {
            splay(entity, offset)
        }
    }
}

private fun splay(entity: BlockDisplay, offset: Vector3f) {
    val start = entity.transformation

    val end = entity.transformation
    end.translation.apply {
        this
        .add(offset)
        .normalize()
        .mul(Random.nextDouble(1.0, 3.0).toFloat())
        .sub(offset)
    }
    end.scale.set(.35f)
    end.leftRotation.identity()
    end.rightRotation.identity()

    entity.interpolationDuration = 1
    entity.interpolationDelay = 0

    var t = .0f
    interval(0, 1) {
        t = t.moveTowards(1f, .07f)

        entity.transformation = start.lerp(end, t.eased())
        entity.interpolationDelay = 0

        if (t >= 1) it.close()
    }
}