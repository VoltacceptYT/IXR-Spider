package net.browselotus.spiderbot.spider

import net.browselotus.spiderbot.spider.configuration.UpgradeModule
import net.browselotus.spiderbot.utilities.custom_items.CustomItemComponent
import net.browselotus.spiderbot.utilities.custom_items.attach
import net.browselotus.spiderbot.utilities.namespacedID
import net.browselotus.spiderbot.utilities.setSpiderbotModel
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

private val upgradeModuleComponent by lazy { CustomItemComponent("upgradeModule") }
private val MODULE_KEY by lazy { namespacedID("upgrade_module_id") }

private fun line(component: Component) = component.decoration(TextDecoration.ITALIC, false)

fun createUpgradeModuleItem(module: UpgradeModule): ItemStack {
    val item = ItemStack(module.material)
    val meta = item.itemMeta ?: return item

    meta.itemName(Component.text(module.displayName, module.color).decoration(TextDecoration.ITALIC, false))
    meta.lore(listOf(
        line(Component.text("Upgrade Module", NamedTextColor.DARK_GRAY)),
        line(Component.text(module.description, NamedTextColor.GRAY)),
        Component.empty(),
        line(Component.text("Drag it into a component slot in the Mk9 Configurator", NamedTextColor.YELLOW)),
    ))
    meta.setSpiderbotModel(module.modelId)
    meta.persistentDataContainer.set(MODULE_KEY, PersistentDataType.STRING, module.name)
    item.itemMeta = meta
    return item.attach(upgradeModuleComponent)
}

fun ItemStack.upgradeModule(): UpgradeModule? {
    val id = itemMeta?.persistentDataContainer?.get(MODULE_KEY, PersistentDataType.STRING) ?: return null
    return UpgradeModule.entries.find { it.name == id }
}

/** Puts a module item in the player's inventory, dropping it at their feet if there is no room. */
fun giveUpgradeModuleItem(player: org.bukkit.entity.Player, module: UpgradeModule) {
    val leftovers = player.inventory.addItem(createUpgradeModuleItem(module))
    for (left in leftovers.values) player.world.dropItemNaturally(player.location, left)
}
