package net.browselotus.spiderbot.spider.components

import net.browselotus.spiderbot.utilities.ecs.Component
import java.util.UUID

/**
 * Marks a spider as belonging to a specific player (the one who spawned it).
 * Owned spiders default to following their owner, and are despawned when the
 * owner leaves the server.
 */
class Owner(val playerUUID: UUID) : Component
