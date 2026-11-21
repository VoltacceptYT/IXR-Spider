package net.browselotus.spiderbot.spider.presets

import net.browselotus.spiderbot.spider.components.body.GaitType
import net.browselotus.spiderbot.spider.configuration.BodyPlan
import net.browselotus.spiderbot.spider.configuration.CreatureType
import net.browselotus.spiderbot.spider.configuration.Gait
import net.browselotus.spiderbot.spider.configuration.LegPlan
import net.browselotus.spiderbot.spider.configuration.SegmentPlan
import net.browselotus.spiderbot.spider.configuration.SoundPlayer
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.utilities.maths.FORWARD_VECTOR
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.util.Vector


private fun equalLength(segmentCount: Int, length: Double): List<SegmentPlan> {
    return List(segmentCount) { SegmentPlan(length, FORWARD_VECTOR) }
}

private fun BodyPlan.addLegPair(root: Vector, rest: Vector, segments: List<SegmentPlan>) {
    legs += LegPlan(Vector( root.x, root.y, root.z), Vector( rest.x, rest.y, rest.z), segments)
    legs += LegPlan(Vector(-root.x, root.y, root.z), Vector(-rest.x, rest.y, rest.z), segments.map { it.clone() })
}

fun biped(segmentCount: Int, segmentLength: Double): SpiderOptions {
    val options = SpiderOptions()
    options.bodyPlan.addLegPair(Vector(.0, .0, .0), Vector(1.0, .0, .0), equalLength(segmentCount, 1.0 * segmentLength))
    applyLineLegModel(options.bodyPlan, Material.NETHERITE_BLOCK.createBlockData())
    return options
}

fun quadruped(segmentCount: Int, segmentLength: Double): SpiderOptions {
    val options = SpiderOptions()
    options.bodyPlan.addLegPair(Vector(.0, .0, .0), Vector(0.9,.0, 0.9), equalLength(segmentCount, 0.9 * segmentLength))
    options.bodyPlan.addLegPair(Vector(.0, .0, .0), Vector(1.0, .0, -1.1), equalLength(segmentCount, 1.2 * segmentLength))
    applyLineLegModel(options.bodyPlan, Material.NETHERITE_BLOCK.createBlockData())
    return options
}

fun hexapod(segmentCount: Int, segmentLength: Double): SpiderOptions {
    val options = SpiderOptions()
    options.bodyPlan.addLegPair(Vector(.0,.0,0.1), Vector(1.0,.0, 1.1), equalLength(segmentCount, 1.1 * segmentLength))
    options.bodyPlan.addLegPair(Vector(.0,.0,0.0), Vector(1.3,.0,-0.3), equalLength(segmentCount, 1.1 * segmentLength))
    options.bodyPlan.addLegPair(Vector(.0,.0,-.1), Vector(1.2,.0,-2.0), equalLength(segmentCount, 1.6 * segmentLength))
    applyLineLegModel(options.bodyPlan, Material.NETHERITE_BLOCK.createBlockData())
    return options
}

fun octopod(segmentCount: Int, segmentLength: Double): SpiderOptions {
    val options = SpiderOptions()
    options.bodyPlan.addLegPair(Vector(.0,.0,  .1), Vector(1.0, .0,  1.6), equalLength(segmentCount, 1.1 * segmentLength))
    options.bodyPlan.addLegPair(Vector(.0,.0,  .0), Vector(1.3, .0,  0.4), equalLength(segmentCount, 1.0 * segmentLength))
    options.bodyPlan.addLegPair(Vector(.0,.0, -.1), Vector(1.3, .0, -0.9), equalLength(segmentCount, 1.1 * segmentLength))
    options.bodyPlan.addLegPair(Vector(.0,.0, -.2), Vector(1.1, .0, -2.5), equalLength(segmentCount, 1.6 * segmentLength))
    applyLineLegModel(options.bodyPlan, Material.NETHERITE_BLOCK.createBlockData())
    return options
}


private fun createRobotSegments(segmentCount: Int, lengthScale: Double) = List(segmentCount) { index ->
    var length = lengthScale.toFloat()
    var initDirection = FORWARD_VECTOR

    if (index == 0) {
        length *= .5f
        initDirection = initDirection.rotateAroundX(Math.PI / 3)
    }

    if (index == 1) length *= .8f

    SegmentPlan(length.toDouble(), initDirection)
}


fun quadBot(segmentCount: Int, segmentLength: Double): SpiderOptions {
    val options = SpiderOptions()
    options.bodyPlan.bodyModel = SpiderTorsoModels.FLAT.model.clone()
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15, .2), rest = Vector(1.3 * 1.0,.0, 1.0), createRobotSegments(segmentCount, .9 * .7 * segmentLength))
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15,-.2), rest = Vector(1.3 * 1.1,.0,-1.2), createRobotSegments(segmentCount, 1.2 * .7 * segmentLength))
    applyMechanicalLegModel(options.bodyPlan)
    return options
}

