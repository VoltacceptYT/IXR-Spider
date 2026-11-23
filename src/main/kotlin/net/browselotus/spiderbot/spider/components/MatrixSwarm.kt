package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.SWARM_DRONE_HITS
import net.browselotus.spiderbot.spider.configuration.SWARM_SIZE
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.matrixSwarmActive
import net.browselotus.spiderbot.spider.presets.concreteBlockData
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.rendering.interpolateTransform
import net.browselotus.spiderbot.utilities.rendering.renderBlock
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Display
import org.bukkit.entity.Fireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.ShulkerBullet
import org.bukkit.entity.SmallFireball
import org.bukkit.entity.Trident
import org.bukkit.util.Vector
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ---- tuning ----

/** Ticks before a crashed drone's replacement launches (20 seconds). */
private const val RESPAWN_TICKS = 20 * 20

/** Ticks a drone is immune after a hit, so one burst of damage cannot count as two hits. */
private const val HIT_IMMUNITY_TICKS = 10

/** Ticks the unfolding "launch" animation takes. */
private const val DEPLOY_TICKS = 14

/** A drone's laser does this fraction of a normal laser's damage, and is this big compared to one. */
private const val DRONE_DAMAGE_FACTOR = 0.4
private const val DRONE_SHOT_SIZE_FACTOR = 0.45

/** Ticks between a drone's shots. */
private const val DRONE_FIRE_COOLDOWN = 30

/** Drones circle their target at this distance (blocks), and the robot at [IDLE_ORBIT_RADIUS] * scale. */
private const val COMBAT_ORBIT_RADIUS = 4.0
private const val IDLE_ORBIT_RADIUS = 1.8
private const val IDLE_HOVER_HEIGHT = 1.7

/** Longest a crash lasts before the drone blows up in the air. */
private const val MAX_CRASH_TICKS = 60

private const val MAX_SPEED = 1.0

// ---- components ----

/** Added to a robot whose Central Core carries the Matrix Swarm: which drone sits in each slot and when a lost one is replaced. */
class MatrixSwarm : Component {
    val drones = arrayOfNulls<ECSEntity>(SWARM_SIZE)
    val respawnTimers = IntArray(SWARM_SIZE) { it * 8 }
}

class SwarmDrone(
    val robot: ECSEntity,
    val options: SpiderOptions,
    val index: Int,
    val world: World,
    val position: Vector,
    val velocity: Vector,
) : Component {
    var hitsLeft = SWARM_DRONE_HITS
    var age = 0

    var immunity = 0
    var hitFlash = 0
    var fireCooldown = 20 + index * 10
    var recoil = 0f
    var stuck = 0

    // flight pose
    var yaw = 0f
    var pitch = 0f
    var roll = 0f
    var rotorAngle = 0f
    var rotorSpeed = 0f

    // crashing
    var crashing = false
    var crashAge = 0
    val tumble = Quaternionf()
    val tumbleSpin = Vector3f(
        Random.nextFloat() * 0.5f - 0.25f,
        Random.nextFloat() * 0.5f - 0.25f,
        Random.nextFloat() * 0.5f - 0.25f,
    )
}

// ---- world helpers ----

private fun World.solidAt(x: Double, y: Double, z: Double): Boolean {
    val bx = floor(x).toInt()
    val bz = floor(z).toInt()
    if (!isChunkLoaded(bx shr 4, bz shr 4)) return true
    return !getBlockAt(bx, floor(y).toInt(), bz).isPassable
}

private fun shortestAngle(from: Float, to: Float): Float {
    var d = (to - from) % (2 * PI.toFloat())
    if (d > PI) d -= 2 * PI.toFloat()
    if (d < -PI) d += 2 * PI.toFloat()
    return d
}

// ---- model ----

private class DronePiece(val key: String, val block: BlockData, val brightness: Display.Brightness?, val transform: Matrix4f)

private fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, yaw: Float = 0f) =
    Matrix4f().translate(cx, cy, cz).rotateY(yaw).scale(sx, sy, sz).translate(-0.5f, -0.5f, -0.5f)

private val FRAME_BLOCK by lazy { Material.DEEPSLATE_TILES.createBlockData() }

