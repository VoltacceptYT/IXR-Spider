package net.browselotus.spiderbot.spider.components.rendering

import net.browselotus.spiderbot.utilities.rendering.interpolateTransform
import net.browselotus.spiderbot.utilities.rendering.renderBlock
import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.components.Cloak
import net.browselotus.spiderbot.spider.components.damagedLook
import net.browselotus.spiderbot.spider.configuration.CreatureType
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.hiddenRigParts
import net.browselotus.spiderbot.spider.configuration.wearForRigPart
import net.browselotus.spiderbot.utilities.*
import net.browselotus.spiderbot.utilities.rendering.RenderGroup
import org.bukkit.util.Vector
import org.joml.Matrix4f
import org.joml.Vector4f


fun renderSpider(spider: SpiderBody, cloak: Cloak, options: SpiderOptions): RenderGroup {
    val group = RenderGroup()

    val damage = options.damage
    val transform = Matrix4f().rotate(spider.orientation)
    val bodyHealth = if (options.creature == CreatureType.DROID) damage.of("chest") else damage.of("core")
    group[spider] = renderModel(spider, cloak, spider.position, options.bodyPlan.bodyModel, transform, options, 1f - bodyHealth.toFloat())

    // the droid's separately moving parts: each one is posed by the rig and then follows the body
    val rig = options.bodyPlan.rig
    if (rig != null) {
        rig.updateChains()
        val hidden = hiddenRigParts(damage)
        for (part in rig.parts) {
            if (part.name in hidden) continue
            val partTransform = Matrix4f(transform).mul(rig.chain(part.name))
            group["rig:${part.name}"] = renderModel(spider, cloak, spider.position, part.model, partTransform, options, wearForRigPart(damage, part.name))
        }
    }


    for ((legIndex, leg) in spider.legs.withIndex()) {
        if (damage.of("leg$legIndex") <= 0.0) continue

        val chain = leg.chain

        val legPlan = options.bodyPlan.legs.getOrNull(legIndex)

        // upright limbs (e.g. a droid's legs) are drawn facing the body's heading, which stays
        // stable even when the limb points straight down; spider legs keep the yaw/pitch frame
        val rotations = if (legPlan?.bendDirection != null) {
            chain.getRotationsFacing(spider.forwardDirection().toVector3f())
        } else {
            chain.getRotations(options.gait.legChainPivotMode.get(spider))
        }

        for ((segmentIndex, rotation) in rotations.withIndex()) {
            val segmentPlan = legPlan?.segments?.getOrNull(segmentIndex) ?: continue

            val parent = chain.segments.getOrNull(segmentIndex - 1)?.position ?: chain.root

            val segmentTransform = Matrix4f().rotate(rotation)
            group[legIndex to segmentIndex] = renderModel(spider, cloak, parent, segmentPlan.model, segmentTransform, options, 1f - damage.of("leg$legIndex").toFloat())

        }
    }

    return group
}

private fun renderModel(
    spider: SpiderBody,
    cloak: Cloak,
    position: Vector,
    model: DisplayModel,
    transformation: Matrix4f,
    options: SpiderOptions,
    wear: Float = 0f,
): RenderGroup {
    val group = RenderGroup()

    for ((index, piece) in model.pieces.withIndex()) {
        group[index] = renderModelPiece(spider, cloak, position, piece, transformation, options, index, wear)
    }

    return group
}


private fun renderModelPiece(
    spider: SpiderBody,
    cloak: Cloak,
    position: Vector,
    piece: BlockDisplayModelPiece,
    transformation: Matrix4f,
    options: SpiderOptions,
    index: Int,
    wear: Float,
) = renderBlock(
    location = position.toLocation(spider.world),
    init = {
        it.teleportDuration = 1
        it.interpolationDuration = 1
    },
    update = {
        val transform = Matrix4f(transformation).mul(piece.transform)
        it.interpolateTransform(transform)

        val cloak = if (piece.tags.contains("cloak")) {
            val relative = transform.transform(Vector4f(.5f, .5f, .5f, 1f))
            val piecePosition = position.clone()
            piecePosition.x += relative.x
            piecePosition.y += relative.y
            piecePosition.z += relative.z

            cloak.getPiece(piece, spider.world, piecePosition, piece.block, piece.brightness, options.cloak)
        } else null

        if (cloak != null) {
            it.block = cloak.first
            it.brightness = cloak.second
        } else {
            it.block = piece.block
            it.brightness = piece.brightness
        }

        if (wear > 0f) {
            val look = damagedLook(piece.tags, index, wear)
            if (look != null) {
                it.block = look.first
                it.brightness = look.second
            }
        }
    }
)