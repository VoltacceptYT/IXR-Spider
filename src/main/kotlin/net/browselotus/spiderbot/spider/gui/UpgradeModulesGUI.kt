package net.browselotus.spiderbot.spider.gui

import net.browselotus.spiderbot.AppState
import net.browselotus.spiderbot.spider.components.maxPartHp
import net.browselotus.spiderbot.spider.configuration.MODULE_SLOTS_PER_PART
import net.browselotus.spiderbot.spider.configuration.PartRole
import net.browselotus.spiderbot.spider.configuration.PartSpec
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.UpgradeModule
import net.browselotus.spiderbot.spider.configuration.firepowerRole
import net.browselotus.spiderbot.spider.configuration.WEAPON_SLOT
import net.browselotus.spiderbot.spider.configuration.weaponPart
import net.browselotus.spiderbot.spider.configuration.partSpecs
import net.browselotus.spiderbot.spider.createUpgradeModuleItem
import net.browselotus.spiderbot.spider.giveUpgradeModuleItem
import net.browselotus.spiderbot.spider.saveSpiderOptions
import net.browselotus.spiderbot.spider.upgradeModule
import net.browselotus.spiderbot.utilities.events.addEventListener
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

private const val SIZE = 54
private const val SLOT_BACK = 0
private const val SLOT_TITLE = 4
private const val SLOT_CLOSE = 49

/** The weapon slot sits on the bottom row, with its label to the left of it. */
private const val SLOT_WEAPON_LABEL = 46
private const val SLOT_WEAPON = 47

/** Components are laid out three to a row: [label][slot][slot] [label][slot][slot] [label][slot][slot]. */
private const val PARTS_PER_ROW = 3
private const val MAX_PARTS = PARTS_PER_ROW * 4

private fun labelSlot(partIndex: Int) = (1 + partIndex / PARTS_PER_ROW) * 9 + (partIndex % PARTS_PER_ROW) * 3
private fun moduleSlot(partIndex: Int, slot: Int) = labelSlot(partIndex) + 1 + slot

private class UpgradeHolder : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory(): Inventory = backing
}

/** Finds which component and which of its slots a raw inventory slot is, if it is a module slot. */
private fun findModuleSlot(options: SpiderOptions, rawSlot: Int): Pair<PartSpec, Int>? {
    if (rawSlot == SLOT_WEAPON) return options.weaponPart()?.let { it to WEAPON_SLOT }
    val specs = options.partSpecs()
    for ((index, spec) in specs.take(MAX_PARTS).withIndex()) {
        for (slot in 0 until MODULE_SLOTS_PER_PART) {
            if (moduleSlot(index, slot) == rawSlot) return spec to slot
        }
    }
    return null
}

fun setupUpgradeModulesGUI() {
    addEventListener(object : Listener {
        @EventHandler
        fun onDrag(event: InventoryDragEvent) {
            if (event.inventory.holder !is UpgradeHolder) return
            // spreading a stack over the menu would bypass the slot rules: use plain clicks instead
            if (event.rawSlots.any { it < event.inventory.size }) event.isCancelled = true
        }

        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            if (event.inventory.holder !is UpgradeHolder) return
            val player = event.whoClicked as? Player ?: return
            val rawSlot = event.rawSlot

            // The player's own inventory stays usable so modules can be picked up from it, but nothing may be
            // shift-clicked or double-click-collected into or out of the menu.
            if (rawSlot < 0 || rawSlot >= event.inventory.size) {
                if (event.isShiftClick || event.action == InventoryAction.COLLECT_TO_CURSOR) {
                    event.isCancelled = true
                    if (event.isShiftClick) player.sendActionBar(Component.text("Drag the module onto a slot instead", NamedTextColor.GRAY))
                }
                return
            }

            event.isCancelled = true

            if (rawSlot == SLOT_BACK) { openSpiderCustomizationGUI(player); return }
            if (rawSlot == SLOT_CLOSE) { player.closeInventory(); return }

            val options = AppState.findSpiderByOwner(player.uniqueId)?.query<SpiderOptions>() ?: return
            val (spec, slot) = findModuleSlot(options, rawSlot) ?: return

            if (handleSlotClick(player, options, spec, slot, event.cursor, event.isShiftClick)) {
                saveSpiderOptions(player.uniqueId, options)
            }
            populate(event.inventory, options)
        }
    })
}

