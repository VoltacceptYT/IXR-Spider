package com.heledron.spideranimation.spider.gui

import com.heledron.spideranimation.AppState
import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.utilities.events.addEventListener
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.DyeColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

private const val GUI_TITLE = "Customize Your Robo-Spider"
private const val INVENTORY_SIZE = 54

private const val SCALE_STEP = 0.25
private const val SCALE_MIN = 0.25
private const val SCALE_MAX = 3.0

// Slot layout (6 rows of 9). Rows are 0-8, 9-17, 18-26, 27-35, 36-44, 45-53.
private const val SLOT_TITLE = 4
private const val SLOT_SCALE_DOWN = 10
private const val SLOT_SCALE_DISPLAY = 13
private const val SLOT_SCALE_UP = 16
private val GLOW_SLOTS = ((18..26) + (27..33)) // 16 slots, one per dye color
private const val SLOT_GLOW_LABEL = 34
private val BODY_SLOTS = ((36..44) + (45..51)) // 16 slots, one per dye color
private const val SLOT_BODY_LABEL = 52
private const val SLOT_CLOSE = 53

// Bukkit's DyeColor enum happens to be declared in a sensible display order already.
private val orderedDyeColors = DyeColor.entries.toList()

/** Marker holder so the click handler can recognize this GUI without matching on title text. */
private class CustomizationHolder : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory(): Inventory = backing
}

fun setupSpiderCustomizationGUI() {
    addEventListener(object : Listener {
        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            if (event.inventory.holder !is CustomizationHolder) return
            event.isCancelled = true

            val slot = event.rawSlot
            if (slot < 0 || slot >= event.inventory.size) return // clicked their own inventory, not the GUI

            val player = event.whoClicked as? Player ?: return
            val entity = AppState.findSpiderByOwner(player.uniqueId) ?: return
            val spider = entity.query<SpiderBody>() ?: return
            val options = entity.query<SpiderOptions>() ?: return

            when {
                slot == SLOT_SCALE_DOWN -> adjustScale(spider, options, -SCALE_STEP)
                slot == SLOT_SCALE_UP -> adjustScale(spider, options, SCALE_STEP)
                slot == SLOT_CLOSE -> { player.closeInventory(); return }
                slot in GLOW_SLOTS -> options.bodyPlan.setGlowColor(orderedDyeColors[GLOW_SLOTS.indexOf(slot)])
                slot in BODY_SLOTS -> options.bodyPlan.setBodyColor(orderedDyeColors[BODY_SLOTS.indexOf(slot)])
                else -> return
            }

            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
            refresh(event.inventory, options)
        }
    })
}

fun openSpiderCustomizationGUI(player: Player) {
    val entity = AppState.findSpiderByOwner(player.uniqueId)
    if (entity == null) {
        player.sendMessage(Component.text("You don't have a robo-spider yet.", NamedTextColor.RED))
        return
    }
    val options = entity.query<SpiderOptions>() ?: return

    val holder = CustomizationHolder()
    val inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, Component.text(GUI_TITLE))
    holder.backing = inventory

    populate(inventory, options)
    player.openInventory(inventory)
}

private fun adjustScale(spider: SpiderBody, options: SpiderOptions, delta: Double) {
    val oldScale = options.bodyPlan.scale
    val newScale = (oldScale + delta).coerceIn(SCALE_MIN, SCALE_MAX)
    if (newScale == oldScale) return

    val factor = newScale / oldScale
    options.walkGait.scale(factor)
    options.gallopGait.scale(factor)
    options.bodyPlan.scale(factor)
    spider.updateBodyPlan()
}

private fun populate(inventory: Inventory, options: SpiderOptions) {
    val filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ")
    for (i in 0 until inventory.size) inventory.setItem(i, filler)

    inventory.setItem(SLOT_TITLE, namedItem(Material.SPIDER_SPAWN_EGG, GUI_TITLE, NamedTextColor.AQUA))
    inventory.setItem(SLOT_SCALE_DOWN, namedItem(Material.RED_DYE, "- Decrease Scale", NamedTextColor.RED))
    inventory.setItem(SLOT_SCALE_UP, namedItem(Material.LIME_DYE, "+ Increase Scale", NamedTextColor.GREEN))
    inventory.setItem(SLOT_CLOSE, namedItem(Material.BARRIER, "Close", NamedTextColor.RED))
    inventory.setItem(SLOT_GLOW_LABEL, namedItem(Material.ENDER_EYE, "Eye / Glow Color", NamedTextColor.AQUA))
    inventory.setItem(SLOT_BODY_LABEL, namedItem(Material.PAINTING, "Body Color", NamedTextColor.GOLD))

    refresh(inventory, options)
}

private fun refresh(inventory: Inventory, options: SpiderOptions) {
    inventory.setItem(SLOT_SCALE_DISPLAY, createScaleDisplay(options.bodyPlan.scale))

    for ((index, dye) in orderedDyeColors.withIndex()) {
        inventory.setItem(GLOW_SLOTS[index], createColorButton(dye, "eye/glow color", options.bodyPlan.glowColor == dye))
        inventory.setItem(BODY_SLOTS[index], createColorButton(dye, "body color", options.bodyPlan.bodyColor == dye))
    }
}

private fun createScaleDisplay(scale: Double): ItemStack {
    val item = ItemStack(Material.NETHER_STAR)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text("Scale: ${"%.2f".format(scale)}x", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(Component.text("Click the arrows to resize", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
    item.itemMeta = meta
    return item
}

private fun createColorButton(dye: DyeColor, purpose: String, selected: Boolean): ItemStack {
    val dyeMaterial = Material.matchMaterial("${dye.name}_DYE") ?: Material.WHITE_DYE
    val item = ItemStack(dyeMaterial)
    val meta = item.itemMeta ?: return item

    val displayName = dye.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    val prefix = if (selected) "* " else ""
    val color = if (selected) NamedTextColor.GREEN else NamedTextColor.WHITE

    meta.itemName(Component.text("$prefix$displayName", color).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(Component.text("Set as $purpose", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
    item.itemMeta = meta
    return item
}

private fun namedItem(material: Material, name: String, color: NamedTextColor = NamedTextColor.WHITE): ItemStack {
    val item = ItemStack(material)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))
    item.itemMeta = meta
    return item
}
