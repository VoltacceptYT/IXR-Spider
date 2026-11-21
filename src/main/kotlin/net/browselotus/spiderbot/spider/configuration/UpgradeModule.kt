package net.browselotus.spiderbot.spider.configuration

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material

/** Every component of the robot has this many general module slots. */
const val MODULE_SLOTS_PER_PART = 2

/** The weapon slot: an extra slot on the component that fires the lasers. It only takes weapon modules. */
const val WEAPON_SLOT = MODULE_SLOTS_PER_PART

private const val TOTAL_SLOTS = MODULE_SLOTS_PER_PART + 1

/** Upgrade modules that can be dragged into a component's slots in the Mk9 Configurator. They are won from `/wheel`. */
enum class UpgradeModule(
    val displayName: String,
    val material: Material,
    val color: NamedTextColor,
    val description: String,
    /** Extra hit points the component this is installed in gets (0 for non-plating modules). */
    val plateHp: Int = 0,
    /** A weapon module: it only fits the weapon slot, and the weapon slot only takes weapon modules. */
    val weaponOnly: Boolean = false,
    /** Relative chance of coming up on the wheel, among the modules. */
    val weight: Int,
) {
    SELF_HEALING("Self-Healing Nanoparticles", Material.SLIME_BALL, NamedTextColor.GREEN,
        "Heals its component by 0.5 hit points per second", weight = 10),
    COPPER_PLATING("Copper Plating", Material.COPPER_INGOT, NamedTextColor.GOLD,
        "+1 hit point on its component", plateHp = 1, weight = 20),
    BRASS_PLATING("Brass Plating", Material.GOLD_INGOT, NamedTextColor.YELLOW,
        "+2 hit points on its component", plateHp = 2, weight = 16),
    STEEL_PLATING("Steel Plating", Material.IRON_INGOT, NamedTextColor.GRAY,
        "+3 hit points on its component", plateHp = 3, weight = 12),
    TITANIUM_PLATING("Titanium Plating", Material.NETHERITE_SCRAP, NamedTextColor.WHITE,
        "+4 hit points on its component", plateHp = 4, weight = 7),
    COBALT_PLATING("Cobalt Plating", Material.LAPIS_LAZULI, NamedTextColor.BLUE,
        "+5 hit points on its component", plateHp = 5, weight = 4),
    BEAM_SPLITTER("Beam Splitter", Material.PRISMARINE_CRYSTALS, NamedTextColor.AQUA,
        "Splits each laser into 3 smaller lasers that spread like a shotgun. Only fits a weapon slot",
        weaponOnly = true, weight = 6);

    val isPlating get() = plateHp > 0
}

/** The modules installed in each component of a robot: two general slots, plus the weapon slot on the firepower component. */
class RobotUpgrades {
    /** Component id -> the name of the module in each slot ("" when a slot is empty). */
    val slots = mutableMapOf<String, MutableList<String>>()

    fun get(partId: String, slot: Int): UpgradeModule? {
        val name = slots[partId]?.getOrNull(slot) ?: return null
        return UpgradeModule.entries.find { it.name == name }
    }

    fun modulesOn(partId: String): List<UpgradeModule> = (0 until TOTAL_SLOTS).mapNotNull { get(partId, it) }

    fun has(partId: String, module: UpgradeModule) = module in modulesOn(partId)

    fun set(partId: String, slot: Int, module: UpgradeModule?) {
        val list = slots.getOrPut(partId) { MutableList(TOTAL_SLOTS) { "" } }
        while (list.size < TOTAL_SLOTS) list.add("")
        list[slot] = module?.name ?: ""
        if (list.all { it.isEmpty() }) slots.remove(partId)
    }

    /** Extra hit points the plating in this component's slots adds. */
    fun bonusHp(partId: String) = modulesOn(partId).sumOf { it.plateHp }

    fun selfHealing(partId: String) = has(partId, UpgradeModule.SELF_HEALING)

    fun count() = slots.keys.sumOf { modulesOn(it).size }

    /** Empties every slot, returning the modules that were in them. */
    fun removeAll(): List<UpgradeModule> {
        val all = slots.keys.toList().flatMap { modulesOn(it) }
        slots.clear()
        return all
    }
}

/** The component that fires the lasers: a droid's cannon arm, otherwise the core. */
fun SpiderOptions.firepowerRole() = if (creature == CreatureType.DROID) PartRole.CANNON_ARM else PartRole.CORE

/** The component that carries the weapon slot. */
fun SpiderOptions.weaponPart(): PartSpec? = partSpecs().firstOrNull { it.role == firepowerRole() }

/** The beam splitter works while it sits in the weapon slot of a firepower component that has not been knocked out. */
fun SpiderOptions.beamSplitterActive() = partSpecs().any {
    it.role == firepowerRole() && damage.of(it.id) > 0.0 && upgrades.get(it.id, WEAPON_SLOT) == UpgradeModule.BEAM_SPLITTER
}
