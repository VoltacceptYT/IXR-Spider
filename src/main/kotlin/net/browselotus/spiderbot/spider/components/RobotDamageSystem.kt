package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.DroidRig
import net.browselotus.spiderbot.spider.configuration.PartRole
import net.browselotus.spiderbot.spider.configuration.PartSpec
import net.browselotus.spiderbot.spider.configuration.PartStatus
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.capabilities
import net.browselotus.spiderbot.spider.configuration.partSpecs
import net.browselotus.spiderbot.spider.configuration.statusOf
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.maths.DOWN_VECTOR
import net.browselotus.spiderbot.utilities.raycastGround
import net.browselotus.spiderbot.utilities.rendering.interpolateTransform
import net.browselotus.spiderbot.utilities.rendering.renderBlock
import net.kyori.adventure.text.Component as Text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Display
import org.bukkit.entity.Fireball
import org.bukkit.entity.SmallFireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.ShulkerBullet
import org.bukkit.entity.Trident
import org.bukkit.util.Vector
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.ceil
import kotlin.random.Random

/** Vanilla damage of a thrown trident. */
const val TRIDENT_DAMAGE = 8.0

/** Every robot part has 5 hearts. */
const val PART_MAX_HP = 10.0

private const val DIAMOND_ARMOR_POINTS = 20.0 // full diamond set: 3 + 6 + 8 + 3
private const val DIAMOND_TOUGHNESS = 8.0     // 2 per piece

/** Vanilla armor formula: the damage [rawDamage] deals to something wearing a full set of diamond armor. */
fun damageAgainstFullDiamond(rawDamage: Double): Double {
    val reduction = minOf(
        20.0,
        maxOf(DIAMOND_ARMOR_POINTS / 5.0, DIAMOND_ARMOR_POINTS - rawDamage / (2.0 + DIAMOND_TOUGHNESS / 4.0)),
    ) / 25.0
    return rawDamage * (1.0 - reduction)
}

/** Vanilla difficulty scaling for damage dealt by mobs: none on peaceful, softer on easy, harder on hard. */
fun difficultyScaled(damage: Double, world: World): Double = when (world.difficulty) {
    Difficulty.PEACEFUL -> 0.0
    Difficulty.EASY -> minOf(damage / 2.0 + 1.0, damage)
    Difficulty.NORMAL -> damage
    Difficulty.HARD -> damage * 1.5
}

/**
 * Hurts the robot part nearest [point] as if it were a player with 5 hearts in full diamond armor taking
 * [rawDamage] (vanilla health points, before armor). The damage is scaled by the difficulty of the robot's
 * world, like vanilla does for mob damage; pass [scaleWithDifficulty] = false for player-sourced damage.
 */
fun hurtRobot(app: ECS, entity: ECSEntity, point: Vector, rawDamage: Double, scaleWithDifficulty: Boolean = true) {
    val world = entity.query<SpiderBody>()?.world ?: return
    val scaled = if (scaleWithDifficulty) difficultyScaled(rawDamage, world) else rawDamage
    if (scaled <= 0.0) return
    val options = entity.query<SpiderOptions>() ?: return
    damageRobot(app, entity, point, damageAgainstFullDiamond(scaled) / maxPartHp(options))
}

/** Hit points of each component: the base [PART_MAX_HP] plus whatever the equipped plating adds. */
fun maxPartHp(options: SpiderOptions): Double = PART_MAX_HP + options.upgrades.bonusHp

/** Self-healing nanoparticles mend every component by this many hit points per second. */
const val NANO_HEAL_HP_PER_SECOND = 0.5

class RobotDamageState : Component {
    val lastStatus = mutableMapOf<String, PartStatus>()
    val disabledByDamage = mutableSetOf<String>()
    var tick = 0
}

class DebrisPiece(val block: BlockData, val brightness: Display.Brightness?, val transform: Matrix4f)

