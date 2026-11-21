package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.capabilities
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.events.addEventListener
import org.bukkit.Bukkit
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.AbstractSkeleton
import org.bukkit.entity.Arrow
import org.bukkit.entity.Blaze
import org.bukkit.entity.Creeper
import org.bukkit.entity.Ghast
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Monster
import org.bukkit.entity.Pillager
import org.bukkit.entity.Player
import org.bukkit.entity.SmallFireball
import org.bukkit.attribute.Attribute
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityExplodeEvent
import java.util.UUID
import kotlin.math.max

/** Per-robot record of which mobs are going after it. Added to every robot via [ensureMobAggro]. */
class MobAggro : Component {
    /** Mobs the robot hit (or that hit the robot) -> ticks left of hunting it, regardless of range. */
    val provokedMobs = mutableMapOf<UUID, Int>()

    /** Mob -> ticks until it may attack the robot again. */
    val attackCooldowns = mutableMapOf<UUID, Int>()
}

private fun ECSEntity.mobAggro(): MobAggro {
    query<MobAggro>()?.let { return it }
    val created = MobAggro()
    addComponent(created)
    return created
}

/** Called when a robot's shot lands on [mob]: it will now hunt the robot. */
fun provokeMob(robot: ECSEntity, mob: LivingEntity) {
    if (mob is Player) return
    val options = robot.query<SpiderOptions>() ?: return
    if (!options.attack.mobsAttackRobot) return
    robot.mobAggro().provokedMobs[mob.uniqueId] = options.attack.mobRetaliateTicks
}

/** Called when [mob] hurts the robot: the robot remembers it and shoots back. */
fun provokeRobot(robot: ECSEntity, mob: LivingEntity) {
    val options = robot.query<SpiderOptions>() ?: return
    robot.query<SpiderAttacker>()?.provoked?.put(mob.uniqueId, options.attack.aggroMemoryTicks)
    if (mob !is Player && options.attack.mobsAttackRobot) {
        robot.mobAggro().provokedMobs[mob.uniqueId] = options.attack.mobRetaliateTicks
    }
}

private fun isRangedMob(mob: Mob) = mob is AbstractSkeleton || mob is Pillager || mob is Blaze || mob is Ghast

private fun attackDamageOf(mob: LivingEntity): Double =
    mob.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 2.0

/** Explosion power vanilla would use for this source. */
private fun explosionPower(source: org.bukkit.entity.Entity?): Double = when (source) {
    is Creeper -> if (source.isPowered) 6.0 else 3.0
    is org.bukkit.entity.TNTPrimed -> 4.0
    else -> 3.0
}

