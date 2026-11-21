package net.browselotus.spiderbot

import net.browselotus.spiderbot.spider.spinWheel
import net.browselotus.spiderbot.utilities.custom_items.setupCustomItemCommand
import net.browselotus.spiderbot.utilities.requireCommand
import org.bukkit.entity.Player

/** The plugin only has two commands: `/items` (the Robot Configuration tool) and `/wheel`. */
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
}
