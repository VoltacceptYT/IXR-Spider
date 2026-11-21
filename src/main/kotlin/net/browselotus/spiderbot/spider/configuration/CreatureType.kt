package net.browselotus.spiderbot.spider.configuration

import org.bukkit.Sound
import org.bukkit.util.Vector
import java.util.Base64

/** Wraps a textures.minecraft.net skin URL into the base64 "textures" property value that player heads expect. */
private fun skinTextureValue(url: String): String {
    val json = """{"textures":{"SKIN":{"url":"$url"}}}"""
    return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
}

/**
 * The kinds of robotic creature the plugin can spawn. Every creature runs on the exact same body,
 * walking, targeting and shooting systems; this only describes what makes each one look and sound
 * different: what its shots look like, where they leave the body, and which sounds it makes.
 */
enum class CreatureType(
    val displayName: String,

    /** Base64 "textures" value of the player head rendered as this creature's projectile. */
    val shotTexture: String,

    /** Where shots leave the body, relative to the body centre at scale 1.0 (x = left, y = up, z = forward). */
    private val muzzleX: Double,
    private val muzzleY: Double,
    private val muzzleZ: Double,

    val shootSound: Sound,
    val shootVolume: Float,
    val shootPitch: Float,

    val hitSound: Sound,
    val hitVolume: Float,
    val hitPitch: Float,
) {
    SPIDER(
        displayName = "Spider",
        shotTexture = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmI1ODY0MWU3NmRhODI2MjE3MDhhM2Q2YzEwYmI0NTBjMjNkNDc1ZTUyMTAzMTBkMGI4N2U0NjBhNWZjMjM1NCJ9fX0=",
        muzzleX = 0.0, muzzleY = 0.3, muzzleZ = 0.6,
        shootSound = Sound.ENTITY_SPIDER_HURT, shootVolume = 0.6f, shootPitch = 1.4f,
        hitSound = Sound.BLOCK_COBWEB_HIT, hitVolume = 0.6f, hitPitch = 1.0f,
    ),

    DROID(
        displayName = "Droid",
        shotTexture = skinTextureValue(
            "http://textures.minecraft.net/texture/25dffc5e53c837385f5d7863935bc5afa3e4a8176ae52e2f8d7d797c952e3f"
        ),
        // the end of the Droid's cannon arm, see DroidModels
        muzzleX = 0.35, muzzleY = 0.20, muzzleZ = 0.55,
        shootSound = Sound.ENTITY_BLAZE_SHOOT, shootVolume = 0.5f, shootPitch = 1.8f,
        hitSound = Sound.ENTITY_FIREWORK_ROCKET_BLAST, hitVolume = 0.5f, hitPitch = 1.4f,
    );

    /** A fresh copy each time, since [Vector] is mutable. */
    val muzzleOffset: Vector get() = Vector(muzzleX, muzzleY, muzzleZ)
}
