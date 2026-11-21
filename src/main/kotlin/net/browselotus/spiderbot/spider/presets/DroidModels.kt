package net.browselotus.spiderbot.spider.presets

import net.browselotus.spiderbot.spider.configuration.BodyPlan
import net.browselotus.spiderbot.utilities.BlockDisplayModelPiece
import net.browselotus.spiderbot.utilities.DisplayModel
import org.bukkit.Material
import org.bukkit.entity.Display
import org.joml.Matrix4f
import kotlin.math.max
import kotlin.math.min

/**
 * The humanoid look of the Droid, built out of boxes, with a few rotated pieces for faceted joints.
 *
 * The torso is authored around the pelvis (the body origin): +Y is up, +Z is forward and +X is the
 * droid's left. Pieces tagged "cloak" are the paintable armour (recolored by the body color setting,
 * and tinted by the cloak), "eye" pieces get the animated glow color, and "blinking_lights" the
 * flickering accent lights.
 *
 * Leg segments are authored along +Z (the direction the segment points), with +Y being the direction the
 * droid faces, so a segment hanging straight down has its front along +Y.
 *
 * Keep the cannon tip (x = 0.35, y = 0.20, z ~ 0.53) in line with CreatureType.DROID's muzzle offset.
 */
object DroidModels {
    private val PAINT = Material.WHITE_CONCRETE
    private val DARK = Material.GRAY_CONCRETE
    private val BLACK = Material.BLACK_CONCRETE
    private val STEEL = Material.IRON_BLOCK
    private val TRIM = Material.LIGHT_GRAY_CONCRETE

    private val SIDES = doubleArrayOf(1.0, -1.0)

    /** An axis aligned box. The corners may be given in any order. */
    private fun box(
        material: Material,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        tags: List<String> = emptyList(),
        brightness: Display.Brightness? = null,
    ) = rbox(material, x0, y0, z0, x1, y1, z1, 0.0, 0.0, 0.0, tags, brightness)

    /** A box rotated by [rx], [ry], [rz] degrees around its own centre. */
    private fun rbox(
        material: Material,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        rx: Double, ry: Double, rz: Double,
        tags: List<String> = emptyList(),
        brightness: Display.Brightness? = null,
    ): BlockDisplayModelPiece {
        val minX = min(x0, x1); val maxX = max(x0, x1)
        val minY = min(y0, y1); val maxY = max(y0, y1)
        val minZ = min(z0, z1); val maxZ = max(z0, z1)
        return BlockDisplayModelPiece(
            block = material.createBlockData(),
            transform = Matrix4f()
                .translate(((minX + maxX) / 2).toFloat(), ((minY + maxY) / 2).toFloat(), ((minZ + maxZ) / 2).toFloat())
                .rotateXYZ(Math.toRadians(rx).toFloat(), Math.toRadians(ry).toFloat(), Math.toRadians(rz).toFloat())
                .scale((maxX - minX).toFloat(), (maxY - minY).toFloat(), (maxZ - minZ).toFloat())
                .translate(-0.5f, -0.5f, -0.5f),
            brightness = brightness,
            tags = tags,
        )
    }

