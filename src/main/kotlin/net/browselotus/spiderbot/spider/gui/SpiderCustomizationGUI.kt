package net.browselotus.spiderbot.spider.gui

import net.browselotus.spiderbot.AppState
import net.browselotus.spiderbot.spider.components.body.SpiderBody
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.saveSpiderOptions
import net.browselotus.spiderbot.utilities.events.addEventListener
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

private const val MAIN_TITLE = "Customize Your Robo-Spider"
private const val BODY_COLOR_TITLE = "Body Color"
private const val GLOW_COLOR_TITLE = "Eye / Glow Color"
private const val INVENTORY_SIZE = 54

private const val SCALE_STEP = 0.25
private const val SCALE_MIN = 0.25
private const val SCALE_MAX = 3.0

/** Which page of the customization menu an open inventory represents. */
private enum class Menu { MAIN, BODY_COLOR, GLOW_COLOR }

// ----- Main menu slot layout (6 rows of 9). Rows are 0-8, 9-17, 18-26, 27-35, 36-44, 45-53. -----
private const val SLOT_TITLE = 4
private const val SLOT_SCALE_DOWN = 10
private const val SLOT_SCALE_DISPLAY = 13
private const val SLOT_SCALE_UP = 16
private const val SLOT_BODY_COLOR_BUTTON = 30
private const val SLOT_GLOW_COLOR_BUTTON = 32
private const val SLOT_CLOSE = 49

// ----- Color submenu slot layout -----
private const val SLOT_SUB_BACK = 0
private const val SLOT_SUB_TITLE = 4
private const val SLOT_SUB_CLOSE = 49
private val COLOR_SLOTS = ((18..26) + (27..33)) // 16 slots, one per dye color

// Bukkit's DyeColor enum happens to be declared in a sensible display order already.
private val orderedDyeColors = DyeColor.entries.toList()

/** Marker holder so the click handler can recognize this GUI, and which page it's on, without matching on title text. */
private class CustomizationHolder(val menu: Menu) : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory(): Inventory = backing
}

fun setupSpiderCustomizationGUI() {
    addEventListener(object : Listener {
        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            val holder = event.inventory.holder as? CustomizationHolder ?: return
            event.isCancelled = true

            val slot = event.rawSlot
            if (slot < 0 || slot >= event.inventory.size) return // clicked their own inventory, not the GUI

            val player = event.whoClicked as? Player ?: return
            val entity = AppState.findSpiderByOwner(player.uniqueId) ?: return
            val spider = entity.query<SpiderBody>() ?: return
            val options = entity.query<SpiderOptions>() ?: return

            when (holder.menu) {
                Menu.MAIN -> onMainMenuClick(player, slot, spider, options, event.inventory)
                Menu.BODY_COLOR -> onColorMenuClick(player, slot, options, event.inventory, isBody = true)
                Menu.GLOW_COLOR -> onColorMenuClick(player, slot, options, event.inventory, isBody = false)
            }
        }
    })
}

private fun onMainMenuClick(player: Player, slot: Int, spider: SpiderBody, options: SpiderOptions, inventory: Inventory) {
    when (slot) {
        SLOT_SCALE_DOWN -> adjustScale(spider, options, -SCALE_STEP)
        SLOT_SCALE_UP -> adjustScale(spider, options, SCALE_STEP)
        SLOT_CLOSE -> { player.closeInventory(); return }
        SLOT_BODY_COLOR_BUTTON -> { openColorMenu(player, options, isBody = true); return }
        SLOT_GLOW_COLOR_BUTTON -> { openColorMenu(player, options, isBody = false); return }
        else -> return
    }

    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
    refreshMain(inventory, options)
    saveSpiderOptions(player.uniqueId, options)
}

private fun onColorMenuClick(player: Player, slot: Int, options: SpiderOptions, inventory: Inventory, isBody: Boolean) {
    when {
        slot == SLOT_SUB_BACK -> { openSpiderCustomizationGUI(player); return }
        slot == SLOT_SUB_CLOSE -> { player.closeInventory(); return }
        slot in COLOR_SLOTS -> {
            val dye = orderedDyeColors[COLOR_SLOTS.indexOf(slot)]
            if (isBody) options.bodyPlan.setBodyColor(dye) else options.bodyPlan.setGlowColor(dye)
        }
        else -> return
    }

    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
    refreshColorMenu(inventory, options, isBody)
    saveSpiderOptions(player.uniqueId, options)
}