/**
 * The drone's custom block display model, built fresh every frame so it can animate. About half a block across at
 * robot scale 1. The shell is painted in the robot's body color, the eye, rotors and lights glow in its glow color.
 *
 * - [unfold]: 0..1, the arms and rotors swing out of the core when launched
 * - [spin]: rotor angle (radians)
 * - [wear]: 0..1, how beaten up it looks
 * - [flash]: just got hit
 * - [dead]: crashing, so every light is out
 */
private fun droneModel(drone: SwarmDrone, unfold: Float, spin: Float, wear: Float, flash: Boolean, dead: Boolean, includeRotors: Boolean): List<DronePiece> {
    val plan = drone.options.bodyPlan
    val shell = if (flash) Material.RED_CONCRETE.createBlockData() else plan.bodyColor.concreteBlockData()
    val tick = drone.age

    val glow = plan.eyePalette
    val blink = plan.blinkingPalette
    val off = Material.BLACK_CONCRETE.createBlockData() to Display.Brightness(0, 0)

    var eye = if (flash) Material.RED_SHULKER_BOX.createBlockData() to Display.Brightness(15, 15) else glow[(tick / 3 + drone.index * 2) % glow.size]
    var light = blink[(tick / 2 + drone.index * 3) % blink.size]
    var rotorGlow = glow[0]

    // damaged: the lights stutter out, like the robot's own damaged look
    if (wear > 0.4f && Random.nextFloat() < wear - 0.2f) eye = off
    if (wear > 0.4f && Random.nextFloat() < wear) light = off
    if (dead) { eye = off; light = off; rotorGlow = off }

    fun shellBlock(index: Int) = damagedLook(listOf("cloak"), index, wear)?.first ?: shell

    val armLength = 0.60f * unfold
    val reach = 0.212f * unfold
    val out = mutableListOf<DronePiece>()

    out += DronePiece("body", shellBlock(1), null, box(0f, 0f, 0f, 0.26f, 0.12f, 0.30f))
    out += DronePiece("nose", shellBlock(2), null, box(0f, -0.01f, 0.17f, 0.14f, 0.09f, 0.06f))
    out += DronePiece("dome", shellBlock(3), null, box(0f, 0.085f, -0.02f, 0.16f, 0.05f, 0.16f))
    out += DronePiece("belly", FRAME_BLOCK, null, box(0f, -0.08f, 0f, 0.20f, 0.05f, 0.22f))
    out += DronePiece("eye", eye.first, eye.second, box(0f, 0f, 0.205f, 0.10f, 0.045f, 0.02f))
    out += DronePiece("light", light.first, light.second, box(0f, -0.115f, 0.02f, 0.07f, 0.025f, 0.07f))
    out += DronePiece("antenna", FRAME_BLOCK, null, box(0.06f, 0.14f, -0.08f, 0.012f, 0.09f, 0.012f))
    out += DronePiece("antennaTip", light.first, light.second, box(0.06f, 0.195f, -0.08f, 0.03f, 0.03f, 0.03f))

    if (unfold > 0.02f) {
        out += DronePiece("armA", FRAME_BLOCK, null, box(0f, 0f, 0f, armLength, 0.035f, 0.05f, (PI / 4).toFloat()))
        out += DronePiece("armB", FRAME_BLOCK, null, box(0f, 0f, 0f, armLength, 0.035f, 0.05f, (-PI / 4).toFloat()))

        val corners = listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)
        for ((i, corner) in corners.withIndex()) {
            val px = corner.first * reach
            val pz = corner.second * reach
            out += DronePiece("pod$i", FRAME_BLOCK, null, box(px, 0.03f, pz, 0.08f * unfold, 0.06f, 0.08f * unfold))

            if (includeRotors) {
                // neighbouring rotors turn opposite ways, like a real quadcopter, and the blade is a long thin strip
                val direction = if (i == 0 || i == 3) 1f else -1f
                out += DronePiece("rotor$i", rotorGlow.first, rotorGlow.second,
                    box(px, 0.075f, pz, 0.26f * unfold, 0.012f, 0.04f, spin * direction + i * 0.7f))
            }
        }
    }

    return out
}

