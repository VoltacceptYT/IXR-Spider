package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.ArmPose
import net.browselotus.spiderbot.spider.configuration.DroidRig
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import org.bukkit.Bukkit
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val HALF_PI = 1.5708f

private fun approach(current: Float, target: Float, k: Float) = current + (target - current) * k
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun ArmPose.approach(flex: Float, elbow: Float, abduct: Float, yaw: Float, k: Float) {
    this.flex = approach(this.flex, flex, k)
    this.elbow = approach(this.elbow, elbow, k)
    this.abduct = approach(this.abduct, abduct, k)
    this.yaw = approach(this.yaw, yaw, k)
}

/**
 * Drives the droid's skeleton from what is going on around it, every tick:
 *
 *  - walking: each arm swings against the leg on its side (read from where that foot is in its step),
 *    the chest counter-twists and leans into the run, elbows bend more the faster it goes
 *  - turning: the chest rolls into the turn
 *  - looking: the head tracks its target, else its owner, else slowly scans around; the chest helps turn
 *    when looking far to the side
 *  - fighting: the cannon arm swings up and aims at the target, the other arm comes up in a guard,
 *    firing kicks the cannon back
 *  - getting hit / stunned: the chest snaps back and the arms flail
 *  - standing still: slow breathing and sway
 */
fun setupDroidAnimation(app: ECS) {
    app.onEvent<SpiderShootEvent> { event ->
        event.entity.query<SpiderOptions>()?.bodyPlan?.rig?.recoil = 1f
    }

    app.onEvent<TridentHitEvent> { event ->
        event.entity.query<SpiderOptions>()?.bodyPlan?.rig?.shock = 1f
    }

    app.onTick {
        for ((entity, spider, options) in app.query<ECSEntity, SpiderBody, SpiderOptions>()) {
            val rig = options.bodyPlan.rig ?: continue
            animateDroid(entity, spider, options, rig)
        }
    }
}

