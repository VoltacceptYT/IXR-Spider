package net.browselotus.spiderbot.spider.gui

import net.browselotus.spiderbot.spider.configuration.UpgradeModule
import net.browselotus.spiderbot.spider.createUpgradeModuleItem
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
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/** Permission for `/upgrades`. */
const val ADMIN_UPGRADES_PERMISSION = "spiderbot.upgrades.admin"

private const val SIZE = 27
private const val STACK = 64

/** Module slots: the first row of seven, then the rest centred on the row below. */
private val MODULE_SLOTS = (10..16).toList() + (20..24).toList()

private class AdminUpgradesHolder : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory(): Inventory = backing
}

private fun stackOf(module: UpgradeModule): ItemStack = createUpgradeModuleItem(module).apply { amount = STACK }

fun openAdminUpgradesGUI(player: Player) {
    if (!player.hasPermission(ADMIN_UPGRADES_PERMISSION)) {
        player.sendMessage(Component.text("Only admins can use this command.", NamedTextColor.RED))
        return
    }

    val holder = AdminUpgradesHolder()
    val inventory = Bukkit.createInventory(holder, SIZE, Component.text("Admin Upgrades"))
    holder.backing = inventory

    val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.itemName(Component.text(" ")) }
    }
    for (i in 0 until SIZE) inventory.setItem(i, filler)
    for ((index, module) in UpgradeModule.entries.take(MODULE_SLOTS.size).withIndex()) {
        inventory.setItem(MODULE_SLOTS[index], stackOf(module))
    }

    inventory.setItem(
        4,
        ItemStack(Material.BARRIER).apply {
            editMeta {
                it.itemName(Component.text("Close", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
            }
        },
    )

    player.openInventory(inventory)
}

fun setupAdminUpgradesGUI() {
    addEventListener(object : Listener {
        @EventHandler
        fun onDrag(event: InventoryDragEvent) {
            if (event.inventory.holder !is AdminUpgradesHolder) return
            if (event.rawSlots.any { it < event.inventory.size }) event.isCancelled = true
        }

        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            if (event.inventory.holder !is AdminUpgradesHolder) return
            val player = event.whoClicked as? Player ?: return

            if (!player.hasPermission(ADMIN_UPGRADES_PERMISSION)) {
                event.isCancelled = true
                player.closeInventory()
                return
            }

            val rawSlot = event.rawSlot
            val inMenu = rawSlot in 0 until event.inventory.size

            // the player's own inventory stays usable, but nothing may be shift-clicked or collected into the menu
            if (!inMenu) {
                if (event.isShiftClick || event.action == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
                    event.isCancelled = true
                }
                return
            }

            // nothing can be put into, swapped with or taken out of the menu itself: it only hands out copies
            event.isCancelled = true

            if (rawSlot == 4) { player.closeInventory(); return }

            val index = MODULE_SLOTS.indexOf(rawSlot)
            val module = UpgradeModule.entries.getOrNull(index) ?: return
            if (event.click !in setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)) return

            if (event.isShiftClick) {
                val leftovers = player.inventory.addItem(stackOf(module))
                for (left in leftovers.values) player.world.dropItemNaturally(player.location, left)
            } else {
                if (!event.cursor.type.isAir) {
                    player.sendActionBar(Component.text("Empty your cursor first", NamedTextColor.RED))
                    return
                }
                player.setItemOnCursor(stackOf(module))
            }
            player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f)
        }
    })
}