// ---- crashes and wrecks ----

private fun crashImpact(app: ECS, entity: ECSEntity, drone: SwarmDrone) {
    val at = drone.position.toLocation(drone.world)
    val body = drone.options.bodyPlan.bodyColor.concreteBlockData()

    drone.world.spawnParticle(Particle.EXPLOSION, at, 1)
    drone.world.spawnParticle(Particle.LARGE_SMOKE, at, 8, 0.15, 0.15, 0.15, 0.02)
    drone.world.spawnParticle(Particle.ELECTRIC_SPARK, at, 16, 0.2, 0.2, 0.2, 0.15)
    drone.world.spawnParticle(Particle.BLOCK, at, 14, 0.15, 0.15, 0.15, body)
    drone.world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.7f)

    // the wreck stays on the ground for a while: a scorched copy of the drone's frame
    val scale = drone.options.bodyPlan.scale.coerceIn(0.6, 3.0).toFloat()
    val pieces = droneModel(drone, 1f, 0f, 1f, flash = false, dead = true, includeRotors = false).map {
        DebrisPiece(it.block, it.brightness, Matrix4f().scale(scale).mul(it.transform))
    }
    app.spawn(
        DetachedPart(
            world = drone.world,
            position = drone.position.clone(),
            velocity = Vector(Random.nextDouble(-0.1, 0.1), 0.15, Random.nextDouble(-0.1, 0.1)),
            pieces = pieces,
        )
    )
    entity.remove()
}

private fun startCrash(entity: ECSEntity, drone: SwarmDrone) {
    drone.crashing = true
    drone.crashAge = 0
    drone.velocity.multiply(0.5).setY(0.12)

    // the slot opens up, and a replacement launches after a while
    drone.robot.query<MatrixSwarm>()?.let { swarm ->
        if (swarm.drones[drone.index] === entity) swarm.drones[drone.index] = null
        swarm.respawnTimers[drone.index] = RESPAWN_TICKS
    }

    drone.world.playSound(drone.position.toLocation(drone.world), Sound.ENTITY_IRON_GOLEM_DEATH, 0.5f, 1.9f)
}

/** A drone leaving without crashing: the module was taken out, the core was knocked off, or the robot is gone. */
private fun recall(entity: ECSEntity, drone: SwarmDrone) {
    val at = drone.position.toLocation(drone.world)
    drone.world.spawnParticle(Particle.POOF, at, 6, 0.1, 0.1, 0.1, 0.03)
    drone.world.playSound(at, Sound.BLOCK_PISTON_CONTRACT, 0.4f, 1.8f)
    entity.remove()
}

private fun hitDrone(entity: ECSEntity, drone: SwarmDrone, from: Vector) {
    drone.hitsLeft--
    drone.immunity = HIT_IMMUNITY_TICKS
    drone.hitFlash = 6

    val at = drone.position.toLocation(drone.world)
    drone.world.spawnParticle(Particle.ELECTRIC_SPARK, at, 10, 0.1, 0.1, 0.1, 0.08)
    drone.world.playSound(at, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.6f, 1.9f)

    val push = drone.position.clone().subtract(from)
    if (push.lengthSquared() > 1e-6) drone.velocity.add(push.normalize().multiply(0.4))

    if (drone.hitsLeft <= 0) startCrash(entity, drone)
}

// ---- systems ----