/** Returns whether anything changed. */
private fun handleSlotClick(player: Player, options: SpiderOptions, spec: PartSpec, slot: Int, cursor: ItemStack, shift: Boolean): Boolean {
    val upgrades = options.upgrades
    val current = upgrades.get(spec.id, slot)
    val held = cursor.upgradeModule()

    fun deny(message: String): Boolean {
        player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1.2f)
        player.sendActionBar(Component.text(message, NamedTextColor.RED))
        return false
    }

    // taking a module out: shift-click sends it to the inventory, a click with an empty hand picks it up
    if (current != null && (shift || cursor.type.isAir)) {
        upgrades.set(spec.id, slot, null)
        if (shift) giveUpgradeModuleItem(player, current) else player.setItemOnCursor(createUpgradeModuleItem(current))
        player.playSound(player.location, Sound.BLOCK_GRINDSTONE_USE, 0.8f, 1.2f)
        return true
    }

    if (cursor.type.isAir) return false
    if (held == null) return deny("Only upgrade modules fit in these slots")

    if (slot == WEAPON_SLOT && !held.weaponOnly) return deny("Only weapon modules fit the weapon slot")
    if (slot != WEAPON_SLOT && held.weaponOnly) return deny("${held.displayName} only fits the weapon slot")
    val otherSlots = (0..WEAPON_SLOT).filter { it != slot }
    if (otherSlots.any { upgrades.get(spec.id, it) == held }) return deny("${spec.label} already has ${held.displayName}")
    if (current != null && cursor.amount > 1) return deny("Remove the module in this slot first")

    // place one module; a module already in the slot swaps into the player's hand
    upgrades.set(spec.id, slot, held)
    if (current != null) {
        player.setItemOnCursor(createUpgradeModuleItem(current))
    } else {
        val rest = cursor.clone()
        rest.amount -= 1
        player.setItemOnCursor(if (rest.amount <= 0) ItemStack(Material.AIR) else rest)
    }

    player.playSound(player.location, Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1.2f)
    player.sendActionBar(Component.text("Installed ${held.displayName} in ${spec.label}", held.color))
    return true
}

fun openUpgradeModulesGUI(player: Player) {
    val options = AppState.findSpiderByOwner(player.uniqueId)?.query<SpiderOptions>()
    if (options == null) {
        player.sendMessage(Component.text("You don't have a robo-spider yet.", NamedTextColor.RED))
        return
    }

    val holder = UpgradeHolder()
    val inventory = Bukkit.createInventory(holder, SIZE, Component.text("Upgrade Modules"))
    holder.backing = inventory

    populate(inventory, options)
    player.openInventory(inventory)
}

private fun populate(inventory: Inventory, options: SpiderOptions) {
    val filler = plain(Material.GRAY_STAINED_GLASS_PANE, " ")
    for (i in 0 until inventory.size) inventory.setItem(i, filler)

    inventory.setItem(SLOT_BACK, plain(Material.ARROW, "< Back", NamedTextColor.YELLOW))
    inventory.setItem(SLOT_CLOSE, plain(Material.BARRIER, "Close", NamedTextColor.RED))
    inventory.setItem(SLOT_TITLE, helpItem())

    for ((index, spec) in options.partSpecs().take(MAX_PARTS).withIndex()) {
        inventory.setItem(labelSlot(index), partLabel(spec, options))
        for (slot in 0 until MODULE_SLOTS_PER_PART) {
            val module = options.upgrades.get(spec.id, slot)
            inventory.setItem(moduleSlot(index, slot), if (module != null) moduleDisplay(module) else emptySlot())
        }
    }

    val weaponPart = options.weaponPart()
    if (weaponPart != null) {
        val module = options.upgrades.get(weaponPart.id, WEAPON_SLOT)
        inventory.setItem(SLOT_WEAPON_LABEL, weaponLabel(weaponPart))
        inventory.setItem(SLOT_WEAPON, if (module != null) moduleDisplay(module) else emptyWeaponSlot())
    }
}

