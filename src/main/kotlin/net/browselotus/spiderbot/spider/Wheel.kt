package net.browselotus.spiderbot.spider

import net.browselotus.spiderbot.spider.configuration.UpgradeModule
import net.browselotus.spiderbot.utilities.currentPlugin
import net.browselotus.spiderbot.utilities.events.addEventListener
import net.browselotus.spiderbot.utilities.events.runLater
import net.browselotus.spiderbot.utilities.namespacedID
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
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import kotlin.random.Random

/** Players who are not owners have to wait this long between spins: 6 hours. */
const val WHEEL_COOLDOWN_MILLIS = 6L * 60 * 60 * 1000

/** Owners (operators, or anyone with this permission) can spin as often as they like. */
const val WHEEL_OWNER_PERMISSION = "spiderbot.wheel.owner"

/** Chance that a spin lands on an upgrade module instead of an item of value. */
private const val MODULE_CHANCE = 0.4

private const val WHEEL_SIZE = 27
private const val STRIP_FIRST_SLOT = 9      // the middle row is the scrolling strip
private const val STRIP_WIDTH = 9
private const val CENTER_SLOT = STRIP_FIRST_SLOT + STRIP_WIDTH / 2
private const val POINTER_TOP = 4
private const val POINTER_BOTTOM = 22

/** What the wheel can land on. */
private class Prize(val item: () -> ItemStack, val label: Component)

private class Valuable(val material: Material, val amount: IntRange, val weight: Int)

private val VALUABLES = listOf(
    Valuable(Material.IRON_INGOT, 8..16, 30),
    Valuable(Material.REDSTONE, 16..32, 30),
    Valuable(Material.GOLD_INGOT, 6..12, 22),
    Valuable(Material.EMERALD, 4..10, 16),
    Valuable(Material.DIAMOND, 2..5, 12),
    Valuable(Material.ANCIENT_DEBRIS, 1..3, 6),
    Valuable(Material.ENCHANTED_GOLDEN_APPLE, 1..1, 3),
    Valuable(Material.NETHERITE_INGOT, 1..2, 3),
    Valuable(Material.TOTEM_OF_UNDYING, 1..1, 2),
    Valuable(Material.NETHER_STAR, 1..1, 1),
)

private fun <T> weightedPick(items: List<T>, weight: (T) -> Int): T {
    var roll = Random.nextInt(items.sumOf(weight))
    for (item in items) {
        roll -= weight(item)
        if (roll < 0) return item
    }
    return items.last()
}

private fun rollPrize(): Prize {
    if (Random.nextDouble() < MODULE_CHANCE) {
        val module = weightedPick(UpgradeModule.entries) { it.weight }
        return Prize({ createUpgradeModuleItem(module) }, Component.text(module.displayName, module.color))
    }

    val valuable = weightedPick(VALUABLES) { it.weight }
    val amount = Random.nextInt(valuable.amount.first, valuable.amount.last + 1)
    return Prize({ ItemStack(valuable.material, amount) }, Component.text("${amount}x ${prettyName(valuable.material)}", NamedTextColor.AQUA))
}

private fun prettyName(material: Material) =
    material.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/** An item as it appears on the spinning strip (a plain icon, no real item data). */
private fun stripIcon(prize: Prize): ItemStack {
    val item = prize.item().clone()
    val meta = item.itemMeta ?: return item
    meta.itemName(prize.label.decoration(TextDecoration.ITALIC, false))
    meta.lore(null)
    item.itemMeta = meta
    return item
}

private class WheelHolder : InventoryHolder {
    lateinit var backing: Inventory
    override fun getInventory(): Inventory = backing
}

private val LAST_SPIN_KEY by lazy { namespacedID("wheel_last_spin") }

private fun isOwner(player: Player) = player.isOp || player.hasPermission(WHEEL_OWNER_PERMISSION)

private fun lastSpin(player: Player): Long =
    player.persistentDataContainer.get(LAST_SPIN_KEY, PersistentDataType.LONG) ?: 0L

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis + 59_999) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** Spins that are still animating, so prizes are never lost if the player leaves part way through. */
private val spinning = mutableMapOf<UUID, () -> Unit>()

private fun filler(material: Material = Material.GRAY_STAINED_GLASS_PANE): ItemStack {
    val item = ItemStack(material)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text(" "))
    item.itemMeta = meta
    return item
}

private fun pointer(name: String): ItemStack {
    val item = ItemStack(Material.HOPPER)
    val meta = item.itemMeta ?: return item
    meta.itemName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
    item.itemMeta = meta
    return item
}