fun setupMatrixSwarm(app: ECS) {
    // launch, replace and recall the drones of every robot
    app.onTick {
        for ((entity, spider, options) in app.query<ECSEntity, SpiderBody, SpiderOptions>()) {
            if (!options.matrixSwarmActive()) {
                val swarm = entity.query<MatrixSwarm>() ?: continue
                for (i in 0 until SWARM_SIZE) {
                    swarm.drones[i]?.query<SwarmDrone>()?.let { recall(swarm.drones[i]!!, it) }
                    swarm.drones[i] = null
                }
                entity.removeComponent<MatrixSwarm>()
                continue
            }

            val swarm = entity.query<MatrixSwarm>() ?: MatrixSwarm().also { entity.addComponent(it) }
            val scale = options.bodyPlan.scale.coerceIn(0.6, 3.0)

            for (i in 0 until SWARM_SIZE) {
                val current = swarm.drones[i]
                if (current != null && !current.scheduledForRemoval) continue
                swarm.drones[i] = null

                if (swarm.respawnTimers[i] > 0) { swarm.respawnTimers[i]--; continue }

                val start = spider.position.clone().add(Vector(0.0, 0.5 * scale, 0.0))
                val drone = SwarmDrone(
                    robot = entity, options = options, index = i, world = spider.world,
                    position = start,
                    velocity = Vector(Random.nextDouble(-0.15, 0.15), 0.3, Random.nextDouble(-0.15, 0.15)),
                )
                drone.yaw = atan2(spider.forwardDirection().x, spider.forwardDirection().z).toFloat()
                swarm.drones[i] = app.spawn(drone)

                spider.world.playSound(start.toLocation(spider.world), Sound.BLOCK_PISTON_EXTEND, 0.5f, 1.8f)
                spider.world.spawnParticle(Particle.POOF, start.toLocation(spider.world), 6, 0.1, 0.1, 0.1, 0.03)
            }
        }
    }

    // flight, combat, crashing
    app.onTick {
        for ((entity, drone) in app.query<ECSEntity, SwarmDrone>()) {
            drone.age++
            if (drone.immunity > 0) drone.immunity--
            if (drone.hitFlash > 0) drone.hitFlash--
            if (drone.recoil > 0f) drone.recoil = (drone.recoil - 0.08f).coerceAtLeast(0f)

            if (drone.crashing) { tickCrash(app, entity, drone); continue }

            val robot = drone.robot
            val spider = robot.query<SpiderBody>()
            val options = robot.query<SpiderOptions>()
            if (robot.scheduledForRemoval || spider == null || options == null || spider.world != drone.world || !options.matrixSwarmActive()) {
                recall(entity, drone)
                continue
            }

            tickFlight(app, entity, drone, robot, spider, options)
            tickDamage(entity, drone, robot)
        }
    }

    app.onRender {
        for ((entity, drone) in app.query<ECSEntity, SwarmDrone>()) renderDrone(entity, drone)
    }
}

