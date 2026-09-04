// SPDX-License-Identifier: MIT
package dev.opendistress.wear.tile

import android.content.ComponentName
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.Typography.BODY_LARGE
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.launchAction
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import dev.opendistress.wear.MainActivity

/** A safe launcher surface: the Tile never creates, queues, or sends an alert. */
class OpenDistressTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest) =
        Futures.immediateFuture(
            Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(
                    Timeline.fromLayoutElement(
                        materialScope(this, requestParams.deviceConfiguration) {
                            primaryLayout(
                                titleSlot = { text("OpenDistress".layoutString) },
                                mainSlot = {
                                    text(
                                        "Open alert controls".layoutString,
                                        typography = BODY_LARGE,
                                    )
                                },
                                bottomSlot = {
                                    textEdgeButton(
                                        labelContent = { text("OPEN".layoutString) },
                                        onClick = clickable(
                                            action = launchAction(
                                                ComponentName(
                                                    this@OpenDistressTileService,
                                                    MainActivity::class.java,
                                                ),
                                                emptyMap(),
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    ),
                )
                .build(),
        )

    override fun onTileResourcesRequest(requestParams: ResourcesRequest) =
        Futures.immediateFuture(
            Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build(),
        )

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}
