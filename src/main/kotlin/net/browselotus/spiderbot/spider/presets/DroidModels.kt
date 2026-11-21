package net.browselotus.spiderbot.spider.presets

import net.browselotus.spiderbot.spider.configuration.BodyPlan
import net.browselotus.spiderbot.utilities.BlockDisplayModelPiece
import net.browselotus.spiderbot.utilities.DisplayModel
import org.bukkit.Material
import org.bukkit.entity.Display
import org.joml.Matrix4f

/**
 * The humanoid look of the Droid, built out of plain boxes.
 *
 * The torso is authored around the pelvis (the body origin): +Y is up, +Z is forward and +X is the
 * droid's left. Pieces tagged "cloak" are the paintable armour (recolored by the body color setting,
 * and tinted by the cloak), "eye" pieces get the animated glow color, and "blinking_lights" the
 * flickering accent lights.
 *
 * Leg segments are authored along +Z (the direction the segment points), with +Y being the direction the
 * droid faces, so a segment hanging straight down has its front along +Y.
 */
object DroidModels {
    private val PAINT = Material.WHITE_CONCRETE
    private val DARK = Material.GRAY_CONCRETE
    private val BLACK = Material.BLACK_CONCRETE

    private fun box(
        material: Material,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        tags: List<String> = emptyList(),
        brightness: Display.Brightness? = null,
    ) = BlockDisplayModelPiece(
        block = material.createBlockData(),
        transform = Matrix4f()
            .translate(x0.toFloat(), y0.toFloat(), z0.toFloat())
            .scale((x1 - x0).toFloat(), (y1 - y0).toFloat(), (z1 - z0).toFloat()),
        brightness = brightness,
        tags = tags,
    )

    /** Armour that takes the droid's body color. */
    private fun paint(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
        box(PAINT, x0, y0, z0, x1, y1, z1, tags = listOf("cloak"))

    /** Glowing eye piece, animated by the renderer. */
    private fun eye(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
        box(Material.CYAN_SHULKER_BOX, x0, y0, z0, x1, y1, z1, tags = listOf("eye"), brightness = Display.Brightness(15, 15))

    /** Accent light piece, flickered by the renderer. */
    private fun light(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
        box(DARK, x0, y0, z0, x1, y1, z1, tags = listOf("blinking_lights"))

    fun torso(): DisplayModel {
        val pieces = mutableListOf<BlockDisplayModelPiece>()

        // pelvis and waist
        pieces += box(DARK, -0.20, -0.12, -0.12, 0.20, 0.06, 0.12)
        pieces += paint(-0.17, -0.02, 0.12, 0.17, 0.06, 0.15)
        pieces += box(BLACK, -0.10, 0.06, -0.08, 0.10, 0.20, 0.08)

        // chest, with a glowing reactor core on the front and a backpack
        pieces += paint(-0.26, 0.20, -0.15, 0.26, 0.62, 0.15)
        pieces += box(DARK, -0.20, 0.26, 0.15, 0.20, 0.52, 0.17)
        pieces += light(-0.06, 0.34, 0.17, 0.06, 0.46, 0.19)
        pieces += box(DARK, -0.17, 0.26, -0.25, 0.17, 0.58, -0.15)

        // neck and head: visor with two glowing eyes, ear plates and an antenna
        pieces += box(BLACK, -0.05, 0.62, -0.05, 0.05, 0.70, 0.05)
        pieces += paint(-0.15, 0.70, -0.14, 0.15, 0.96, 0.14)
        pieces += box(BLACK, -0.13, 0.77, 0.14, 0.13, 0.90, 0.16)
        pieces += eye(0.03, 0.80, 0.16, 0.10, 0.87, 0.18)
        pieces += eye(-0.10, 0.80, 0.16, -0.03, 0.87, 0.18)
        pieces += box(DARK, 0.15, 0.76, -0.05, 0.19, 0.90, 0.05)
        pieces += box(DARK, -0.19, 0.76, -0.05, -0.15, 0.90, 0.05)
        pieces += box(DARK, 0.07, 0.96, -0.02, 0.10, 1.12, 0.01)
        pieces += light(0.06, 1.12, -0.03, 0.11, 1.17, 0.02)

        // shoulder pads
        pieces += paint(0.26, 0.46, -0.10, 0.42, 0.64, 0.10)
        pieces += paint(-0.42, 0.46, -0.10, -0.26, 0.64, 0.10)

        // right arm, hanging relaxed at its side
        pieces += box(DARK, -0.40, 0.20, -0.05, -0.30, 0.46, 0.05)
        pieces += paint(-0.41, -0.06, -0.06, -0.29, 0.20, 0.06)
        pieces += box(BLACK, -0.39, -0.14, -0.05, -0.31, -0.06, 0.05)

        // left arm, bent forward and ending in the cannon the droid shoots from.
        // The shots leave from the glowing tip, see CreatureType.DROID's muzzle offset.
        pieces += box(DARK, 0.30, 0.20, -0.05, 0.40, 0.46, 0.05)
        pieces += paint(0.295, 0.14, -0.055, 0.405, 0.26, 0.30)
        pieces += box(BLACK, 0.31, 0.15, 0.30, 0.39, 0.25, 0.50)
        pieces += light(0.33, 0.17, 0.50, 0.37, 0.23, 0.53)

        return DisplayModel(pieces)
    }

    /** The upper leg, from the hip to the knee. */
    fun thigh(length: Double): DisplayModel {
        val leg = listOf("leg", "thigh")
        return DisplayModel(listOf(
            paint(-0.075, -0.075, 0.04, 0.075, 0.075, length - 0.04),
            box(DARK, -0.05, 0.075, 0.10, 0.05, 0.095, length - 0.14),
            box(BLACK, -0.085, -0.085, -0.04, 0.085, 0.085, 0.08),
            box(BLACK, -0.09, -0.09, length - 0.08, 0.09, 0.09, length + 0.06),
            paint(-0.055, 0.09, length - 0.09, 0.055, 0.125, length + 0.03),
        ).onEach { it.tags = it.tags + leg })
    }

    /** The lower leg, from the knee to the foot. The end of the segment is where the foot meets the ground. */
    fun shin(length: Double): DisplayModel {
        val leg = listOf("leg", "shin")
        return DisplayModel(listOf(
            paint(-0.065, -0.065, 0.04, 0.065, 0.065, length - 0.08),
            box(DARK, -0.045, 0.065, 0.08, 0.045, 0.09, length - 0.20),
            box(DARK, -0.05, -0.095, 0.06, 0.05, -0.065, length - 0.14),
            box(BLACK, -0.075, -0.075, length - 0.11, 0.075, 0.075, length - 0.04),
            box(DARK, -0.10, -0.09, length - 0.06, 0.10, 0.20, length),
        ).onEach { it.tags = it.tags + leg })
    }

    /** Gives every leg of [bodyPlan] a thigh model on its first segment and a shin model on its second. */
    fun applyLegModels(bodyPlan: BodyPlan) {
        for (leg in bodyPlan.legs) {
            leg.segments.getOrNull(0)?.let { it.model = thigh(it.length) }
            leg.segments.getOrNull(1)?.let { it.model = shin(it.length) }
        }
    }
}
