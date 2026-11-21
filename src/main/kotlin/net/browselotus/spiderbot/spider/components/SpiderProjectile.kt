package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.CreatureType
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.capabilities
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.maths.FORWARD_VECTOR
import net.browselotus.spiderbot.utilities.maths.rotate
import net.browselotus.spiderbot.utilities.resolveCollision
import net.browselotus.spiderbot.utilities.events.interval
import net.browselotus.spiderbot.utilities.rendering.interpolateTransform
import net.browselotus.spiderbot.utilities.rendering.renderBlock
import net.browselotus.spiderbot.utilities.rendering.renderItem
import com.destroystokyo.paper.profile.ProfileProperty
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.data.Levelled
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
import org.joml.Vector3f
import java.util.UUID
import kotlin.random.Random

/** Builds the player head (with the given base64 skin texture) that is rendered as a creature's projectile. */
fun createShotHeadItem(texture: String, profileName: String = "shot"): ItemStack {
    val item = ItemStack(Material.PLAYER_HEAD)
    val meta = item.itemMeta as SkullMeta

    val profile = Bukkit.createProfile(UUID.randomUUID(), profileName)
    profile.setProperty(ProfileProperty("textures", texture))
    meta.playerProfile = profile

    item.itemMeta = meta
    return item
}

fun createSpiderShotHeadItem(): ItemStack = createShotHeadItem(CreatureType.SPIDER.shotTexture, "spider_shot")

/** Fired when a [SpiderProjectile] stops, whether it hit [target] or just hit a block (target will still be its intended one). */
class SpiderProjectileHitEvent(
    val world: World,
    val position: Vector,
    val target: LivingEntity,
    val creature: CreatureType = CreatureType.SPIDER,
)

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
    /** Who fired this, which decides what the projectile looks like. */
    val creature: CreatureType = CreatureType.SPIDER,
    /** The robot that fired this, so whatever it hits can hit back. */
    val shooter: ECSEntity? = null,
    /** Pellets from a beam splitter land together, so each one has to be able to hurt despite the target's hit immunity. */
    val ignoreHitImmunity: Boolean = false,
) : Component {
    var age = 0
    var previousPosition: Vector = position.clone()
}

/** Beam splitter: how far (radians) the outer lasers fan out from the middle one. */
private const val BEAM_SPLIT_ANGLE = 0.1
/** Beam splitter: random wobble added to each laser, so the blast looks like a shotgun spread. */
private const val BEAM_SPLIT_JITTER = 0.03
/** Beam splitter: each of the three lasers is this big compared to a normal one. */
private const val BEAM_SPLIT_SIZE_FACTOR = 0.6
/** Beam splitter: each of the three lasers does this fraction of a normal laser's damage. */
private const val BEAM_SPLIT_DAMAGE_FACTOR = 0.5

/** Spawns and launches a projectile from [spider] towards [target], using [options].attack for its stats. */
fun fireSpiderProjectile(app: ECS, entity: ECSEntity, spider: SpiderBody, options: SpiderOptions, target: LivingEntity) {
    val config = options.attack
    val scale = options.bodyPlan.scale

    // a droid fires from the tip of its cannon arm, wherever the arm is pointing right now;
    // everything else has a fixed muzzle spot on the body, so it follows the body's orientation
    val muzzle = options.bodyPlan.rig?.muzzleWorld(spider.position, spider.orientation)
        ?: spider.position.clone()
            .add(options.creature.muzzleOffset.multiply(scale).rotate(spider.orientation))

    val direction = target.eyeLocation.toVector().subtract(muzzle)
    if (direction.lengthSquared() < 1e-6) return
    direction.normalize()

    val spread = options.capabilities().spread
    if (spread > 0.0) {
        direction.add(Vector(Random.nextDouble(-spread, spread), Random.nextDouble(-spread, spread), Random.nextDouble(-spread, spread))).normalize()
    }

    if (options.upgrades.beamSplitter) {
        // beam splitter: three smaller lasers fanned out like a shotgun blast
        val fan = listOf(-BEAM_SPLIT_ANGLE, 0.0, BEAM_SPLIT_ANGLE)
        for (angle in fan) {
            val pellet = direction.clone().rotateAroundY(angle)
            pellet.add(Vector(
                Random.nextDouble(-BEAM_SPLIT_JITTER, BEAM_SPLIT_JITTER),
                Random.nextDouble(-BEAM_SPLIT_JITTER, BEAM_SPLIT_JITTER),
                Random.nextDouble(-BEAM_SPLIT_JITTER, BEAM_SPLIT_JITTER),
            )).normalize()

            app.spawn(
                SpiderProjectile(
                    world = spider.world,
                    position = muzzle.clone(),
                    velocity = pellet.multiply(config.projectileSpeed),
                    target = target,
                    damage = config.damage * BEAM_SPLIT_DAMAGE_FACTOR,
                    slownessDurationTicks = config.slownessDurationTicks,
                    slownessAmplifier = config.slownessAmplifier,
                    maxAgeTicks = config.projectileLifetimeTicks,
                    scale = scale * BEAM_SPLIT_SIZE_FACTOR,
                    creature = options.creature,
                    shooter = entity,
                    ignoreHitImmunity = true,
                )
            )
        }
    } else {
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
                creature = options.creature,
                shooter = entity,
            )
        )
    }

    app.emit(SpiderShootEvent(entity = entity, spider = spider, target = target))
}