    /** Armour that takes the droid's body color. */
    private fun paint(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
        box(PAINT, x0, y0, z0, x1, y1, z1, tags = listOf("cloak"))

    /** Armour that takes the droid's body color, rotated. */
    private fun rpaint(
        x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
        rx: Double, ry: Double, rz: Double,
    ) = rbox(PAINT, x0, y0, z0, x1, y1, z1, rx, ry, rz, tags = listOf("cloak"))

    /** Glowing eye piece, animated by the renderer. */
    private fun eye(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
        box(Material.CYAN_SHULKER_BOX, x0, y0, z0, x1, y1, z1, tags = listOf("eye"), brightness = Display.Brightness(15, 15))

    /** Accent light piece, flickered by the renderer. */
    private fun light(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
        box(DARK, x0, y0, z0, x1, y1, z1, tags = listOf("blinking_lights"))

    /** A faceted ball joint: a cube plus a copy turned 45 degrees on two axes. */
    private fun joint(material: Material, cx: Double, cy: Double, cz: Double, h: Double) = listOf(
        box(material, cx - h, cy - h, cz - h, cx + h, cy + h, cz + h),
        rbox(material, cx - h * 0.9, cy - h * 0.9, cz - h * 0.9, cx + h * 0.9, cy + h * 0.9, cz + h * 0.9, 45.0, 45.0, 0.0),
    )

    fun torso(): DisplayModel {
        val p = mutableListOf<BlockDisplayModelPiece>()

        // ---------- pelvis and belt ----------
        p += box(DARK, -0.20, -0.12, -0.12, 0.20, 0.06, 0.12)
        p += paint(-0.055, -0.13, 0.12, 0.055, 0.03, 0.15)           // front guard
        p += paint(-0.16, -0.10, -0.14, 0.16, 0.03, -0.115)          // rear guard
        p += box(BLACK, -0.21, 0.04, -0.13, 0.21, 0.08, 0.13)        // belt
        p += box(STEEL, -0.04, 0.035, 0.13, 0.04, 0.085, 0.145)      // buckle
        p += light(-0.012, 0.052, 0.145, 0.012, 0.068, 0.152)

        // ---------- waist: black core with ribbed rings and two small pistons ----------
        p += box(BLACK, -0.10, 0.08, -0.08, 0.10, 0.20, 0.08)
        p += box(DARK, -0.115, 0.10, -0.095, 0.115, 0.12, 0.095)
        p += box(DARK, -0.115, 0.16, -0.095, 0.115, 0.18, 0.095)
        for (s in SIDES) p += box(STEEL, 0.05 * s, 0.08, 0.09, 0.065 * s, 0.20, 0.105)

        // ---------- chest ----------
        p += box(DARK, -0.23, 0.20, -0.13, 0.23, 0.60, 0.13)         // inner frame
        p += paint(-0.21, 0.20, -0.15, 0.21, 0.40, 0.15)             // lower plate
        for (s in SIDES) p += paint(0.02 * s, 0.40, -0.14, 0.25 * s, 0.60, 0.16)   // pecs
        p += box(BLACK, -0.025, 0.40, 0.13, 0.025, 0.60, 0.155)      // sternum groove
        p += paint(-0.20, 0.60, -0.12, 0.20, 0.65, 0.12)             // collar

        // pec vents
        for (s in SIDES) for (i in 0..2) {
            val y = 0.45 + 0.04 * i
            p += box(BLACK, 0.14 * s, y, 0.16, 0.23 * s, y + 0.015, 0.17)
        }

        // reactor: black bezel around a glowing core
        p += box(BLACK, -0.065, 0.335, 0.15, 0.065, 0.365, 0.17)
        p += box(BLACK, -0.065, 0.235, 0.15, 0.065, 0.265, 0.17)
        p += box(BLACK, 0.035, 0.265, 0.15, 0.065, 0.335, 0.17)
        p += box(BLACK, -0.065, 0.265, 0.15, -0.035, 0.335, 0.17)
        p += light(-0.035, 0.265, 0.15, 0.035, 0.335, 0.165)
        for (s in SIDES) p += light(0.11 * s, 0.295, 0.15, 0.135 * s, 0.315, 0.162)

        // ---------- backpack with thrusters, vents and antennas ----------
        p += box(DARK, -0.16, 0.26, -0.24, 0.16, 0.56, -0.12)
        p += paint(-0.15, 0.56, -0.235, 0.15, 0.60, -0.13)
        for (i in 0..2) {
            val y = 0.33 + 0.07 * i
            p += box(BLACK, -0.12, y, -0.25, 0.12, y + 0.02, -0.24)
        }
        for (s in SIDES) {
            p += box(BLACK, 0.06 * s, 0.17, -0.22, 0.12 * s, 0.26, -0.16)     // thruster nozzle
            p += light(0.07 * s, 0.16, -0.21, 0.11 * s, 0.17, -0.17)
            p += box(STEEL, 0.12 * s, 0.60, -0.20, 0.135 * s, 0.80, -0.185)   // antenna rod
        }
        p += light(0.11, 0.80, -0.205, 0.145, 0.83, -0.18)

        // ---------- neck ----------
        p += box(BLACK, -0.05, 0.65, -0.05, 0.05, 0.73, 0.05)
        p += box(DARK, -0.065, 0.66, -0.065, 0.065, 0.675, 0.065)
        p += box(DARK, -0.065, 0.70, -0.065, 0.065, 0.715, 0.065)

        // ---------- head ----------
        p += box(DARK, -0.11, 0.73, -0.10, 0.11, 0.775, 0.125)       // jaw
        for (x in doubleArrayOf(-0.04, -0.005, 0.03)) p += box(BLACK, x, 0.735, 0.125, x + 0.015, 0.77, 0.135)
        p += box(STEEL, -0.04, 0.72, 0.08, 0.04, 0.735, 0.125)       // chin
        p += paint(-0.15, 0.775, -0.14, 0.15, 0.97, 0.14)            // skull
        p += paint(-0.145, 0.93, 0.14, 0.145, 0.96, 0.17)            // brow
        p += box(BLACK, -0.13, 0.82, 0.14, 0.13, 0.93, 0.16)         // visor
        p += eye(0.035, 0.855, 0.16, 0.105, 0.915, 0.175)
        p += eye(-0.105, 0.855, 0.16, -0.035, 0.915, 0.175)
        p += box(DARK, -0.10, 0.80, -0.155, 0.10, 0.94, -0.14)       // back panel
        p += box(DARK, -0.02, 0.97, -0.10, 0.02, 0.99, 0.08)         // crest

        // ear modules
        for (s in SIDES) {
            p += box(DARK, 0.15 * s, 0.82, -0.065, 0.19 * s, 0.92, 0.065)
            p += box(STEEL, 0.19 * s, 0.85, -0.03, 0.20 * s, 0.89, 0.03)
        }
        p += light(0.20, 0.865, -0.01, 0.205, 0.875, 0.01)

        // antennas
        p += box(DARK, 0.06, 0.97, -0.035, 0.11, 0.99, 0.025)
        p += box(STEEL, 0.075, 0.99, -0.015, 0.095, 1.14, 0.005)
        p += light(0.07, 1.14, -0.02, 0.10, 1.18, 0.01)
        p += box(STEEL, -0.09, 0.97, -0.01, -0.075, 1.06, 0.005)

        // ---------- shoulders and upper arms (both sides) ----------
        for (s in SIDES) {
            p += joint(DARK, 0.335 * s, 0.50, 0.0, 0.065)                                  // shoulder socket
            p += paint(0.27 * s, 0.56, -0.12, 0.43 * s, 0.64, 0.12)                        // pad
            p += paint(0.40 * s, 0.46, -0.10, 0.45 * s, 0.60, 0.10)                        // outer plate
            p += box(BLACK, 0.30 * s, 0.64, -0.02, 0.40 * s, 0.65, 0.02)                   // pad stripe
            p += light(0.33 * s, 0.58, 0.12, 0.37 * s, 0.60, 0.13)

            p += box(DARK, 0.315 * s, 0.20, -0.04, 0.385 * s, 0.46, 0.04)                  // upper arm core
            p += paint(0.295 * s, 0.31, -0.065, 0.405 * s, 0.44, 0.065)                    // bicep armour
            p += box(BLACK, 0.31 * s, 0.285, -0.055, 0.39 * s, 0.305, 0.055)              // band
            p += joint(DARK, 0.35 * s, 0.20, 0.0, 0.055)                                   // elbow
        }

        // ---------- right arm: forearm and a relaxed hand ----------
        run {
            val s = -1.0
            p += paint(0.29 * s, 0.03, -0.065, 0.41 * s, 0.145, 0.065)                     // forearm
            p += box(BLACK, 0.315 * s, -0.02, -0.045, 0.385 * s, 0.03, 0.045)              // wrist
            p += box(DARK, 0.305 * s, -0.115, -0.04, 0.395 * s, -0.02, 0.045)              // palm
            p += box(BLACK, 0.305 * s, -0.19, -0.04, 0.395 * s, -0.115, -0.015)            // fingers
            p += box(BLACK, 0.305 * s, -0.19, -0.01, 0.395 * s, -0.115, 0.015)
            p += box(BLACK, 0.305 * s, -0.19, 0.02, 0.395 * s, -0.115, 0.045)
            p += box(DARK, 0.285 * s, -0.13, 0.0, 0.305 * s, -0.06, 0.03)                  // thumb
        }

        // ---------- left arm: forearm cannon pointing forward ----------
        run {
            p += paint(0.295, 0.14, 0.055, 0.405, 0.26, 0.24)                              // forearm shell
            p += box(DARK, 0.29, 0.135, 0.24, 0.41, 0.265, 0.275)                          // collar
            p += box(BLACK, 0.315, 0.16, 0.275, 0.385, 0.24, 0.40)                         // barrel housing
            for (z in doubleArrayOf(0.295, 0.325, 0.355)) p += box(DARK, 0.305, 0.15, z, 0.395, 0.25, z + 0.015)   // heat fins
            p += box(STEEL, 0.335, 0.18, 0.40, 0.365, 0.22, 0.50)                          // barrel
            p += box(BLACK, 0.325, 0.17, 0.47, 0.375, 0.23, 0.52)                          // muzzle brake
            p += light(0.335, 0.185, 0.52, 0.365, 0.215, 0.535)                            // glowing tip
            p += box(DARK, 0.405, 0.15, 0.08, 0.43, 0.25, 0.22)                            // battery pack
            p += light(0.43, 0.19, 0.12, 0.435, 0.21, 0.18)
            p += box(BLACK, 0.335, 0.26, 0.10, 0.365, 0.28, 0.17)                          // sight
            p += box(DARK, 0.33, 0.125, 0.06, 0.37, 0.14, 0.22)                            // under rail
        }

        return DisplayModel(p)
    }

    /** The upper leg, from the hip to the knee. */
    fun thigh(length: Double): DisplayModel {
        val p = mutableListOf<BlockDisplayModelPiece>()
        val l = length

        // hip joint
        p += box(BLACK, -0.085, -0.085, -0.05, 0.085, 0.085, 0.07)
        p += rbox(DARK, -0.07, -0.07, -0.04, 0.07, 0.07, 0.06, 45.0, 0.0, 45.0)

        // armour: front plate, two side plates over a dark core
        p += box(DARK, -0.06, -0.065, 0.06, 0.06, 0.075, l - 0.06)
        p += paint(-0.075, 0.04, 0.07, 0.075, 0.09, l - 0.10)
        for (s in SIDES) p += paint(0.065 * s, -0.06, 0.09, 0.09 * s, 0.06, l - 0.14)
        p += box(BLACK, -0.07, 0.04, 0.20, 0.07, 0.09, 0.215)        // groove across the front plate

        // rear hydraulic piston
        p += box(BLACK, -0.03, -0.10, 0.10, 0.03, -0.065, 0.28)
        p += box(STEEL, -0.012, -0.095, 0.28, 0.012, -0.07, l - 0.08)

        // knee: black ring, faceted joint and a kneecap
        p += box(BLACK, -0.085, -0.085, l - 0.10, 0.085, 0.085, l - 0.05)
        p += joint(DARK, 0.0, 0.0, l, 0.07)
        p += paint(-0.06, 0.085, l - 0.10, 0.06, 0.12, l + 0.03)

        return DisplayModel(p.onEach { it.tags = it.tags + listOf("leg", "thigh") })
    }

    /** The lower leg, from the knee to the foot. The end of the segment is where the foot meets the ground. */
    fun shin(length: Double): DisplayModel {
        val p = mutableListOf<BlockDisplayModelPiece>()
        val l = length
        val calfEnd = l * 0.62

        // calf: wide upper armour, narrower lower shin, front guard
        p += paint(-0.075, -0.07, 0.05, 0.075, 0.075, calfEnd)
        p += paint(-0.055, -0.05, calfEnd, 0.055, 0.06, l - 0.12)
        p += paint(-0.045, 0.06, 0.10, 0.045, 0.085, l - 0.14)
        p += box(DARK, -0.045, -0.04, 0.05, 0.045, 0.05, l - 0.12)   // dark core showing in the seams

        // side vents
        for (s in SIDES) for (i in 0..2) {
            val z = 0.12 + 0.04 * i
            p += box(BLACK, 0.075 * s, -0.04, z, 0.085 * s, 0.04, z + 0.015)
        }

        // calf muscle block with a vent light, and an achilles piston
        p += box(BLACK, -0.04, -0.10, 0.08, 0.04, -0.07, 0.22)
        p += light(-0.02, -0.105, 0.12, 0.02, -0.10, 0.18)
        p += box(STEEL, -0.012, -0.085, 0.22, 0.012, -0.06, l - 0.10)

        // ankle joint
        p += box(DARK, -0.08, -0.07, l - 0.11, 0.08, 0.07, l - 0.045)
        p += rbox(DARK, -0.07, -0.065, l - 0.10, 0.07, 0.065, l - 0.05, 45.0, 0.0, 45.0)

        // foot: heel, arch, toe cap with a black tip, and a wide sole
        p += box(DARK, -0.085, -0.09, l - 0.07, 0.085, 0.02, l - 0.03)
        p += box(DARK, -0.09, 0.02, l - 0.05, 0.09, 0.12, l - 0.025)
        p += paint(-0.085, 0.12, l - 0.06, 0.085, 0.17, l - 0.025)
        p += box(BLACK, -0.085, 0.17, l - 0.04, 0.085, 0.205, l - 0.015)
        p += box(BLACK, -0.10, -0.10, l - 0.03, 0.10, 0.21, l)
        p += light(-0.03, -0.095, l - 0.065, 0.03, -0.09, l - 0.045)

        return DisplayModel(p.onEach { it.tags = it.tags + listOf("leg", "shin") })
    }

    /** Gives every leg of [bodyPlan] a thigh model on its first segment and a shin model on its second. */
    fun applyLegModels(bodyPlan: BodyPlan) {
        for (leg in bodyPlan.legs) {
            leg.segments.getOrNull(0)?.let { it.model = thigh(it.length) }
            leg.segments.getOrNull(1)?.let { it.model = shin(it.length) }
        }
    }
}