fun setupWheel() {
    addEventListener(object : Listener {
        @EventHandler
        fun onClick(event: InventoryClickEvent) {
            if (event.inventory.holder is WheelHolder) event.isCancelled = true
        }

        @EventHandler
        fun onDrag(event: InventoryDragEvent) {
            if (event.inventory.holder is WheelHolder) event.isCancelled = true
        }

        @EventHandler
        fun onQuit(event: PlayerQuitEvent) {
            // hand over the prize right away: it was decided when the wheel was spun
            spinning.remove(event.player.uniqueId)?.invoke()
        }
    })
}

/** Handles `/wheel`: checks the cooldown, then spins the animated wheel. */
fun spinWheel(player: Player) {
    if (spinning.containsKey(player.uniqueId)) {
        player.sendMessage(Component.text("Your wheel is already spinning!", NamedTextColor.RED))
        return
    }

    val owner = isOwner(player)
    if (!owner) {
        val remaining = lastSpin(player) + WHEEL_COOLDOWN_MILLIS - System.currentTimeMillis()
        if (remaining > 0) {
            player.sendMessage(Component.text("The wheel is recharging. Spin again in ${formatDuration(remaining)}.", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1.0f)
            return
        }
        player.persistentDataContainer.set(LAST_SPIN_KEY, PersistentDataType.LONG, System.currentTimeMillis())
    }

    val prize = rollPrize()

    // The strip scrolls left. The frame at index f shows strip[f .. f + 8], so the item in the
    // middle slot is strip[f + 4]. The prize sits where the last frame stops.
    val frames = Random.nextInt(34, 42)
    val prizeIndex = frames + STRIP_WIDTH / 2
    val strip = List(prizeIndex + STRIP_WIDTH / 2 + 1) { index ->
        if (index == prizeIndex) prize else {
            val module = weightedPick(UpgradeModule.entries) { it.weight }
            if (Random.nextDouble() < MODULE_CHANCE) Prize({ createUpgradeModuleItem(module) }, Component.text(module.displayName, module.color))
            else {
                val v = weightedPick(VALUABLES) { it.weight }
                Prize({ ItemStack(v.material) }, Component.text(prettyName(v.material), NamedTextColor.AQUA))
            }
        }
    }
    val icons = strip.map { stripIcon(it) }

    val holder = WheelHolder()
    val inventory = Bukkit.createInventory(holder, WHEEL_SIZE, Component.text("Wheel of Upgrades"))
    holder.backing = inventory
    for (i in 0 until WHEEL_SIZE) inventory.setItem(i, filler())
    inventory.setItem(POINTER_TOP, pointer("v"))
    inventory.setItem(POINTER_BOTTOM, pointer("^"))
    player.openInventory(inventory)

    var finished = false
    fun finish() {
        if (finished) return
        finished = true
        spinning.remove(player.uniqueId)

        val leftovers = player.inventory.addItem(prize.item())
        for (left in leftovers.values) player.world.dropItemNaturally(player.location, left)

        if (player.isOnline) {
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
            player.sendMessage(Component.text("The wheel landed on ", NamedTextColor.GRAY).append(prize.label).append(Component.text("!", NamedTextColor.GRAY)))
        }
    }
    spinning[player.uniqueId] = ::finish

    fun showFrame(frame: Int) {
        for (column in 0 until STRIP_WIDTH) inventory.setItem(STRIP_FIRST_SLOT + column, icons[frame + column])
    }

    fun step(frame: Int) {
        if (finished) return
        showFrame(frame)

        if (frame >= frames) {
            // landed: flash the pointers and hand out the prize
            inventory.setItem(POINTER_TOP, filler(Material.LIME_STAINED_GLASS_PANE))
            inventory.setItem(POINTER_BOTTOM, filler(Material.LIME_STAINED_GLASS_PANE))
            finish()
            runLater(60) { if (player.openInventory.topInventory.holder === holder) player.closeInventory() }
            return
        }

        if (player.isOnline) player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f + frame.toFloat() / frames)

        // starts fast (1 tick per frame) and slows to a crawl at the end
        val remaining = frames - frame
        val delay = when {
            remaining > 18 -> 1L
            remaining > 12 -> 2L
            remaining > 8 -> 3L
            remaining > 5 -> 4L
            remaining > 3 -> 6L
            remaining > 1 -> 8L
            else -> 11L
        }
        runLater(delay) { step(frame + 1) }
    }

    step(0)
}