class DetachedPart(
    val world: World,
    val position: Vector,
    val velocity: Vector,
    val pieces: List<DebrisPiece>,
) : Component {
    val rotation = Quaternionf()
    val spin = Vector3f(
        Random.nextFloat() * 0.3f - 0.15f,
        Random.nextFloat() * 0.3f - 0.15f,
        Random.nextFloat() * 0.3f - 0.15f,
    )
    var age = 0
    var resting = false
}

fun damagedLook(tags: List<String>, index: Int, wear: Float): Pair<BlockData, Display.Brightness?>? {
    if (tags.contains("cloak")) {
        val noise = ((index * 2654435761L ushr 4) and 0xFF).toFloat() / 255f
        val w = wear * 0.8f
        val material = when {
            noise < w * 0.5f -> Material.GRAY_CONCRETE
            noise < w -> Material.BLACK_CONCRETE
            wear > 0.6f && noise > 0.92f -> Material.EXPOSED_COPPER
            else -> return null
        }
        return material.createBlockData() to null
    }

    if (wear > 0.4f && (tags.contains("eye") || tags.contains("blinking_lights"))) {
        if (Random.nextFloat() < wear - 0.3f) return Material.BLACK_CONCRETE.createBlockData() to Display.Brightness(0, 0)
    }

    return null
}

fun partAnchor(spider: SpiderBody, options: SpiderOptions, spec: PartSpec): Vector {
    val rig = options.bodyPlan.rig
    val s = options.bodyPlan.scale.toFloat()

    fun rigPoint(partName: String, x: Float, y: Float, z: Float): Vector {
        if (rig == null) return spider.position.clone()
        rig.updateChains()
        val point = Vector3f(x * s, y * s, z * s)
        rig.chain(partName).transformPosition(point)
        point.rotate(spider.orientation)
        return spider.position.clone().add(Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble()))
    }

    return when (spec.role) {
        PartRole.CORE -> if (rig != null) rigPoint(DroidRig.CHEST, 0f, 0.4f, 0f) else spider.position.clone()
        PartRole.HEAD -> rigPoint(DroidRig.HEAD, 0f, 0.85f, 0f)
        PartRole.CANNON_ARM -> rigPoint(DroidRig.UPPER_L, 0.35f, 0.3f, 0f)
        PartRole.GUARD_ARM -> rigPoint(DroidRig.UPPER_R, -0.35f, 0.3f, 0f)
        PartRole.LEG -> {
            val leg = spider.legs.getOrNull(spec.legIndex)
            if (leg == null) spider.position.clone()
            else leg.attachmentPosition.clone().add(leg.endEffector).multiply(0.5)
        }
    }
}

private fun capturePart(spider: SpiderBody, options: SpiderOptions, spec: PartSpec, origin: Vector): List<DebrisPiece> {
    val out = mutableListOf<DebrisPiece>()
    val rig = options.bodyPlan.rig
    val offset = Vector3f(
        (spider.position.x - origin.x).toFloat(),
        (spider.position.y - origin.y).toFloat(),
        (spider.position.z - origin.z).toFloat(),
    )

    fun addRig(name: String) {
        val currentRig = rig ?: return
        val part = currentRig.part(name) ?: return
        val matrix = Matrix4f().translate(offset).rotate(spider.orientation).mul(currentRig.chain(name))
        for (piece in part.model.pieces) {
            out += DebrisPiece(piece.block, piece.brightness, Matrix4f(matrix).mul(piece.transform))
        }
    }

    rig?.updateChains()

    when (spec.role) {
        PartRole.HEAD -> addRig(DroidRig.HEAD)
        PartRole.CANNON_ARM -> { addRig(DroidRig.UPPER_L); addRig(DroidRig.FORE_L) }
        PartRole.GUARD_ARM -> { addRig(DroidRig.UPPER_R); addRig(DroidRig.FORE_R) }
        PartRole.LEG -> {
            val leg = spider.legs.getOrNull(spec.legIndex) ?: return out
            val legPlan = options.bodyPlan.legs.getOrNull(spec.legIndex) ?: return out
            val chain = leg.chain
            val rotations = if (legPlan.bendDirection != null) {
                chain.getRotationsFacing(spider.forwardDirection().toVector3f())
            } else {
                chain.getRotations(options.gait.legChainPivotMode.get(spider))
            }

            for ((segmentIndex, rotation) in rotations.withIndex()) {
                val segmentPlan = legPlan.segments.getOrNull(segmentIndex) ?: continue
                val parent = chain.segments.getOrNull(segmentIndex - 1)?.position ?: chain.root
                val matrix = Matrix4f()
                    .translate(
                        (parent.x - origin.x).toFloat(),
                        (parent.y - origin.y).toFloat(),
                        (parent.z - origin.z).toFloat(),
                    )
                    .rotate(rotation)
                for (piece in segmentPlan.model.pieces) {
                    out += DebrisPiece(piece.block, piece.brightness, Matrix4f(matrix).mul(piece.transform))
                }
            }
        }
        PartRole.CORE -> {}
    }

    return out
}