private fun tickFlight(app: ECS, entity: ECSEntity, drone: SwarmDrone, robot: ECSEntity, spider: SpiderBody, options: SpiderOptions) {
    val scale = options.bodyPlan.scale.coerceIn(0.6, 3.0)
    val config = options.attack
    val attacker = robot.query<SpiderAttacker>()
    val target = attacker?.target?.takeIf { it.isValid && !it.isDead && it.world == drone.world }
    val around = 2 * PI * drone.index / SWARM_SIZE

    // where it wants to be: circling the robot, or circling whatever the robot is fighting
    val desired = if (target != null) {
        val angle = drone.age * 0.05 + around
        target.location.toVector().add(Vector(
            cos(angle) * COMBAT_ORBIT_RADIUS, target.height + 1.0 + sin(drone.age * 0.12 + drone.index) * 0.2, sin(angle) * COMBAT_ORBIT_RADIUS,
        ))
    } else {
        val angle = drone.age * 0.05 + around
        spider.position.clone().add(Vector(
            cos(angle) * IDLE_ORBIT_RADIUS * scale, IDLE_HOVER_HEIGHT * scale + sin(drone.age * 0.1 + drone.index * 2.0) * 0.15, sin(angle) * IDLE_ORBIT_RADIUS * scale,
        ))
    }

    // spring towards the spot, with damping so it settles in instead of oscillating
    drone.velocity.add(desired.clone().subtract(drone.position).multiply(0.06)).multiply(0.88)
    val speed = drone.velocity.length()
    if (speed > MAX_SPEED) drone.velocity.multiply(MAX_SPEED / speed)

    // never fly through walls: bounce off, and if it somehow ends up inside one, return it to the robot
    val next = drone.position.clone().add(drone.velocity)
    if (drone.world.solidAt(next.x, next.y, next.z)) {
        drone.velocity.multiply(-0.3).setY(drone.velocity.y.coerceAtLeast(0.0) + 0.05)
    } else {
        drone.position.add(drone.velocity)
    }
    if (drone.world.solidAt(drone.position.x, drone.position.y, drone.position.z)) {
        if (++drone.stuck > 10) {
            drone.position.copy(spider.position.clone().add(Vector(0.0, 1.0 * scale, 0.0)))
            drone.velocity.zero()
            drone.stuck = 0
        }
    } else drone.stuck = 0

    // face the target, or where it is flying
    val facing = if (target != null) target.location.toVector().subtract(drone.position)
    else Vector(drone.velocity.x, 0.0, drone.velocity.z).takeIf { it.lengthSquared() > 0.004 } ?: spider.forwardDirection()
    val targetYaw = atan2(facing.x, facing.z).toFloat()
    drone.yaw += shortestAngle(drone.yaw, targetYaw) * 0.25f

    // lean into the flight direction, like a quadcopter does
    val local = Vector3f(drone.velocity.x.toFloat(), 0f, drone.velocity.z.toFloat()).rotateY(-drone.yaw)
    drone.pitch += (local.z * 1.3f - drone.pitch).coerceIn(-0.5f, 0.5f) * 0.2f
    drone.roll += (-local.x * 1.3f - drone.roll).coerceIn(-0.5f, 0.5f) * 0.2f

    // rotors spin up as it launches, and wobble when it is damaged
    val damaged = drone.hitsLeft < SWARM_DRONE_HITS
    drone.rotorSpeed += (0.9f - drone.rotorSpeed) * 0.15f
    drone.rotorAngle += drone.rotorSpeed * (if (damaged) 0.6f + Random.nextFloat() * 0.5f else 1f)

    if (damaged && drone.age % 4 == 0) {
        val at = drone.position.toLocation(drone.world)
        drone.world.spawnParticle(Particle.SMOKE, at, 2, 0.05, 0.05, 0.05, 0.01)
        if (Random.nextFloat() < 0.4f) drone.world.spawnParticle(Particle.ELECTRIC_SPARK, at, 3, 0.08, 0.08, 0.08, 0.05)
    }

    // fire on the robot's target
    if (drone.fireCooldown > 0) drone.fireCooldown--
    if (target == null || drone.fireCooldown > 0 || drone.age < DEPLOY_TICKS) return

    val muzzle = drone.position.clone().add(Vector(0.0, -0.05, 0.0))
    val aim = target.eyeLocation.toVector().subtract(muzzle)
    val distance = aim.length()
    if (distance < 1e-3 || distance > config.attackRange) return
    aim.normalize()
    if (drone.world.rayTraceBlocks(muzzle.toLocation(drone.world), aim, distance, FluidCollisionMode.NEVER, true) != null) return

    app.spawn(
        SpiderProjectile(
            world = drone.world,
            position = muzzle,
            velocity = aim.multiply(config.projectileSpeed),
            target = target,
            damage = config.damage * DRONE_DAMAGE_FACTOR,
            slownessDurationTicks = config.slownessDurationTicks,
            slownessAmplifier = config.slownessAmplifier,
            maxAgeTicks = config.projectileLifetimeTicks,
            scale = options.bodyPlan.scale * DRONE_SHOT_SIZE_FACTOR,
            creature = options.creature,
            shooter = robot,
            ignoreHitImmunity = true,
        )
    )

    drone.fireCooldown = DRONE_FIRE_COOLDOWN + Random.nextInt(0, 10)
    drone.recoil = 1f
    drone.world.playSound(muzzle.toLocation(drone.world), options.creature.shootSound, 0.25f, options.creature.shootPitch + 0.4f)
}