private fun animateDroid(entity: ECSEntity, spider: SpiderBody, options: SpiderOptions, rig: DroidRig) {
    rig.time++
    val t = rig.time.toFloat()
    val s = options.bodyPlan.scale.toFloat()
    val gait = options.gait

    val horizontalSpeed = hypot(spider.velocity.x, spider.velocity.z)
    val moving = if (gait.maxSpeed > 1e-6) (horizontalSpeed / gait.maxSpeed).toFloat().coerceIn(0f, 1f) else 0f

    val facing = spider.forwardDirection()
    facing.y = 0.0
    if (facing.lengthSquared() < 1e-6) facing.z = 1.0 else facing.normalize()

    // how far forward (+1) or back (-1) each foot is from where it rests, which says where it is in its step
    fun legSwing(index: Int): Float {
        val leg = spider.legs.getOrNull(index) ?: return 0f
        val offset = leg.endEffector.clone().subtract(leg.restPosition)
        return (offset.dot(facing) / gait.comfortZoneRadius * 1.4).toFloat().coerceIn(-1f, 1f)
    }

    val swingL = legSwing(0)
    val swingR = legSwing(1)
    val breath = sin(t * 0.07f)
    val stride = swingL - swingR

    // ---------- what it is paying attention to ----------
    val attacker = entity.query<SpiderAttacker>()
    val target = attacker?.target?.takeIf { it.isValid && !it.isDead }
    val owner = entity.query<Owner>()?.let { Bukkit.getPlayer(it.playerUUID) }

    val lookAt: Vector? = target?.eyeLocation?.toVector()
        ?: owner
            ?.takeIf { it.world == spider.world && it.location.toVector().distanceSquared(spider.position) < 100.0 }
            ?.eyeLocation?.toVector()

    val inverse = Quaternionf(spider.orientation).conjugate()

    /** [point] as seen from a spot on the body (given in body space), in body space: x left, y up, z forward. */
    fun localTo(point: Vector, from: Vector3f): Vector3f {
        val origin = Vector3f(from).rotate(spider.orientation)
        val d = Vector3f(
            (point.x - spider.position.x - origin.x).toFloat(),
            (point.y - spider.position.y - origin.y).toFloat(),
            (point.z - spider.position.z - origin.z).toFloat(),
        )
        return d.rotate(inverse)
    }

    var gazeYaw: Float
    var gazePitch: Float
    if (lookAt != null) {
        val d = localTo(lookAt, Vector3f(0f, 0.69f * s, 0f))
        gazeYaw = atan2(d.x, d.z)
        gazePitch = atan2(-d.y, hypot(d.x, d.z))
    } else {
        // nothing to look at: scan around slowly
        gazeYaw = sin(t * 0.021f) * 0.45f * (1f - moving * 0.6f)
        gazePitch = sin(t * 0.013f) * 0.08f
    }

    val stunned = entity.query<TridentHitDetector>()?.stunned == true
    val flail = if (stunned) 1f else rig.shock

    // ---------- chest ----------
    val chestAim = if (target != null) gazeYaw.coerceIn(-1f, 1f) * 0.35f else 0f
    val chestYaw = stride * 0.22f + chestAim
    val chestPitch = moving * 0.12f + breath * 0.012f - flail * 0.30f - rig.recoil * 0.05f
    val chestRoll = -stride * 0.04f -
        (spider.rotationalVelocity.y * 3f).coerceIn(-0.12f, 0.12f) +
        sin(t * 0.031f) * 0.01f

    rig.chestYaw = approach(rig.chestYaw, chestYaw, 0.2f)
    rig.chestPitch = approach(rig.chestPitch, chestPitch, 0.25f)
    rig.chestRoll = approach(rig.chestRoll, chestRoll, 0.2f)

    // ---------- head (a child of the chest, so it only does what the chest does not) ----------
    val headYaw = (gazeYaw.coerceIn(-1.3f, 1.3f) - rig.chestYaw).coerceIn(-1.2f, 1.2f)
    val headPitch = (gazePitch - rig.chestPitch).coerceIn(-0.6f, 0.6f)
    val headRoll = flail * sin(t * 1.3f) * 0.2f

    rig.headYaw = approach(rig.headYaw, headYaw, 0.3f)
    rig.headPitch = approach(rig.headPitch, headPitch, 0.3f)
    rig.headRoll = approach(rig.headRoll, headRoll, 0.3f)

    // ---------- arms: swing against the leg on the same side ----------
    var lFlex = -swingL * 0.9f + breath * 0.03f
    var rFlex = -swingR * 0.9f - breath * 0.03f
    var lElbow = 0.20f + moving * 0.45f + max(0f, lFlex) * 0.7f
    var rElbow = 0.20f + moving * 0.45f + max(0f, rFlex) * 0.7f
    var lAbduct = 0.07f + breath * 0.015f
    var rAbduct = 0.07f + breath * 0.015f
    var lYaw = 0f
    val rYaw = 0f

    // knocked about
    lFlex += flail * sin(t * 0.9f) * 0.6f
    rFlex += flail * sin(t * 0.9f + 2f) * 0.6f
    lAbduct += flail * 0.5f
    rAbduct += flail * 0.5f
    lElbow += flail * 0.4f
    rElbow += flail * 0.4f

    // fighting: cannon arm (left) aims at the target, the right arm comes up in a guard
    rig.aim = if (target != null && !stunned) min(1f, rig.aim + 0.12f) else max(0f, rig.aim - 0.06f)
    if (rig.aim > 0f && lookAt != null) {
        val w = rig.aim
        val d = localTo(lookAt, Vector3f(0.335f * s, 0.50f * s, 0f))
        val aimYaw = (atan2(d.x, d.z) - rig.chestYaw).coerceIn(-1.3f, 1.3f)
        val aimPitch = (atan2(-d.y, hypot(d.x, d.z)) - rig.chestPitch).coerceIn(-1.1f, 1.1f)

        // with the elbow bent 90 degrees the forearm (and the cannon) points along the shoulder's own forward axis
        lYaw = mix(lYaw, aimYaw, w)
        lFlex = mix(lFlex, -aimPitch, w)
        lAbduct = mix(lAbduct, 0f, w)
        lElbow = mix(lElbow, HALF_PI, w)

        rFlex = mix(rFlex, 0.45f, w)
        rElbow = mix(rElbow, 1.2f, w)
        rAbduct = mix(rAbduct, 0.18f, w)
    }

    // kick back from the shot
    lElbow += rig.recoil * 0.35f

    rig.leftArm.approach(lFlex, lElbow, lAbduct, lYaw, if (rig.aim > 0f) 0.35f else 0.25f)
    rig.rightArm.approach(rFlex, rElbow, rAbduct, rYaw, 0.25f)

    rig.recoil *= 0.8f
    rig.shock *= 0.92f
    if (rig.recoil < 0.001f) rig.recoil = 0f
    if (rig.shock < 0.001f) rig.shock = 0f
}