fun openSpiderCustomizationGUI(player: Player) {
    val entity = AppState.findSpiderByOwner(player.uniqueId)
    if (entity == null) {
        player.sendMessage(Component.text("You don't have a robo-spider yet.", NamedTextColor.RED))
        return
    }
    val options = entity.query<SpiderOptions>() ?: return

    val holder = CustomizationHolder(Menu.MAIN)
    val inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, Component.text(MAIN_TITLE))
    holder.backing = inventory

    populateMain(inventory, options)
    player.openInventory(inventory)
}

private fun openColorMenu(player: Player, options: SpiderOptions, isBody: Boolean) {
    val menu = if (isBody) Menu.BODY_COLOR else Menu.GLOW_COLOR
    val title = if (isBody) BODY_COLOR_TITLE else GLOW_COLOR_TITLE

    val holder = CustomizationHolder(menu)
    val inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, Component.text(title))
    holder.backing = inventory

    populateColorMenu(inventory, options, isBody)
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

private fun populateMain(inventory: Inventory, options: SpiderOptions) {
    val filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ")
    for (i in 0 until inventory.size) inventory.setItem(i, filler)

    inventory.setItem(SLOT_TITLE, namedItem(Material.SPIDER_SPAWN_EGG, MAIN_TITLE, NamedTextColor.AQUA))
    inventory.setItem(SLOT_SCALE_DOWN, namedItem(Material.RED_DYE, "- Decrease Scale", NamedTextColor.RED))
    inventory.setItem(SLOT_SCALE_UP, namedItem(Material.LIME_DYE, "+ Increase Scale", NamedTextColor.GREEN))
    inventory.setItem(SLOT_CLOSE, namedItem(Material.BARRIER, "Close", NamedTextColor.RED))

    refreshMain(inventory, options)
}

private fun refreshMain(inventory: Inventory, options: SpiderOptions) {
    inventory.setItem(SLOT_SCALE_DISPLAY, createScaleDisplay(options.bodyPlan.scale))
    inventory.setItem(SLOT_BODY_COLOR_BUTTON, createColorMenuButton(Material.PAINTING, "Body Color", NamedTextColor.GOLD, options.bodyPlan.bodyColor))
    inventory.setItem(SLOT_GLOW_COLOR_BUTTON, createColorMenuButton(Material.ENDER_EYE, "Eye / Glow Color", NamedTextColor.AQUA, options.bodyPlan.glowColor))
}

private fun populateColorMenu(inventory: Inventory, options: SpiderOptions, isBody: Boolean) {
    val filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ")
    for (i in 0 until inventory.size) inventory.setItem(i, filler)

    val title = if (isBody) BODY_COLOR_TITLE else GLOW_COLOR_TITLE
    inventory.setItem(SLOT_SUB_TITLE, namedItem(Material.SPIDER_SPAWN_EGG, title, NamedTextColor.AQUA))
    inventory.setItem(SLOT_SUB_BACK, namedItem(Material.ARROW, "< Back", NamedTextColor.YELLOW))
    inventory.setItem(SLOT_SUB_CLOSE, namedItem(Material.BARRIER, "Close", NamedTextColor.RED))

    refreshColorMenu(inventory, options, isBody)
}

private fun refreshColorMenu(inventory: Inventory, options: SpiderOptions, isBody: Boolean) {
    val selectedColor = if (isBody) options.bodyPlan.bodyColor else options.bodyPlan.glowColor
    val purpose = if (isBody) "body color" else "eye/glow color"

    for ((index, dye) in orderedDyeColors.withIndex()) {
        inventory.setItem(COLOR_SLOTS[index], createColorButton(dye, purpose, selectedColor == dye))
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

private fun createColorMenuButton(material: Material, label: String, color: NamedTextColor, currentDye: DyeColor): ItemStack {
    val item = ItemStack(material)
    val meta = item.itemMeta ?: return item

    val currentName = currentDye.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    meta.itemName(Component.text(label, color).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(
        Component.text("Currently: $currentName", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
        Component.text("Click to open color menu", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
    ))
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
