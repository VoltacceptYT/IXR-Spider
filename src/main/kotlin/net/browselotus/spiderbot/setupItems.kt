package net.browselotus.spiderbot

import net.browselotus.spiderbot.AppState.ecs
import net.browselotus.spiderbot.kinematic_chain_visualizer.KinematicChainVisualizer
import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.components.Cloak
import net.browselotus.spiderbot.spider.components.PointDetector
import net.browselotus.spiderbot.spider.components.rendering.SpiderRenderer
import net.browselotus.spiderbot.spider.configuration.CreatureType
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.presets.droid
import net.browselotus.spiderbot.spider.presets.hexBot
import net.browselotus.spiderbot.spider.gui.openSpiderCustomizationGUI
import net.browselotus.spiderbot.laser.LaserPoint
import net.browselotus.spiderbot.utilities.Serializer
import net.browselotus.spiderbot.utilities.custom_items.CustomItemComponent
import net.browselotus.spiderbot.utilities.custom_items.attach
import net.browselotus.spiderbot.utilities.custom_items.createNamedItem
import net.browselotus.spiderbot.utilities.custom_items.customItemRegistry
import net.browselotus.spiderbot.utilities.currentPlugin
import net.browselotus.spiderbot.utilities.ecs.ECSEntity
import net.browselotus.spiderbot.utilities.events.onTick
import net.browselotus.spiderbot.utilities.namespacedID
import net.browselotus.spiderbot.utilities.setSpiderbotModel
import net.browselotus.spiderbot.utilities.raycastGround
import net.browselotus.spiderbot.utilities.overloads.direction
import net.browselotus.spiderbot.utilities.overloads.eyePosition
import net.browselotus.spiderbot.utilities.overloads.playSound
import net.browselotus.spiderbot.utilities.overloads.position
import net.browselotus.spiderbot.utilities.persistence.UUIDDataType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.roundToInt


fun setupItems() {
    val configuratorComponent = CustomItemComponent("mk9Configurator")
    customItemRegistry += { createConfiguratorItem().attach(configuratorComponent) }
    configuratorComponent.lockToOffHand()
    configuratorComponent.onGestureUse { player, _ ->
        openSpiderCustomizationGUI(player)
    }
}

private fun createConfiguratorItem(): ItemStack {
    val item = ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
    val meta = item.itemMeta ?: return item

    meta.setSpiderbotModel("mk9_configurator")
    meta.itemName(rainbowText("Mk9 Configurator"))
    meta.lore(listOf(
        Component.text("Right Click to customize your Mk9 Spider", NamedTextColor.GRAY)
            .decoration(TextDecoration.BOLD, false)
            .decoration(TextDecoration.ITALIC, true)
    ))

    item.itemMeta = meta
    return item
}

/** Builds a bold, non-italic name where each non-space character steps through the color wheel. */
private fun rainbowText(text: String): Component {
    var component = Component.empty()
    val stepCount = text.count { it != ' ' }.coerceAtLeast(1)
    var colorIndex = 0

    for (char in text) {
        val piece = if (char == ' ') {
            Component.text(" ")
        } else {
            val hue = colorIndex.toFloat() / stepCount
            val rgb = java.awt.Color.HSBtoRGB(hue, 1.0f, 1.0f) and 0xFFFFFF
            colorIndex++
            Component.text(char.toString(), TextColor.color(rgb))
        }

        component = component.append(piece.decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
    }

    return component
}
