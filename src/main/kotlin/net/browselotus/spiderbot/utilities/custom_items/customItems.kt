package net.browselotus.spiderbot.utilities.custom_items

import net.browselotus.spiderbot.utilities.events.onGestureUseItem
import net.browselotus.spiderbot.utilities.events.onTick
import net.browselotus.spiderbot.utilities.namespacedID
import net.browselotus.spiderbot.utilities.requireCommand
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Bukkit.createInventory
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

val customItemRegistry = mutableListOf<() -> ItemStack>()

fun openCustomItemInventory(player: Player) {
    val inventory = createInventory(null, InventoryType.CHEST, Component.text("Items"))
    customItemRegistry.forEach { inventory.addItem(it()) }
    player.openInventory(inventory)
}

fun setupCustomItemCommand() {
    requireCommand("items").apply {
        setExecutor { sender, _, _, _ ->
            if (sender !is Player) {
                sender.sendMessage("This command can only be used by players.")
                return@setExecutor true
            }
            openCustomItemInventory(sender)
            true
        }
    }
}

class CustomItemComponent(val id: String) {
    fun isAttached(item: ItemStack): Boolean {
        return item.itemMeta?.persistentDataContainer?.get(namespacedID("item_component_$id"), PersistentDataType.BOOLEAN) == true
    }

    fun attach(item: ItemStack) {
        val itemMeta = item.itemMeta ?: return
        itemMeta.persistentDataContainer.set(namespacedID("item_component_$id"), PersistentDataType.BOOLEAN, true)
        item.itemMeta = itemMeta
    }

    fun getPlayersHoldingItem() = Bukkit.getOnlinePlayers().filter { player ->
        val itemInMainHand = player.inventory.itemInMainHand
        val itemInOffHand = player.inventory.itemInOffHand
        isAttached(itemInMainHand) || isAttached(itemInOffHand)
    }

    fun onGestureUse(action: (Player, ItemStack) -> Unit) {
        onGestureUseItem { player, item ->
            if (isAttached(item)) action(player, item)
        }
    }

    fun onInteractEntity(action: (Player, Entity, ItemStack) -> Unit) {
        net.browselotus.spiderbot.utilities.events.onInteractEntity(fun(player, entity, hand) {
            val item = player.inventory.getItem(hand)
            if (isAttached(item)) action(player, entity, item)
        })
    }

    fun onHeldTick(action: (Player, ItemStack) -> Unit) {
        onTick {
            for (player in Bukkit.getServer().onlinePlayers) {
                val itemInMainHand = player.inventory.itemInMainHand
                val itemInOffHand = player.inventory.itemInOffHand
                if (isAttached(itemInMainHand)) action(player, itemInMainHand)
                if (isAttached(itemInOffHand)) action(player, itemInOffHand)
            }
        }
    }

    /**
     * Keeps this item locked to the player's offhand slot. Whenever the item is found anywhere
     * else in a player's inventory (main hand, hotbar, storage, picked up, swapped, etc.) it is
     * moved back into the offhand, swapping with whatever was there if needed.
     */
    fun lockToOffHand() {
        onTick {
            for (player in Bukkit.getServer().onlinePlayers) {
                val inventory = player.inventory
                val offHandItem = inventory.itemInOffHand

                if (isAttached(offHandItem)) continue

                val contents = inventory.contents
                val slot = contents.indexOfFirst { it != null && isAttached(it) }
                if (slot == -1) continue

                val item = contents[slot] ?: continue

                inventory.setItem(slot, if (offHandItem.type == Material.AIR) null else offHandItem)
                inventory.setItemInOffHand(item)

                player.playSound(player.location, org.bukkit.Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.5f, 1.4f)
            }
        }
    }
}

fun createNamedItem(material: Material, name: String): ItemStack {
    val item = ItemStack(material)
    val itemMeta = item.itemMeta ?: throw Exception("ItemMeta is null")
    itemMeta.itemName(Component.text(name))
    item.itemMeta = itemMeta
    return item
}

fun ItemStack.attach(component: CustomItemComponent): ItemStack {
    component.attach(this)
    return this
}