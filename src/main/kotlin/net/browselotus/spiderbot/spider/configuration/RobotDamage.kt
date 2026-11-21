package net.browselotus.spiderbot.spider.configuration

import net.kyori.adventure.text.format.NamedTextColor
import kotlin.math.min

const val FULL_FUNCTION_HEALTH = 0.6
const val CRITICAL_HEALTH = 0.3

enum class PartRole { CORE, HEAD, CANNON_ARM, GUARD_ARM, LEG }

class PartSpec(val id: String, val label: String, val role: PartRole, val legIndex: Int = -1) {
    val detachable get() = role != PartRole.CORE
}

enum class PartStatus(val label: String, val color: NamedTextColor) {
    OPERATIONAL("OPERATIONAL", NamedTextColor.GREEN),
    DAMAGED("DAMAGED", NamedTextColor.YELLOW),
    CRITICAL("CRITICAL", NamedTextColor.RED),
    FAILED("FAILED", NamedTextColor.DARK_RED),
}

fun statusOf(health: Double) = when {
    health <= 0.0 -> PartStatus.FAILED
    health < CRITICAL_HEALTH -> PartStatus.CRITICAL
    health < FULL_FUNCTION_HEALTH -> PartStatus.DAMAGED
    else -> PartStatus.OPERATIONAL
}

fun efficiency(health: Double) = min(1.0, health / FULL_FUNCTION_HEALTH).coerceAtLeast(0.0)

class RobotDamage {
    val health = mutableMapOf<String, Double>()

    fun of(id: String) = health[id] ?: 1.0

    fun set(id: String, value: Double) {
        if (value >= 0.999) health.remove(id) else health[id] = value.coerceAtLeast(0.0)
    }

    fun isDamaged() = health.isNotEmpty()
}

class Capabilities(
    val online: Boolean,
    val mobility: Double,
    val firepower: Double,
    val sensors: Double,
    val aim: Double,
) {
    val spread get() = (1.0 - aim) * 0.3
}

fun SpiderOptions.partSpecs(): List<PartSpec> = when (creature) {
    CreatureType.DROID -> listOf(
        PartSpec("head", "Head", PartRole.HEAD),
        PartSpec("chest", "Chest Core", PartRole.CORE),
        PartSpec("armL", "Cannon Arm", PartRole.CANNON_ARM),
        PartSpec("armR", "Guard Arm", PartRole.GUARD_ARM),
        PartSpec("leg0", "Left Leg", PartRole.LEG, 0),
        PartSpec("leg1", "Right Leg", PartRole.LEG, 1),
    )
    CreatureType.SPIDER -> listOf(PartSpec("core", "Central Core", PartRole.CORE)) +
        bodyPlan.legs.indices.map {
            PartSpec("leg$it", "${if (it % 2 == 0) "Left" else "Right"} Leg ${it / 2 + 1}", PartRole.LEG, it)
        }
}

fun SpiderOptions.capabilities(): Capabilities {
    val d = damage
    val legCount = bodyPlan.legs.size
    val mobility = if (legCount == 0) 1.0 else (0 until legCount).sumOf { efficiency(d.of("leg$it")) } / legCount

    return if (creature == CreatureType.DROID) {
        val cannon = efficiency(d.of("armL"))
        Capabilities(
            online = d.of("chest") > 0.0,
            mobility = mobility,
            firepower = cannon,
            sensors = efficiency(d.of("head")),
            aim = (cannon + efficiency(d.of("armR"))) / 2,
        )
    } else {
        val core = efficiency(d.of("core"))
        Capabilities(online = core > 0.0, mobility = mobility, firepower = core, sensors = core, aim = core)
    }
}

fun hiddenRigParts(damage: RobotDamage): Set<String> = buildSet {
    if (damage.of("head") <= 0.0) add(DroidRig.HEAD)
    if (damage.of("armL") <= 0.0) { add(DroidRig.UPPER_L); add(DroidRig.FORE_L) }
    if (damage.of("armR") <= 0.0) { add(DroidRig.UPPER_R); add(DroidRig.FORE_R) }
}

fun wearForRigPart(damage: RobotDamage, name: String): Float = 1f - when (name) {
    DroidRig.HEAD -> damage.of("head")
    DroidRig.UPPER_L, DroidRig.FORE_L -> damage.of("armL")
    DroidRig.UPPER_R, DroidRig.FORE_R -> damage.of("armR")
    else -> damage.of("chest")
}.toFloat()