private fun line(component: Component) = component.decoration(TextDecoration.ITALIC, false)

private fun helpItem(): ItemStack {
    val item = ItemStack(Material.SMITHING_TABLE)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text("Upgrade Modules", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(
        line(Component.text("Every component has 2 module slots, and the weapon has its own weapon slot.", NamedTextColor.GRAY)),
        line(Component.text("Pick a module up from your inventory and click a slot to install it.", NamedTextColor.GRAY)),
        line(Component.text("Click an installed module to take it out (shift-click sends it to your inventory).", NamedTextColor.GRAY)),
        Component.empty(),
        line(Component.text("Get modules by spinning /wheel.", NamedTextColor.DARK_GRAY)),
    ))
    item.itemMeta = meta
    return item
}

private fun partLabel(spec: PartSpec, options: SpiderOptions): ItemStack {
    val material = when (spec.role) {
        PartRole.CORE -> Material.IRON_CHESTPLATE
        PartRole.HEAD -> Material.IRON_HELMET
        PartRole.CANNON_ARM -> Material.CROSSBOW
        PartRole.GUARD_ARM -> Material.SHIELD
        PartRole.LEG -> if (spec.id == "leg0" || spec.id == "leg1") Material.IRON_LEGGINGS else Material.IRON_BOOTS
    }
    val item = ItemStack(material)
    val meta = item.itemMeta ?: return item

    val maxHp = maxPartHp(options, spec.id)
    val hp = options.damage.of(spec.id) * maxHp
    val bonus = options.upgrades.bonusHp(spec.id)

    meta.itemName(Component.text(spec.label, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
    val lore = mutableListOf(
        line(Component.text("Hit points: ${"%.1f".format(hp)} / ${"%.0f".format(maxHp)}", NamedTextColor.GRAY)),
    )
    if (bonus > 0) lore += line(Component.text("Plating: +$bonus hit points", NamedTextColor.GOLD))
    if (options.upgrades.selfHealing(spec.id)) lore += line(Component.text("Self-healing", NamedTextColor.GREEN))
    if (spec.role == options.firepowerRole()) lore += line(Component.text("Fires the lasers", NamedTextColor.AQUA))
    meta.lore(lore)
    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
    item.itemMeta = meta
    return item
}

private fun moduleDisplay(module: UpgradeModule): ItemStack {
    val item = ItemStack(module.material)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text(module.displayName, module.color).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(
        line(Component.text(module.description, NamedTextColor.GRAY)),
        Component.empty(),
        line(Component.text("Installed", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)),
        line(Component.text("Click to take out", NamedTextColor.GOLD)),
        line(Component.text("Shift-click to send to your inventory", NamedTextColor.DARK_GRAY)),
    ))
    meta.addEnchant(Enchantment.UNBREAKING, 1, true)
    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
    item.itemMeta = meta
    return item
}

private fun weaponLabel(spec: PartSpec): ItemStack {
    val item = ItemStack(Material.CROSSBOW)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text("Weapon Slot", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(
        line(Component.text("On the ${spec.label}", NamedTextColor.GRAY)),
        line(Component.text("Only weapon modules fit here", NamedTextColor.DARK_GRAY)),
    ))
    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
    item.itemMeta = meta
    return item
}

private fun emptyWeaponSlot(): ItemStack {
    val item = ItemStack(Material.RED_STAINED_GLASS_PANE)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text("Empty weapon slot", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(line(Component.text("Click with a weapon module (like the Beam Splitter) to install it", NamedTextColor.DARK_GRAY))))
    item.itemMeta = meta
    return item
}

private fun emptySlot(): ItemStack {
    val item = ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text("Empty module slot", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(line(Component.text("Click with an upgrade module to install it", NamedTextColor.DARK_GRAY))))
    item.itemMeta = meta
    return item
}

private fun plain(material: Material, name: String, color: NamedTextColor = NamedTextColor.WHITE): ItemStack {
    val item = ItemStack(material)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text(name, color).decoration(TextDecoration.ITALIC, false))
    item.itemMeta = meta
    return item
}
