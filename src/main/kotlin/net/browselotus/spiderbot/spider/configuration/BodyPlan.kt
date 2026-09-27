package net.browselotus.spiderbot.spider.configuration

import net.browselotus.spiderbot.spider.presets.AnimatedPalettes
import net.browselotus.spiderbot.spider.presets.SpiderTorsoModels
import net.browselotus.spiderbot.spider.presets.blinkingPaletteFor
import net.browselotus.spiderbot.spider.presets.concreteBlockData
import net.browselotus.spiderbot.spider.presets.eyePaletteFor
import net.browselotus.spiderbot.utilities.DisplayModel
import org.bukkit.DyeColor
import org.bukkit.util.Vector

class SegmentPlan(
    var length: Double,
    var initDirection: Vector,
    var model: DisplayModel = DisplayModel(listOf())
) {
    fun clone() = SegmentPlan(length, initDirection.clone(), model.clone())
}

class LegPlan(
    var attachmentPosition: Vector,
    var restPosition: Vector,
    var segments: List<SegmentPlan>,
)

class BodyPlan {
    var scale = 1.0
    var legs = emptyList<LegPlan>()

    var bodyModel = SpiderTorsoModels.EMPTY.model.clone()

    var eyePalette = AnimatedPalettes.CYAN_EYES.palette
    var blinkingPalette = AnimatedPalettes.CYAN_BLINKING_LIGHTS.palette

    // Player-customizable appearance. These track the *current* selection so
    // a customization GUI can show which option is active; the actual look
    // is applied by the setters below.
    var bodyColor: DyeColor = DyeColor.WHITE; private set
    var glowColor: DyeColor = DyeColor.CYAN; private set

    /**
     * Recolors the body's paintable surface (the pieces tagged "cloak" on
     * the torso and legs, which default to white) to the given dye color.
     * These are the same pieces the Cloak component tints for camouflage,
     * so this just changes their resting color.
     */
    fun setBodyColor(dye: DyeColor) {
        bodyColor = dye
        val block = dye.concreteBlockData()

        val pieces = bodyModel.pieces.filter { it.tags.contains("cloak") } +
            legs.flatMap { leg -> leg.segments.flatMap { it.model.pieces.filter { piece -> piece.tags.contains("cloak") } } }

        for (piece in pieces) piece.block = block
    }

    /** Recolors the animated eyes and blinking accent lights to the given dye color. */
    fun setGlowColor(dye: DyeColor) {
        glowColor = dye
        eyePalette = eyePaletteFor(dye)
        blinkingPalette = blinkingPaletteFor(dye)
    }

    fun scale(scale: Double) {
        this.scale *= scale
        bodyModel.scale(scale.toFloat())
        legs.forEach {
            it.attachmentPosition.multiply(scale)
            it.restPosition.multiply(scale)
            it.segments.forEach { segment ->
                segment.length *= scale
                segment.model.scale(scale.toFloat())
            }
        }
    }
}