/** How long the light left by a laser hit flickers: half a second. */
private const val LASER_FLICKER_TICKS = 10

/**
 * What a droid's laser leaves where it hits: a puff of fire particles and a light that flickers for half a
 * second. Nothing is set on fire and the world is not changed: the light is only shown to nearby players
 * (as a fake light block), and put back to the real block afterwards.
 */
private fun laserImpactEffect(world: World, impact: Vector, lightSpot: Vector) {
    val at = impact.toLocation(world)
    world.spawnParticle(Particle.FLAME, at, 14, 0.12, 0.12, 0.12, 0.03)
    world.spawnParticle(Particle.SMOKE, at, 4, 0.1, 0.1, 0.1, 0.01)

    // light goes in an empty block next to the hit
    val spot = listOf(lightSpot.toLocation(world).block, at.block).firstOrNull { it.type.isAir } ?: return
    val location = spot.location
    val viewers = world.players.filter { it.location.distanceSquared(location) < 64.0 * 64.0 }
    if (viewers.isEmpty()) return

    var ticks = 0
    interval(0, 2) { task ->
        if (ticks >= LASER_FLICKER_TICKS) {
            val real = spot.blockData
            for (viewer in viewers) viewer.sendBlockChange(location, real)
            task.close()
            return@interval
        }

        val light = Material.LIGHT.createBlockData() as Levelled
        light.level = Random.nextInt(8, 16)
        for (viewer in viewers) viewer.sendBlockChange(location, light)
        ticks += 2
    }
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
                if (projectile.creature == CreatureType.DROID) laserImpactEffect(projectile.world, blockCollision.position, projectile.previousPosition)
                app.emit(SpiderProjectileHitEvent(world = projectile.world, position = blockCollision.position, target = projectile.target, creature = projectile.creature))
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
                    if (projectile.ignoreHitImmunity) target.noDamageTicks = 0
                    target.damage(projectile.damage)
                    projectile.shooter?.let { provokeMob(it, target) }
                    if (projectile.creature == CreatureType.DROID) laserImpactEffect(projectile.world, projectile.position, projectile.position)
                    target.addPotionEffect(
                        PotionEffect(PotionEffectType.SLOWNESS, projectile.slownessDurationTicks, projectile.slownessAmplifier, false, true)
                    )
                    app.emit(SpiderProjectileHitEvent(world = projectile.world, position = projectile.position.clone(), target = target, creature = projectile.creature))
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
            val directionF = direction.normalize().toVector3f()

            // the droid fires glowing red lasers: a redstone block stretched along its flight path
            if (projectile.creature == CreatureType.DROID) {
                val laserOrientation = Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), directionF)
                val k = projectile.scale.toFloat()

                renderBlock(
                    location = projectile.position.toLocation(projectile.world),
                    init = {
                        it.block = Material.REDSTONE_BLOCK.createBlockData()
                        it.brightness = Display.Brightness(15, 15)
                        it.billboard = Display.Billboard.FIXED
                        it.setGravity(false)
                        it.isPersistent = false
                        it.teleportDuration = 1
                        it.interpolationDuration = 1
                    },
                    update = {
                        it.interpolateTransform(
                            Matrix4f()
                                .rotate(laserOrientation)
                                .scale(0.15f * k, 0.75f * k, 0.15f * k)
                                .translate(-0.5f, -0.5f, -0.5f)
                        )
                    }
                ).submit(entity)
                continue
            }

            val orientation = Quaternionf().rotationTo(FORWARD_VECTOR.toVector3f(), directionF)

            renderItem(
                location = projectile.position.toLocation(projectile.world),
                init = {
                    it.setItemStack(createShotHeadItem(projectile.creature.shotTexture, "${projectile.creature.name.lowercase()}_shot"))
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