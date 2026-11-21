package net.browselotus.spiderbot.spider.gui

import net.browselotus.spiderbot.AppState
import net.browselotus.spiderbot.spider.configuration.CreatureType
import net.browselotus.spiderbot.spider.configuration.PartRole
import net.browselotus.spiderbot.spider.configuration.PartSpec
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.capabilities
import net.browselotus.spiderbot.spider.configuration.efficiency
import net.browselotus.spiderbot.spider.configuration.partSpecs
import net.browselotus.spiderbot.spider.configuration.statusOf
import net.browselotus.spiderbot.spider.saveSpiderOptions
import net.browselotus.spiderbot.utilities.events.addEventListener
import net.browselotus.spiderbot.utilities.events.interval
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import kotlin.math.roundToInt

private const val SIZE = 54
private const val SLOT_BACK = 0
private const val SLOT_OVERVIEW = 4
private const val SLOT_HELP = 8
private const val SLOT_CLOSE = 49

private const val REPAIR_STEP = 0.25
private const val REPAIR_IRON = 1
private const val REPAIR_REDSTONE = 1
private const val REATTACH_IRON = 3
private const val REATTACH_REDSTONE = 2

private class StatusHolder : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory(): Inventory = backing
}

fun setupSystemStatusGUI() {
    addEventListener(object : Listener {
        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            event.inventory.holder as? StatusHolder ?: return
            event.isCancelled = true

            val slot = event.rawSlot
            if (slot < 0 || slot >= event.inventory.size) return

            val player = event.whoClicked as? Player ?: return

            if (slot == SLOT_BACK) { openSpiderCustomizationGUI(player); return }
            if (slot == SLOT_CLOSE) { player.closeInventory(); return }

            val entity = AppState.findSpiderByOwner(player.uniqueId) ?: return
            val options = entity.query<SpiderOptions>() ?: return

            val id = layout(options).entries.firstOrNull { it.value == slot }?.key ?: return
            val spec = options.partSpecs().firstOrNull { it.id == id } ?: return

            if (options.damage.of(spec.id) >= 1.0) {
                player.sendActionBar(Component.text("${spec.label} is fully repaired", NamedTextColor.GRAY))
                return
            }

            if (repair(player, options, spec, event.isShiftClick)) {
                player.playSound(player.location, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.8f, 1.2f)
                saveSpiderOptions(player.uniqueId, options)
            } else {
                val detached = spec.detachable && options.damage.of(spec.id) <= 0.0
                val iron = if (detached) REATTACH_IRON else REPAIR_IRON
                val redstone = if (detached) REATTACH_REDSTONE else REPAIR_REDSTONE
                player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1.2f)
                player.sendActionBar(Component.text("Need $iron iron ingot(s) + $redstone redstone", NamedTextColor.RED))
            }

            populate(event.inventory, options)
        }
    })

    interval(0, 10) {
        for (player in Bukkit.getOnlinePlayers()) {
            val holder = player.openInventory.topInventory.holder as? StatusHolder ?: continue
            val options = AppState.findSpiderByOwner(player.uniqueId)?.query<SpiderOptions>() ?: continue
            populate(holder.backing, options)
        }
    }
}

fun openSystemStatusGUI(player: Player) {
    val entity = AppState.findSpiderByOwner(player.uniqueId)
    if (entity == null) {
        player.sendMessage(Component.text("You don't have a robo-spider yet.", NamedTextColor.RED))
        return
    }
    val options = entity.query<SpiderOptions>() ?: return

    val holder = StatusHolder()
    val inventory = Bukkit.createInventory(holder, SIZE, Component.text("System Status"))
    holder.backing = inventory

    populate(inventory, options)
    player.openInventory(inventory)
}

