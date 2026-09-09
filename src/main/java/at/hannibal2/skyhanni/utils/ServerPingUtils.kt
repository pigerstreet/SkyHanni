package at.hannibal2.skyhanni.utils

import net.minecraft.client.Minecraft
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The round trip time between this client and the server.
 *
 * This lives in its own file instead of in `MinecraftCompat` on purpose: that file is touched often upstream, while a
 * file that only exists in this fork can never cause a merge conflict when syncing.
 */
object ServerPingUtils {

    /**
     * The round trip time to the server, as reported in the player list.
     *
     * Returns [Duration.ZERO] when not connected to a server, or when the server does not report a real value.
     */
    val ping: Duration
        get() {
            val minecraft = Minecraft.getInstance()
            val latency = minecraft.connection?.getPlayerInfo(minecraft.user.profileId)?.latency ?: 0
            return latency.coerceAtLeast(0).milliseconds
        }
}
