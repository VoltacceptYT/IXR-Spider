package net.browselotus.spiderbot.spider.presets

import org.bukkit.DyeColor
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display

private fun DyeColor.material(suffix: String): Material {
    return Material.matchMaterial("${name}_$suffix") ?: error("No $suffix block exists for dye color $name")
}

/** The concrete block matching this dye color — used to recolor the spider's body. */
fun DyeColor.concreteBlockData(): BlockData = material("CONCRETE").createBlockData()

/** A palette of glowing blocks in this dye color, used for the spider's animated eyes. */
fun eyePaletteFor(dye: DyeColor): List<Pair<BlockData, Display.Brightness>> {
    val shulker = dye.material("SHULKER_BOX").createBlockData()
    val concrete = dye.material("CONCRETE").createBlockData()
    val concretePowder = dye.material("CONCRETE_POWDER").createBlockData()

    return listOf(
        *Array(3) { shulker },
        concrete,
        concretePowder,
    ).map { it to Display.Brightness(15, 15) }
}

/** A palette that flickers between off (black) and this dye color, used for blinking accent lights. */
fun blinkingPaletteFor(dye: DyeColor): List<Pair<BlockData, Display.Brightness>> {
    val shulker = dye.material("SHULKER_BOX").createBlockData()
    val concrete = dye.material("CONCRETE").createBlockData()
    val concretePowder = dye.material("CONCRETE_POWDER").createBlockData()

    return listOf(
        *Array(3) { Material.BLACK_SHULKER_BOX.createBlockData() to Display.Brightness(0, 15) },
        *Array(3) { shulker to Display.Brightness(15, 15) },
        concrete to Display.Brightness(15, 15),
        concretePowder to Display.Brightness(15, 15),
    )
}
