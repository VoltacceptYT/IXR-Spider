package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.utilities.*
import net.browselotus.spiderbot.utilities.ecs.Component
import net.browselotus.spiderbot.utilities.ecs.ECS
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.events.addEventListener
import net.browselotus.spiderbot.utilities.events.onInteractEntity
import net.browselotus.spiderbot.utilities.maths.rotate
import net.browselotus.spiderbot.utilities.overloads.direction
import net.browselotus.spiderbot.utilities.overloads.playSound
import net.browselotus.spiderbot.utilities.overloads.position
import net.browselotus.spiderbot.utilities.overloads.yawRadians
import net.browselotus.spiderbot.utilities.rendering.RenderEntity
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Pig
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import org.joml.Quaternionf

class Mountable : Component {
    var currentMarker: ArmorStand? = null
    var currentPig: Pig? = null
    fun getRider() = currentMarker?.passengers?.firstOrNull() as? Player
}

fun setupMountable(app: ECS) {
    onInteractEntity { player, entity, hand ->
        for (mountable in app.query<Mountable>()) {
            val currentPig = mountable.currentPig ?: continue
            if (entity != currentPig) continue
            if (hand != EquipmentSlot.HAND) continue

            // if right click with saddle, add saddle (automatic)
            if (player.inventory.itemInMainHand.type == Material.SADDLE && !currentPig.hasSaddle()) {
                currentPig.world.playSound(currentPig.position, Sound.ENTITY_PIG_SADDLE, 1.0f, 1.0f)
            }

            // if right click with empty hand, remove saddle
            if (player.inventory.itemInMainHand.type.isAir && mountable.getRider() == null) {
                if (player.isSneaking) {
                    currentPig.setSaddle(false)
                }
            }
        }
    }

    // when player mounts the pig, switch them to the marker entity
    addEventListener(object : Listener {
        @EventHandler
        fun onMount(event: VehicleEnterEvent) {
            for (mountable in app.query<Mountable>()) {
                val currentPig = mountable.currentPig ?: continue
                val currentMarker = mountable.currentMarker ?: continue

                if (event.vehicle != currentPig) continue
                val player = event.entered

                event.isCancelled = true
                currentMarker.addPassenger(player)
            }
        }
    })

    // Handle user input
    @Suppress("UnstableApiUsage")
    app.onTick {
        for ((mountable, _, entity) in app.query<Mountable, SpiderBody, ECSEntity>()) {
            val player = mountable.getRider() ?: continue

            val input = Vector()
            if (player.currentInput.isLeft) input.x += 1.0
            if (player.currentInput.isRight) input.x -= 1.0
            if (player.currentInput.isForward) input.z += 1.0
            if (player.currentInput.isBackward) input.z -= 1.0

            val rotation = Quaternionf().rotationYXZ(player.yawRadians(), .0f, .0f)
            val direction = if (input.isZero) input else input.rotate(rotation).normalize()

            val behaviour = DirectionBehaviour(player.direction, direction)
            entity.replaceComponent<SpiderBehaviour>(behaviour)
        }
    }

    // Render pig and marker
    app.onRender {
        for ((spider, mountable) in app.query<SpiderBody, Mountable>()) {
            val location = spider.location().add(spider.velocity)

            val pigLocation = location.clone().add(Vector(.0, -.6, .0))
            val markerLocation = location.clone().add(Vector(.0, .3, .0))

            RenderEntity(
                clazz = Pig::class.java,
                location = pigLocation,
                init = {
                    it.setGravity(false)
                    it.setAI(false)
                    it.isInvisible = true
                    it.isInvulnerable = true
                    it.isSilent = true
                    it.isCollidable = false
                },
                update = {
                    mountable.currentPig = it
                }
            ).submit(spider to "mountable.pig")

            RenderEntity(
                clazz = ArmorStand::class.java,
                location = markerLocation,
                init = {
                    it.setGravity(false)
                    it.isInvisible = true
                    it.isInvulnerable = true
                    it.isSilent = true
                    it.isCollidable = false
                    it.isMarker = true
                },
                update = update@{
                    mountable.currentMarker = it
                    if (mountable.getRider() == null) return@update

                    // This is the only way to preserve passengers when teleporting.
                    // Paper has a TeleportFlag, but it is not supported by Spigot.
                    // https://jd.papermc.io/paper/1.21/io/papermc/paper/entity/TeleportFlag.EntityState.html
                    runCommandSilently("execute as ${it.uniqueId} at @s run tp ${markerLocation.x} ${markerLocation.y} ${markerLocation.z}")
                }
            ).submit(spider to "mountable.marker")
        }
    }
}