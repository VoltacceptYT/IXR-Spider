package net.browselotus.spiderbot

import net.browselotus.spiderbot.spider.gui.openAdminUpgradesGUI
import net.browselotus.spiderbot.spider.spinWheel
import net.browselotus.spiderbot.utilities.custom_items.setupCustomItemCommand
import net.browselotus.spiderbot.utilities.requireCommand
import org.bukkit.entity.Player

/** The plugin's commands: `/items` (the Robot Configuration tool), `/wheel`, and the admin-only `/upgrades`. */
fun setupCommands() {
    setupCustomItemCommand()

    requireCommand("wheel").setExecutor { sender, _, _, _ ->
        if (sender !is Player) {
            sender.sendMessage("This command can only be used by players.")
            return@setExecutor true
        }
        spinWheel(sender)
        true
    }

    requireCommand("upgrades").setExecutor { sender, _, _, _ ->
        if (sender !is Player) {
            sender.sendMessage("This command can only be used by players.")
            return@setExecutor true
        }
        openAdminUpgradesGUI(sender)
        true
    }
}