private fun notifyOwner(entity: ECSEntity, message: String, color: NamedTextColor) {
    val owner = entity.query<Owner>()?.let { Bukkit.getPlayer(it.playerUUID) } ?: return
    owner.sendActionBar(Text.text(message, color))
}

fun damageRobot(app: ECS, entity: ECSEntity, point: Vector, amount: Double) {
    val spider = entity.query<SpiderBody>() ?: return
    val options = entity.query<SpiderOptions>() ?: return

    val spec = options.partSpecs()
        .filter { options.damage.of(it.id) > 0.0 }
        .minByOrNull { partAnchor(spider, options, it).distanceSquared(point) } ?: return

    val at = point.toLocation(spider.world)
    spider.world.spawnParticle(Particle.ELECTRIC_SPARK, at, 12, 0.1, 0.1, 0.1, 0.08)
    spider.world.playSound(at, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.8f, 1.2f)

    val after = (options.damage.of(spec.id) - amount).coerceAtLeast(0.0)
    options.damage.set(spec.id, after)

    if (after <= 0.0 && spec.detachable) {
        val origin = partAnchor(spider, options, spec)
        val pieces = capturePart(spider, options, spec, origin)
        if (pieces.isNotEmpty()) {
            app.spawn(
                DetachedPart(
                    world = spider.world,
                    position = origin.clone(),
                    velocity = Vector(Random.nextDouble(-0.15, 0.15), 0.25, Random.nextDouble(-0.15, 0.15)),
                    pieces = pieces,
                )
            )
        }
        spider.world.playSound(at, Sound.BLOCK_ANVIL_BREAK, 0.8f, 1.4f)
        spider.world.spawnParticle(Particle.LARGE_SMOKE, origin.toLocation(spider.world), 10, 0.15, 0.15, 0.15, 0.02)
    }
}

