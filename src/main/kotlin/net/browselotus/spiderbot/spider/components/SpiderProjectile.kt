package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.maths.FORWARD_VECTOR
import net.browselotus.spiderbot.utilities.resolveCollision
import net.browselotus.spiderbot.utilities.rendering.interpolateTransform
import net.browselotus.spiderbot.utilities.rendering.renderItem
import com.destroystokyo.paper.profile.ProfileProperty
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.joml.Matrix4f
import org.joml.Quaternionf
import java.util.UUID

/** The custom player head skin used for the spider's fired projectile. */
private const val SPIDER_SHOT_HEAD_TEXTURE =
    "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmI1ODY0MWU3NmRhODI2MjE3MDhhM2Q2YzEwYmI0NTBjMjNkNDc1ZTUyMTAzMTBkMGI4N2U0NjBhNWZjMjM1NCJ9fX0="

fun createSpiderShotHeadItem(): ItemStack {
    val item = ItemStack(Material.PLAYER_HEAD)
    val meta = item.itemMeta as SkullMeta

    val profile = Bukkit.createProfile(UUID.randomUUID(), "spider_shot")
    profile.setProperty(ProfileProperty("textures", SPIDER_SHOT_HEAD_TEXTURE))
    meta.playerProfile = profile

    item.itemMeta = meta
    return item
}

/** Fired when a [SpiderProjectile] stops, whether it hit [target] or just hit a block (target will still be its intended one). */
class SpiderProjectileHitEvent(val world: World, val position: Vector, val target: LivingEntity)

class SpiderProjectile(
    val world: World,
    val position: Vector,
    val velocity: Vector,
    val target: LivingEntity,
    val damage: Double,
    val slownessDurationTicks: Int,
    val slownessAmplifier: Int,
    val maxAgeTicks: Int,
    val scale: Double,
) : Component {
    var age = 0
    var previousPosition: Vector = position.clone()
}

/** Spawns and launches a projectile from [spider] towards [target], using [options].attack for its stats. */
fun fireSpiderProjectile(app: ECS, entity: ECSEntity, spider: SpiderBody, options: SpiderOptions, target: LivingEntity) {
    val config = options.attack
    val scale = options.bodyPlan.scale

    val muzzle = spider.position.clone()
        .add(Vector(0.0, 0.3, 0.0).multiply(scale))
        .add(spider.forwardDirection().multiply(0.6 * scale))

    val direction = target.eyeLocation.toVector().subtract(muzzle)
    if (direction.lengthSquared() < 1e-6) return
    direction.normalize()

    app.spawn(
        SpiderProjectile(
            world = spider.world,
            position = muzzle,
            velocity = direction.multiply(config.projectileSpeed),
            target = target,
            damage = config.damage,
            slownessDurationTicks = config.slownessDurationTicks,
            slownessAmplifier = config.slownessAmplifier,
            maxAgeTicks = config.projectileLifetimeTicks,
            scale = scale,
        )
    )

    app.emit(SpiderShootEvent(entity = entity, spider = spider, target = target))
}

fun setupSpiderProjectiles(app: ECS) {
    app.onTick {
        for ((entity, projectile) in app.query<ECSEntity, SpiderProjectile>()) {
            projectile.age++

            projectile.previousPosition = projectile.position.clone()
            projectile.position.add(projectile.velocity)

            val delta = projectile.position.clone().subtract(projectile.previousPosition)

            // Stop at blocks instead of flying through walls.
            val blockCollision = projectile.world.resolveCollision(projectile.position, delta.clone())
            if (blockCollision != null) {
                app.emit(SpiderProjectileHitEvent(world = projectile.world, position = blockCollision.position, target = projectile.target))
                entity.remove()
                continue
            }

            val target = projectile.target
            if (!target.isValid || target.isDead) {
                entity.remove()
                continue
            }

            if (delta.lengthSquared() > 1e-9) {
                val hitBox = target.boundingBox.clone().expand(0.3)
                val hit = hitBox.rayTrace(projectile.previousPosition, delta.clone().normalize(), delta.length())
                if (hit != null) {
                    target.damage(projectile.damage)
                    target.addPotionEffect(
                        PotionEffect(PotionEffectType.SLOWNESS, projectile.slownessDurationTicks, projectile.slownessAmplifier, false, true)
                    )
                    app.emit(SpiderProjectileHitEvent(world = projectile.world, position = projectile.position.clone(), target = target))
                    entity.remove()
                    continue
                }
            }

            if (projectile.age >= projectile.maxAgeTicks) {
                entity.remove()
            }
        }
    }

    app.onRender {
        for ((entity, projectile) in app.query<ECSEntity, SpiderProjectile>()) {
            val direction = projectile.velocity.clone()
            if (direction.lengthSquared() < 1e-9) direction.z = 1.0
            val orientation = Quaternionf().rotationTo(FORWARD_VECTOR.toVector3f(), direction.normalize().toVector3f())

            renderItem(
                location = projectile.position.toLocation(projectile.world),
                init = {
                    it.setItemStack(createSpiderShotHeadItem())
                    it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.HEAD
                    it.billboard = Display.Billboard.FIXED
                    it.setGravity(false)
                    it.isPersistent = false
                    it.teleportDuration = 1
                    it.interpolationDuration = 1
                },
                update = {
                    it.interpolateTransform(Matrix4f().rotate(orientation).scale((projectile.scale * 0.75).toFloat()))
                }
            ).submit(entity)
        }
    }
}