private fun repair(player: Player, options: SpiderOptions, spec: PartSpec, all: Boolean): Boolean {
    val damage = options.damage
    var repaired = false

    while (damage.of(spec.id) < 1.0) {
        val detached = spec.detachable && damage.of(spec.id) <= 0.0
        val iron = if (detached) REATTACH_IRON else REPAIR_IRON
        val redstone = if (detached) REATTACH_REDSTONE else REPAIR_REDSTONE

        val inventory = player.inventory
        if (!inventory.containsAtLeast(ItemStack(Material.IRON_INGOT), iron)) break
        if (!inventory.containsAtLeast(ItemStack(Material.REDSTONE), redstone)) break

        inventory.removeItem(ItemStack(Material.IRON_INGOT, iron), ItemStack(Material.REDSTONE, redstone))
        damage.set(spec.id, damage.of(spec.id) + REPAIR_STEP)
        repaired = true

        if (!all) break
    }

    return repaired
}

private fun layout(options: SpiderOptions): Map<String, Int> {
    val slots = mutableMapOf<String, Int>()
    when (options.creature) {
        CreatureType.DROID -> {
            slots["head"] = 13
            slots["armR"] = 21
            slots["chest"] = 22
            slots["armL"] = 23
            slots["leg1"] = 30
            slots["leg0"] = 32
        }
        CreatureType.SPIDER -> {
            slots["core"] = 22
            for (i in options.bodyPlan.legs.indices) {
                val row = 1 + i / 2
                val column = if (i % 2 == 0) 2 else 6
                slots["leg$i"] = row * 9 + column
            }
        }
    }
    return slots
}

private fun populate(inventory: Inventory, options: SpiderOptions) {
    val filler = plain(Material.GRAY_STAINED_GLASS_PANE, " ")
    for (i in 0 until inventory.size) inventory.setItem(i, filler)

    inventory.setItem(SLOT_BACK, plain(Material.ARROW, "< Back", NamedTextColor.YELLOW))
    inventory.setItem(SLOT_CLOSE, plain(Material.BARRIER, "Close", NamedTextColor.RED))
    inventory.setItem(SLOT_OVERVIEW, overviewItem(options))
    inventory.setItem(SLOT_HELP, helpItem())

    val slots = layout(options)
    for (spec in options.partSpecs()) {
        val slot = slots[spec.id] ?: continue
        inventory.setItem(slot, partItem(spec, options.damage.of(spec.id)))
    }
}

private fun partItem(spec: PartSpec, health: Double): ItemStack {
    val status = statusOf(health)
    val detached = health <= 0.0 && spec.detachable

    val material = when {
        detached -> Material.BARRIER
        else -> when (spec.role) {
            PartRole.CORE -> Material.IRON_CHESTPLATE
            PartRole.HEAD -> Material.IRON_HELMET
            PartRole.CANNON_ARM -> Material.CROSSBOW
            PartRole.GUARD_ARM -> Material.SHIELD
            PartRole.LEG -> if (spec.id == "leg0" || spec.id == "leg1") Material.IRON_LEGGINGS else Material.IRON_BOOTS
        }
    }

    val item = ItemStack(material)
    val meta = item.itemMeta ?: return item

    val percent = (health * 100).roundToInt()
    val function = (efficiency(health) * 100).roundToInt()
    val filled = (health * 10).roundToInt().coerceIn(0, 10)

    meta.itemName(Component.text(spec.label, status.color).decoration(TextDecoration.ITALIC, false))

    val lore = mutableListOf<Component>()
    lore += line(
        Component.text("Integrity: ", NamedTextColor.GRAY)
            .append(Component.text("|".repeat(filled), status.color))
            .append(Component.text("|".repeat(10 - filled), NamedTextColor.DARK_GRAY))
            .append(Component.text(" $percent%", NamedTextColor.WHITE))
    )
    lore += line(
        Component.text("Status: ", NamedTextColor.GRAY)
            .append(Component.text(if (detached) "KNOCKED OFF" else if (health <= 0.0) "OFFLINE" else status.label, status.color))
    )
    lore += line(Component.text(roleLine(spec, function), NamedTextColor.AQUA))
    lore += Component.empty()
    if (health < 1.0) {
        val cost = if (detached) "$REATTACH_IRON iron + $REATTACH_REDSTONE redstone" else "$REPAIR_IRON iron + $REPAIR_REDSTONE redstone"
        lore += line(Component.text(if (detached) "Click to rebuild: $cost" else "Click to repair: $cost", NamedTextColor.GOLD))
        lore += line(Component.text("Shift-click to repair fully", NamedTextColor.DARK_GRAY))
    } else {
        lore += line(Component.text("Fully repaired", NamedTextColor.DARK_GREEN))
    }
    meta.lore(lore)

    if (meta is Damageable && !detached && material.maxDurability > 1) {
        val max = material.maxDurability.toInt()
        meta.damage = ((1.0 - health) * (max - 1)).toInt().coerceIn(0, max - 1)
    }
    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)

    item.itemMeta = meta
    return item
}

