package net.browselotus.spiderbot.spider.configuration

class AttackOptions {
    /** Whether the spider will automatically target and attack entities at all. */
    var enabled = true

    /** How close (in blocks) a hostile mob or provoked entity must be for the spider to notice it. */
    var detectRange = 16.0

    /** The spider gives up on its current target once it strays this far away. */
    var loseRange = 24.0

    /** Maximum distance (in blocks) the spider will fire a projectile from. */
    var attackRange = 12.0

    /** Distance the spider tries to keep from its target while attacking, so it snipes instead of walking into melee range. */
    var followDistance = 6.0

    /** Ticks between shots (20 ticks = 1 second). */
    var cooldownTicks = 40

    /** How many blocks the projectile travels per tick. */
    var projectileSpeed = 1.4

    /** How long a fired projectile can exist before disappearing if it hasn't hit anything, in ticks. */
    var projectileLifetimeTicks = 60

    /** How long the spider remembers an entity after its owner damages it, or is damaged by it, in ticks. */
    var aggroMemoryTicks = 200

    /** Damage dealt per hit. 2.0 damage = 1 heart, so this default (4.0) is 2 hearts. */
    var damage = 4.0

    /** Duration of the slowness effect applied on hit, in ticks (20 ticks = 1 second). */
    var slownessDurationTicks = 40

    /** Amplifier of the slowness effect applied on hit (0 = Slowness I, 1 = Slowness II, etc). */
    var slownessAmplifier = 1
}
