package net.browselotus.spiderbot.spider.configuration

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material

/** Upgrade modules a robot can have installed. They are won from `/wheel` and installed by right-clicking one. */
enum class UpgradeModule(
    val displayName: String,
    val material: Material,
    val color: NamedTextColor,
    val description: String,
    /** Extra hit points every component gets while this plating is equipped (0 for non-plating modules). */
    val plateHp: Int = 0,
    /** Relative chance of coming up on the wheel, among the modules. */
    val weight: Int,
) {
    SELF_HEALING("Self-Healing Nanoparticles", Material.SLIME_BALL, NamedTextColor.GREEN,
        "Heals every component by 0.5 hit points per second", weight = 10),
    COPPER_PLATING("Copper Plating", Material.COPPER_INGOT, NamedTextColor.GOLD,
        "+1 hit point on every component", plateHp = 1, weight = 20),
    BRASS_PLATING("Brass Plating", Material.GOLD_INGOT, NamedTextColor.YELLOW,
        "+2 hit points on every component", plateHp = 2, weight = 16),
    STEEL_PLATING("Steel Plating", Material.IRON_INGOT, NamedTextColor.GRAY,
        "+3 hit points on every component", plateHp = 3, weight = 12),
    TITANIUM_PLATING("Titanium Plating", Material.NETHERITE_SCRAP, NamedTextColor.WHITE,
        "+4 hit points on every component", plateHp = 4, weight = 7),
    COBALT_PLATING("Cobalt Plating", Material.LAPIS_LAZULI, NamedTextColor.BLUE,
        "+5 hit points on every component", plateHp = 5, weight = 4),
    BEAM_SPLITTER("Beam Splitter", Material.PRISMARINE_CRYSTALS, NamedTextColor.AQUA,
        "Splits each laser into 3 smaller lasers that spread like a shotgun", weight = 6);

    val isPlating get() = plateHp > 0
}

/** The modules installed in a robot. Only one plating can be equipped at a time. */
class RobotUpgrades {
    /** Names of the installed [UpgradeModule]s. */
    val installed = mutableSetOf<String>()

    fun has(module: UpgradeModule) = module.name in installed

    val plating: UpgradeModule?
        get() = UpgradeModule.entries.filter { it.isPlating && has(it) }.maxByOrNull { it.plateHp }

    val bonusHp get() = plating?.plateHp ?: 0
    val selfHealing get() = has(UpgradeModule.SELF_HEALING)
    val beamSplitter get() = has(UpgradeModule.BEAM_SPLITTER)

    /** Removes [module], returning whether it was installed. */
    fun uninstall(module: UpgradeModule) = installed.remove(module.name)

    /** Installs [module], returning the plating it replaced if there was one. */
    fun install(module: UpgradeModule): UpgradeModule? {
        val replaced = if (module.isPlating) plating else null
        if (replaced != null) installed.remove(replaced.name)
        installed.add(module.name)
        return replaced
    }
}