fun setupRobotDamage(app: ECS) {
    app.onTick {
        for ((entity, spider, options) in app.query<ECSEntity, SpiderBody, SpiderOptions>()) {
            val ownerId = entity.query<Owner>()?.playerUUID
            val rider = entity.query<Mountable>()?.getRider()

            for (nearby in spider.world.getNearbyEntities(spider.location(), 1.4, 1.6, 1.4)) {
                val amount = when (nearby) {
                    is Trident -> continue
                    is SmallFireball -> 5.0   // blaze fireball, vanilla direct hit
                    is Fireball -> 6.0        // ghast fireball, vanilla direct hit
                    is AbstractArrow -> ceil(nearby.velocity.length() * nearby.damage).coerceAtLeast(1.0)
                    is ShulkerBullet -> 4.0
                    else -> continue
                }

                val shooter = (nearby as Projectile).shooter
                if (shooter is Player && (shooter.uniqueId == ownerId || shooter == rider)) continue
                if (nearby.velocity.length() < 0.8) continue

                hurtRobot(app, entity, nearby.location.toVector(), amount)
                (shooter as? LivingEntity)?.let { provokeRobot(entity, it) }
                nearby.remove()
            }
        }
    }

    // self-healing nanoparticles: 0.5 hit points per second on every component that is damaged but not knocked off
    app.onTick {
        for (options in app.query<SpiderOptions>()) {
            if (!options.upgrades.selfHealing) continue
            val perTick = NANO_HEAL_HP_PER_SECOND / 20.0 / maxPartHp(options)
            for (spec in options.partSpecs()) {
                val health = options.damage.of(spec.id)
                if (health <= 0.0 || health >= 1.0) continue
                options.damage.set(spec.id, (health + perTick).coerceAtMost(1.0))
            }
        }
    }

    app.onTick {
        for ((entity, spider, options, state) in app.query<ECSEntity, SpiderBody, SpiderOptions, RobotDamageState>()) {
            state.tick++
            val damage = options.damage
            val specs = options.partSpecs()

            for (spec in specs) {
                val health = damage.of(spec.id)
                val status = statusOf(health)

                val previous = state.lastStatus.put(spec.id, status)
                if (previous != null && status.ordinal > previous.ordinal) {
                    val message = when (status) {
                        PartStatus.FAILED -> if (spec.detachable) "${spec.label} knocked off!" else "${spec.label} failed, robot offline!"
                        PartStatus.CRITICAL -> "${spec.label} critical!"
                        else -> "${spec.label} damaged"
                    }
                    notifyOwner(entity, message, status.color)
                }

                if (spec.role == PartRole.LEG) {
                    val leg = spider.legs.getOrNull(spec.legIndex)
                    if (leg != null) {
                        if (health <= 0.0) {
                            leg.isDisabled = true
                            state.disabledByDamage += spec.id
                        } else if (state.disabledByDamage.remove(spec.id)) {
                            leg.isDisabled = false
                        }
                    }
                }

                if (health >= 0.6) continue
                val period = if (health <= 0.0) 6 else if (health < 0.3) 4 else 10
                if (state.tick % period != 0) continue

                val at = partAnchor(spider, options, spec).toLocation(spider.world)
                spider.world.spawnParticle(Particle.SMOKE, at, 2, 0.08, 0.08, 0.08, 0.01)
                if (health < 0.3) spider.world.spawnParticle(Particle.ELECTRIC_SPARK, at, 3, 0.1, 0.1, 0.1, 0.02)
                if (health <= 0.0 && !spec.detachable) spider.world.spawnParticle(Particle.LARGE_SMOKE, at, 2, 0.1, 0.1, 0.1, 0.01)
            }

            if (!options.capabilities().online) {
                entity.replaceComponent<SpiderBehaviour>(StayStillBehaviour())
                entity.query<SpiderAttacker>()?.target = null
            }
        }
    }

    app.onTick {
        for ((entity, part) in app.query<ECSEntity, DetachedPart>()) {
            part.age++
            if (part.age > 600) {
                entity.remove()
                continue
            }
            if (part.resting) continue

            part.velocity.y -= 0.05
            part.velocity.multiply(0.98)
            part.position.add(part.velocity)
            part.rotation.rotateXYZ(part.spin.x, part.spin.y, part.spin.z)

            if (part.velocity.y < 0.0) {
                val ground = part.world.raycastGround(part.position, DOWN_VECTOR, 0.2 - part.velocity.y)
                if (ground != null) {
                    part.position.y = ground.hitPosition.y + 0.1
                    part.resting = true
                }
            }
        }
    }

    app.onRender {
        for ((entity, part) in app.query<ECSEntity, DetachedPart>()) {
            for ((index, piece) in part.pieces.withIndex()) {
                renderBlock(
                    location = part.position.toLocation(part.world),
                    init = {
                        it.block = piece.block
                        it.brightness = piece.brightness
                        it.setGravity(false)
                        it.isPersistent = false
                        it.teleportDuration = 1
                        it.interpolationDuration = 1
                    },
                    update = {
                        it.interpolateTransform(Matrix4f().rotate(part.rotation).mul(piece.transform))
                    }
                ).submit(entity to index)
            }
        }
    }
}
