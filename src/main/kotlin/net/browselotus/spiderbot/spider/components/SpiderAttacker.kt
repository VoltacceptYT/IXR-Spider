package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.events.addEventListener
import net.browselotus.spiderbot.utilities.overloads.position
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import java.util.UUID

/** Fired whenever a spider fires a [SpiderProjectile] at [target]. */
class SpiderShootEvent(val entity: ECSEntity, val spider: SpiderBody, val target: LivingEntity)

/**
 * Gives a spider the ability to notice hostile mobs (and anything caught fighting its owner
 * in either direction), chase them down and shoot them. See [setupSpiderAttack].
 */
class SpiderAttacker : Component {
    var target: LivingEntity? = null
    var cooldown = 0

    /** Entities the owner recently fought, in either direction, mapped to the ticks left before the spider forgets them. */
    val provoked = mutableMapOf<UUID, Int>()
}

/** Resolves the actual attacker behind a damage source, following projectiles back to their shooter. */
private fun resolveAttacker(entity: Entity): LivingEntity? = when (entity) {
    is LivingEntity -> entity
    is Projectile -> entity.shooter as? LivingEntity
    else -> null
}

fun setupSpiderAttack(app: ECS) {
    // Remember anything that fights the spider's owner, in either direction, so the spider
    // can either finish off what its owner is attacking, or defend its owner from attackers.
    addEventListener(object : Listener {
        @EventHandler(ignoreCancelled = true)
        fun onDamage(event: EntityDamageByEntityEvent) {
            val attacker = resolveAttacker(event.damager) ?: return
            val victim = event.entity as? LivingEntity ?: return

            for ((spiderAttacker, owner, options) in app.query<SpiderAttacker, Owner, SpiderOptions>()) {
                val ownerPlayer = Bukkit.getPlayer(owner.playerUUID) ?: continue
                val memory = options.attack.aggroMemoryTicks

                if (attacker == ownerPlayer && victim != ownerPlayer) {
                    spiderAttacker.provoked[victim.uniqueId] = memory
                }

                if (victim == ownerPlayer && attacker != ownerPlayer) {
                    spiderAttacker.provoked[attacker.uniqueId] = memory
                }
            }
        }
    })

    app.onTick {
        for ((entity, spider, options, attacker) in app.query<ECSEntity, SpiderBody, SpiderOptions, SpiderAttacker>()) {
            val config = options.attack

            // age out old provocations
            val iterator = attacker.provoked.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val remaining = entry.value - 1
                if (remaining <= 0) iterator.remove() else entry.setValue(remaining)
            }

            if (attacker.cooldown > 0) attacker.cooldown--

            if (!config.enabled) {
                attacker.target = null
                continue
            }

            val ownerPlayer = entity.query<Owner>()?.let { Bukkit.getPlayer(it.playerUUID) }

            val currentTarget = attacker.target
            val stillValid = currentTarget != null &&
                currentTarget.isValid &&
                !currentTarget.isDead &&
                !currentTarget.isInvulnerable &&
                currentTarget.world == spider.world &&
                currentTarget.position.distance(spider.position) <= config.loseRange

            if (!stillValid) {
                val candidates = spider.world.getNearbyEntities(spider.location(), config.detectRange, config.detectRange, config.detectRange)
                    .asSequence()
                    .filterIsInstance<LivingEntity>()
                    .filter { it.isValid && !it.isDead }
                    .filter { it != ownerPlayer }
                    .filter { !it.isInvulnerable }
                    .filter { it is Monster || attacker.provoked.containsKey(it.uniqueId) }

                attacker.target = candidates.minByOrNull { it.position.distanceSquared(spider.position) }
            }

            val target = attacker.target ?: continue

            // Approach and hold at range, reusing the same walk/face logic as TargetBehaviour.
            entity.replaceComponent<SpiderBehaviour>(TargetBehaviour(target.position, config.followDistance))

            val distance = spider.position.distance(target.position)
            if (attacker.cooldown <= 0 && distance <= config.attackRange) {
                fireSpiderProjectile(app, entity, spider, options, target)
                attacker.cooldown = config.cooldownTicks
            }
        }
    }
}