private fun roleLine(spec: PartSpec, function: Int): String = when (spec.role) {
    PartRole.CORE -> if (function <= 0) "Power: OFFLINE, robot is shut down" else "Core output: $function%"
    PartRole.HEAD -> "Sensor range: $function%"
    PartRole.CANNON_ARM -> "Firepower: $function%"
    PartRole.GUARD_ARM -> "Aim stability: $function%"
    PartRole.LEG -> "Mobility contribution: $function%"
}

private fun overviewItem(options: SpiderOptions): ItemStack {
    val caps = options.capabilities()
    val specs = options.partSpecs()
    val average = if (specs.isEmpty()) 1.0 else specs.sumOf { options.damage.of(it.id) } / specs.size

    val item = ItemStack(if (caps.online) Material.NETHER_STAR else Material.REDSTONE_BLOCK)
    val meta = item.itemMeta ?: return item

    meta.itemName(Component.text("System Status", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))

    fun pct(value: Double) = "${(value * 100).roundToInt()}%"

    meta.lore(listOf(
        line(Component.text("Overall integrity: ${pct(average)}", NamedTextColor.WHITE)),
        line(Component.text(if (caps.online) "State: ONLINE" else "State: OFFLINE", if (caps.online) NamedTextColor.GREEN else NamedTextColor.RED)),
        Component.empty(),
        line(Component.text("Mobility: ${pct(caps.mobility)}", NamedTextColor.GRAY)),
        line(Component.text("Firepower: ${pct(caps.firepower)}", NamedTextColor.GRAY)),
        line(Component.text("Sensors: ${pct(caps.sensors)}", NamedTextColor.GRAY)),
        line(Component.text("Aim: ${pct(caps.aim)}", NamedTextColor.GRAY)),
    ))

    item.itemMeta = meta
    return item
}

private fun helpItem(): ItemStack {
    val item = ItemStack(Material.ANVIL)
    val meta = item.itemMeta ?: return item

    meta.itemName(Component.text("Repairs", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(
        line(Component.text("Click a part with iron ingots and redstone", NamedTextColor.GRAY)),
        line(Component.text("in your inventory to repair it.", NamedTextColor.GRAY)),
        Component.empty(),
        line(Component.text("Repair: $REPAIR_IRON iron + $REPAIR_REDSTONE redstone (+25%)", NamedTextColor.YELLOW)),
        line(Component.text("Rebuild lost part: $REATTACH_IRON iron + $REATTACH_REDSTONE redstone", NamedTextColor.YELLOW)),
    ))

    item.itemMeta = meta
    return item
}

private fun line(component: Component) = component.decoration(TextDecoration.ITALIC, false)

private fun plain(material: Material, name: String, color: NamedTextColor = NamedTextColor.WHITE): ItemStack {
    val item = ItemStack(material)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))
    item.itemMeta = meta
    return item
}
