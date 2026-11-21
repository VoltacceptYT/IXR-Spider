package net.browselotus.spiderbot.spider.gui

import net.browselotus.spiderbot.AppState
import net.browselotus.spiderbot.spider.configuration.SpiderOptions
import net.browselotus.spiderbot.spider.configuration.UpgradeModule
import net.browselotus.spiderbot.spider.createUpgradeModuleItem
import net.browselotus.spiderbot.spider.saveSpiderOptions
import net.browselotus.spiderbot.spider.upgradeModule
import net.browselotus.spiderbot.utilities.events.addEventListener
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
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.enchantments.Enchantment

private const val SIZE = 27
private const val SLOT_BACK = 0
private const val SLOT_TITLE = 4
private const val SLOT_CLOSE = 22

/** One slot per module, in the middle row. */
private val MODULE_SLOTS = UpgradeModule.entries.mapIndexed { index, module -> (10 + index) to module }.toMap()

private class UpgradeHolder : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory(): Inventory = backing
}

fun setupUpgradeModulesGUI() {
    addEventListener(object : Listener {
        @EventHandler
        fun onDrag(event: InventoryDragEvent) {
            if (event.inventory.holder is UpgradeHolder) event.isCancelled = true
        }

        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            if (event.inventory.holder !is UpgradeHolder) return
            event.isCancelled = true

            val slot = event.rawSlot
            if (slot < 0 || slot >= event.inventory.size) return

            val player = event.whoClicked as? Player ?: return

            if (slot == SLOT_BACK) { openSpiderCustomizationGUI(player); return }
            if (slot == SLOT_CLOSE) { player.closeInventory(); return }

            val module = MODULE_SLOTS[slot] ?: return
            val options = AppState.findSpiderByOwner(player.uniqueId)?.query<SpiderOptions>() ?: return

            if (options.upgrades.has(module)) uninstall(player, options, module) else install(player, options, module)

            populate(event.inventory, player, options)
        }
    })
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

    populate(inventory, player, options)
    player.openInventory(inventory)
}

/** The first stack of [module] in the player's inventory, if they have one. */
private fun findModuleItem(player: Player, module: UpgradeModule): ItemStack? =
    player.inventory.contents.firstOrNull { it != null && it.upgradeModule() == module }

private fun countModuleItems(player: Player, module: UpgradeModule) =
    player.inventory.contents.filter { it != null && it.upgradeModule() == module }.sumOf { it!!.amount }

private fun giveBack(player: Player, module: UpgradeModule) {
    val leftovers = player.inventory.addItem(createUpgradeModuleItem(module))
    for (left in leftovers.values) player.world.dropItemNaturally(player.location, left)
}

private fun install(player: Player, options: SpiderOptions, module: UpgradeModule) {
    val stack = findModuleItem(player, module)
    if (stack == null) {
        player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1.2f)
        player.sendActionBar(Component.text("You don't have a ${module.displayName} module (try /wheel)", NamedTextColor.RED))
        return
    }

    stack.amount -= 1
    val replaced = options.upgrades.install(module)
    if (replaced != null) giveBack(player, replaced) // only one plating at a time: the old one comes back

    player.playSound(player.location, Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1.2f)
    player.sendActionBar(Component.text("Installed ${module.displayName}", module.color))
    saveSpiderOptions(player.uniqueId, options)
}

private fun uninstall(player: Player, options: SpiderOptions, module: UpgradeModule) {
    if (!options.upgrades.uninstall(module)) return
    giveBack(player, module)

    player.playSound(player.location, Sound.BLOCK_GRINDSTONE_USE, 0.8f, 1.2f)
    player.sendActionBar(Component.text("Uninstalled ${module.displayName}", NamedTextColor.YELLOW))
    saveSpiderOptions(player.uniqueId, options)
}

private fun populate(inventory: Inventory, player: Player, options: SpiderOptions) {
    val filler = plain(Material.GRAY_STAINED_GLASS_PANE, " ")
    for (i in 0 until inventory.size) inventory.setItem(i, filler)

    inventory.setItem(SLOT_BACK, plain(Material.ARROW, "< Back", NamedTextColor.YELLOW))
    inventory.setItem(SLOT_CLOSE, plain(Material.BARRIER, "Close", NamedTextColor.RED))
    inventory.setItem(SLOT_TITLE, plain(Material.SMITHING_TABLE, "Upgrade Modules", NamedTextColor.AQUA))

    for ((slot, module) in MODULE_SLOTS) {
        inventory.setItem(slot, moduleButton(module, options, countModuleItems(player, module)))
    }
}

private fun line(component: Component) = component.decoration(TextDecoration.ITALIC, false)

private fun moduleButton(module: UpgradeModule, options: SpiderOptions, owned: Int): ItemStack {
    val installed = options.upgrades.has(module)
    val item = ItemStack(module.material)
    val meta = item.itemMeta ?: return item

    meta.itemName(Component.text(module.displayName, module.color).decoration(TextDecoration.ITALIC, false))

    val lore = mutableListOf<Component>()
    lore += line(Component.text(module.description, NamedTextColor.GRAY))
    lore += Component.empty()

    if (installed) {
        lore += line(Component.text("INSTALLED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
        lore += line(Component.text("Click to uninstall", NamedTextColor.GOLD))
        meta.addEnchant(Enchantment.UNBREAKING, 1, true)
    } else {
        lore += line(Component.text("Not installed", NamedTextColor.RED))
        lore += line(Component.text("In your inventory: $owned", NamedTextColor.GRAY))
        if (owned > 0) {
            lore += line(Component.text("Click to install", NamedTextColor.GOLD))
            val current = if (module.isPlating) options.upgrades.plating else null
            if (current != null) lore += line(Component.text("Replaces ${current.displayName}", NamedTextColor.DARK_GRAY))
        } else {
            lore += line(Component.text("Win modules from /wheel", NamedTextColor.DARK_GRAY))
        }
    }

    meta.lore(lore)
    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
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