/** What counts as a hit on a drone: the same hostile projectiles that hurt the robot, plus monsters that reach it. */
private fun tickDamage(entity: ECSEntity, drone: SwarmDrone, robot: ECSEntity) {
    if (drone.immunity > 0 || drone.crashing) return

    val ownerId = robot.query<Owner>()?.playerUUID
    val rider = robot.query<Mountable>()?.getRider()
    val at = drone.position.toLocation(drone.world)

    for (nearby in drone.world.getNearbyEntities(at, 1.0, 1.0, 1.0)) {
        if (!nearby.boundingBox.clone().expand(0.3).contains(drone.position.x, drone.position.y, drone.position.z)) continue

        when (nearby) {
            is Trident -> {
                val shooter = nearby.shooter
                if (shooter is Player && (shooter.uniqueId == ownerId || shooter == rider)) continue
                if (nearby.velocity.length() < 0.8) continue
                nearby.velocity = nearby.velocity.normalize().multiply(-0.3)
                (shooter as? LivingEntity)?.let { provokeRobot(robot, it) }
                hitDrone(entity, drone, nearby.location.toVector())
                return
            }

            is SmallFireball, is Fireball, is AbstractArrow, is ShulkerBullet -> {
                val shooter = (nearby as Projectile).shooter
                if (shooter is Player && (shooter.uniqueId == ownerId || shooter == rider)) continue
                if (nearby.velocity.length() < 0.8) continue
                (shooter as? LivingEntity)?.let { provokeRobot(robot, it) }
                nearby.remove()
                hitDrone(entity, drone, nearby.location.toVector())
                return
            }

            is Monster -> {
                provokeRobot(robot, nearby)
                hitDrone(entity, drone, nearby.location.toVector())
                return
            }
        }
    }
}

private fun tickCrash(app: ECS, entity: ECSEntity, drone: SwarmDrone) {
    drone.crashAge++

    drone.velocity.y -= 0.04
    drone.velocity.x *= 0.98
    drone.velocity.z *= 0.98
    drone.position.add(drone.velocity)

    drone.tumble.rotateXYZ(drone.tumbleSpin.x, drone.tumbleSpin.y, drone.tumbleSpin.z)
    drone.rotorSpeed *= 0.96f
    drone.rotorAngle += drone.rotorSpeed

    val at = drone.position.toLocation(drone.world)
    if (drone.crashAge % 2 == 0) {
        drone.world.spawnParticle(Particle.LARGE_SMOKE, at, 1, 0.05, 0.05, 0.05, 0.01)
        drone.world.spawnParticle(Particle.FLAME, at, 1, 0.05, 0.05, 0.05, 0.01)
    }
    if (drone.crashAge % 3 == 0) drone.world.spawnParticle(Particle.ELECTRIC_SPARK, at, 3, 0.1, 0.1, 0.1, 0.05)

    val hitGround = drone.velocity.y < 0.0 && drone.world.solidAt(drone.position.x, drone.position.y - 0.15, drone.position.z)
    if (hitGround || drone.world.solidAt(drone.position.x, drone.position.y, drone.position.z) || drone.crashAge >= MAX_CRASH_TICKS) {
        crashImpact(app, entity, drone)
    }
}

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

private fun renderDrone(entity: ECSEntity, drone: SwarmDrone) {
    val unfold = easeOutBack((drone.age / DEPLOY_TICKS.toFloat()).coerceIn(0f, 1f)).coerceAtLeast(0f)
    val wear = when {
        drone.crashing -> 1f
        drone.hitsLeft < SWARM_DRONE_HITS -> 0.6f
        else -> 0f
    }

    val scale = drone.options.bodyPlan.scale.coerceIn(0.6, 3.0).toFloat() * (0.4f + 0.6f * unfold.coerceAtMost(1f))
    val pieces = droneModel(
        drone, unfold, drone.rotorAngle, wear,
        flash = drone.hitFlash > 0, dead = drone.crashing, includeRotors = true,
    )

    // crashing drones tumble; flying ones lean into their movement, and kick back when they fire
    val orientation = if (drone.crashing) Quaternionf(drone.tumble)
    else Quaternionf().rotateY(drone.yaw).rotateX(drone.pitch - drone.recoil * 0.25f).rotateZ(drone.roll)

    val location = drone.position.toLocation(drone.world)
    for (piece in pieces) {
        renderBlock(
            location = location,
            init = {
                it.block = piece.block
                it.brightness = piece.brightness
                it.setGravity(false)
                it.isPersistent = false
                it.teleportDuration = 1
                it.interpolationDuration = 1
            },
            update = {
                it.block = piece.block
                it.brightness = piece.brightness
                it.interpolateTransform(Matrix4f().rotate(orientation).scale(scale).mul(piece.transform))
            }
        ).submit(entity to piece.key)
    }
}