fun hexBot(segmentCount: Int, segmentLength: Double): SpiderOptions {
    val options = SpiderOptions()
    options.bodyPlan.bodyModel = SpiderTorsoModels.FLAT.model.clone()
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15, .2), rest = Vector(1.3 * 1.0,.0, 1.3), createRobotSegments(segmentCount, 1.1 * .7 * segmentLength))
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15, .0), rest = Vector(1.3 * 1.2,.0,-0.1), createRobotSegments(segmentCount, 1.1 * .7 * segmentLength))
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15,-.2), rest = Vector(1.3 * 1.1,.0,-1.6), createRobotSegments(segmentCount, 1.3 * .7 * segmentLength))
    applyMechanicalLegModel(options.bodyPlan)
    return options
}

fun octoBot(segmentCount: Int, segmentLength: Double): SpiderOptions {
    val options = SpiderOptions()
    options.bodyPlan.bodyModel = SpiderTorsoModels.FLAT.model.clone()
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15, .3), rest = Vector(1.3 * 1.0,.0, 1.3), createRobotSegments(segmentCount, 1.1 * .7 * segmentLength))
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15, .1), rest = Vector(1.3 * 1.2,.0, 0.5), createRobotSegments(segmentCount, 1.0 * .7 * segmentLength))
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15, .1), rest = Vector(1.3 * 1.2,.0,-0.7), createRobotSegments(segmentCount, 1.1 * .7 * segmentLength))
    options.bodyPlan.addLegPair(root = Vector(.2,-.2 - .15,-.3), rest = Vector(1.3 * 1.1,.0,-1.6), createRobotSegments(segmentCount, 1.3 * .7 * segmentLength))
    applyMechanicalLegModel(options.bodyPlan)
    return options
}


// ----- Droid: a humanoid biped that fights like the spiders do -----

/** How far below the droid's body centre (its pelvis) the hips sit. */
private const val DROID_HIP_DROP = 0.08

/** Vertical distance from the hips to the ground while standing; a little less than the leg's full length, so the knees stay bent. */
private const val DROID_LEG_REACH = 0.82

private const val DROID_THIGH_LENGTH = 0.46
private const val DROID_SHIN_LENGTH = 0.46

private fun Gait.configureForDroid(bodyHeight: Double) {
    stationary.bodyHeight = bodyHeight
    stationary.triggerZoneRadius = .1
    moving.bodyHeight = bodyHeight
    moving.triggerZoneRadius = .3

    // legs are far shorter than a spider's, so everything about the steps is smaller too
    comfortZoneRadius = .42
    legLiftHeight = .2

    // With only two legs, the body is held up by whichever foot is down (the gait only ever lifts one at a
    // time) instead of by a polygon of feet, and it stays upright instead of tilting to match the ground.
    useLegacyNormalForce = true
    disableAdvancedRotation = true
    uncomfortableSpeedMultiplier = .5
}

/**
 * A humanoid robot: a torso, head and cannon arm on two legs. It walks, follows its owner, targets
 * enemies and shoots exactly like the spiders do (it is the same body underneath), but looks different
 * and fires a different projectile, see [CreatureType.DROID].
 *
 * A droid always has a thigh and a shin, so [segmentCount] is ignored. [segmentLength] scales the legs.
 */
fun droid(@Suppress("UNUSED_PARAMETER") segmentCount: Int, segmentLength: Double): SpiderOptions {
    val options = SpiderOptions()
    options.creature = CreatureType.DROID

    val bodyHeight = DROID_HIP_DROP + DROID_LEG_REACH * segmentLength
    options.walkGait = Gait(.12, GaitType.WALK).apply { configureForDroid(bodyHeight) }
    // a droid runs by taking quicker steps rather than by galloping like a spider
    options.gallopGait = Gait(.25, GaitType.WALK).apply { configureForDroid(bodyHeight) }
    options.sound.step = SoundPlayer(Sound.BLOCK_METAL_STEP, .3f, .8f)

    options.bodyPlan.bodyModel = DroidModels.pelvis()
    options.bodyPlan.rig = DroidModels.rig()
    options.bodyPlan.addLegPair(
        root = Vector(.12, -DROID_HIP_DROP, .0),
        rest = Vector(.12, .0, .0),
        segments = listOf(
            SegmentPlan(DROID_THIGH_LENGTH * segmentLength, FORWARD_VECTOR),
            SegmentPlan(DROID_SHIN_LENGTH * segmentLength, FORWARD_VECTOR),
        ),
    )

    // knees point forward, like a person's
    for (leg in options.bodyPlan.legs) leg.bendDirection = Vector(.0, .0, 1.0)

    DroidModels.applyLegModels(options.bodyPlan)
    return options
}