fun setupMobAggro(app: ECS) {
    // Explosions (creepers, TNT, ...) hurt robots caught in the blast.
    addEventListener(object : Listener {
        @EventHandler(ignoreCancelled = true)
        fun onExplode(event: EntityExplodeEvent) {
            val at = event.location
            for ((entity, spider, options) in app.query<ECSEntity, SpiderBody, SpiderOptions>()) {
                if (spider.world != at.world) continue
                val distance = spider.position.distance(at.toVector())
                val power = explosionPower(event.entity)
                val radius = power * 2.0 + options.bodyPlan.scale.coerceAtLeast(1.0)
                if (distance > radius) continue

                // vanilla explosion damage: ((i^2 + i) / 2) * 7 * power + 1, where i falls off with distance
                val impact = (1.0 - distance / radius).coerceIn(0.0, 1.0)
                hurtRobot(app, entity, at.toVector(), ((impact * impact + impact) / 2.0) * 7.0 * power + 1.0)
                (event.entity as? LivingEntity)?.let { provokeRobot(entity, it) }
            }
        }
    })

    app.onTick {
        for ((entity, spider, options) in app.query<ECSEntity, SpiderBody, SpiderOptions>()) {
            val config = options.attack
            if (!config.mobsAttackRobot) continue

            val aggro = entity.mobAggro()

            // age timers
            aggro.provokedMobs.entries.removeIf { it.setValue(it.value - 1) <= 1 }  // setValue returns the old value
            aggro.attackCooldowns.entries.removeIf { it.setValue(it.value - 1) <= 1 }

            val scale = options.bodyPlan.scale.coerceAtLeast(0.5)
            val range = max(config.mobAggroRange, 4.0)
            val search = range + 6.0

            val mobs = spider.world.getNearbyEntities(spider.location(), search, search, search)
                .filterIsInstance<Mob>()
                .filter { it.isValid && !it.isDead }

            for (mob in mobs) {
                val provoked = aggro.provokedMobs.containsKey(mob.uniqueId)
                if (!provoked && mob !is Monster) continue

                val distance = mob.location.toVector().distance(spider.position)
                if (!provoked) {
                    if (distance > range) continue

                    // Don't steal a mob from a player that is right next to it, but do take mobs that
                    // are idle or whose player target is further away than the robot.
                    val target = mob.target
                    if (target != null && target.isValid) {
                        val targetDistance = target.location.distance(mob.location)
                        if (targetDistance < distance) continue
                    }
                    if (!mob.hasLineOfSight(spider.location())) continue
                }

                // ---- movement: close in ----
                val ranged = isRangedMob(mob)
                val holdDistance = if (ranged) 7.0 else 1.5 * scale
                if (distance > holdDistance + 0.5 && Bukkit.getCurrentTick() % 5 == 0) {
                    mob.pathfinder.moveTo(spider.location(), 1.1)
                }
                mob.lookAt(spider.location())

                if ((aggro.attackCooldowns[mob.uniqueId] ?: 0) > 0) continue

                // ---- attack ----
                val reach = mob.width / 2.0 + 1.3 * scale + 0.8
                val at = mob.location.toVector().add(org.bukkit.util.Vector(0.0, mob.height * 0.5, 0.0))

                when {
                    mob is Creeper -> {
                        if (distance < 3.0 * scale + 1.0) {
                            mob.ignite()
                            aggro.attackCooldowns[mob.uniqueId] = 200
                        }
                    }

                    ranged -> {
                        if (distance > 14.0 || !mob.hasLineOfSight(spider.location())) continue
                        shootAtRobot(mob, spider.position.clone().add(org.bukkit.util.Vector(0.0, 0.8 * scale, 0.0)))
                        mob.swingMainHand()
                        aggro.attackCooldowns[mob.uniqueId] = if (mob is Blaze) 30 else 40
                    }

                    distance <= reach -> {
                        hurtRobot(app, entity, at, attackDamageOf(mob))
                        mob.swingMainHand()
                        provokeRobot(entity, mob)
                        aggro.attackCooldowns[mob.uniqueId] = config.mobAttackCooldownTicks
                    }
                }
            }

            if (!options.capabilities().online) aggro.provokedMobs.clear()
        }
    }
}

private fun shootAtRobot(mob: Mob, aimAt: org.bukkit.util.Vector) {
    val from = mob.eyeLocation
    val direction = aimAt.clone().subtract(from.toVector())
    if (direction.lengthSquared() < 1e-6) return
    direction.normalize()

    when (mob) {
        is Blaze -> {
            mob.world.spawn(from.clone().add(direction.clone().multiply(0.8)), SmallFireball::class.java) {
                it.shooter = mob
                it.direction = direction.clone()
                it.velocity = direction.clone().multiply(1.0)
                it.setIsIncendiary(false)
            }
        }

        is Ghast -> {
            mob.world.spawn(from.clone().add(direction.clone().multiply(1.5)), org.bukkit.entity.LargeFireball::class.java) {
                it.shooter = mob
                it.direction = direction.clone()
                it.velocity = direction.clone().multiply(1.0)
                it.yield = 0f
                it.setIsIncendiary(false)
            }
        }

        else -> {
            mob.world.spawn(from, Arrow::class.java) {
                it.shooter = mob
                it.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                it.velocity = direction.clone().multiply(1.8)
            }
        }
    }
}
