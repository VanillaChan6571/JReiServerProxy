package com.xinian.jreiproxyserver.network

/**
 * The plugin-message channels JEI and REI actually speak on Minecraft 26.2.
 *
 * Both mods dropped their single multiplexed channel when Minecraft moved to `CustomPacketPayload`:
 * every packet now has its own channel and its own `StreamCodec`, with no packet id byte and no
 * handshake. The payload layouts below are mirrored from the mods' own stream codecs.
 */
object Channels {

    /** JEI, client -> server. */
    object Jei {
        /** No payload. Asks whether the player may use cheat mode. */
        const val REQUEST_CHEAT_PERMISSION = "jei:request_cheat_permission"

        /** `ItemStack` — the stack the client believes it is carrying. */
        const val DELETE_PLAYER_ITEM = "jei:delete_player_item"

        /** `ItemStack`, `GiveMode` (varint ordinal). */
        const val GIVE_ITEM_STACK = "jei:give_item_stack"

        /** `ItemStack`, varint hotbar slot. */
        const val SET_HOTBAR_ITEM_STACK = "jei:set_hotbar_item_stack"

        /** `list<(varint from, varint to)>`, `list<varint>`, `list<varint>`, bool, bool. */
        const val RECIPE_TRANSFER = "jei:recipe_transfer"

        /** As above, but each operation also carries a varint count. */
        const val RECIPE_TRANSFER_COUNTED = "jei:recipe_transfer_counted"

        /** Server -> client: bool hasPermission, `list<string>` allowed cheat methods. */
        const val CHEAT_PERMISSION = "jei:cheat_permission"

        val INCOMING = listOf(
            REQUEST_CHEAT_PERMISSION,
            DELETE_PLAYER_ITEM,
            GIVE_ITEM_STACK,
            SET_HOTBAR_ITEM_STACK,
            RECIPE_TRANSFER,
            RECIPE_TRANSFER_COUNTED,
        )

        val OUTGOING = listOf(CHEAT_PERMISSION)
    }

    /** REI, client -> server. REI keeps the `roughlyenoughitems` namespace for its cheat packets. */
    object Rei {
        /** No payload. */
        const val DELETE_ITEM = "roughlyenoughitems:delete_item"

        /** Optional `ItemStack` — give into the inventory. */
        const val CREATE_ITEM = "roughlyenoughitems:create_item"

        /** Optional `ItemStack` — give onto the cursor. */
        const val CREATE_ITEM_GRAB = "roughlyenoughitems:create_item_grab"

        /** Optional `ItemStack`, varint hotbar slot. */
        const val CREATE_ITEM_HOTBAR = "roughlyenoughitems:create_item_hotbar"

        /**
         * REI's recipe transfer: `Identifier` category, bool stacked, `CompoundTag` body.
         *
         * REI decides whether to offer quick crafting purely from whether the server registered
         * this channel, so it must not be advertised unless the transfer is actually handled.
         */
        const val MOVE_ITEMS = "roughlyenoughitems:move_items_new"

        val INCOMING = listOf(
            DELETE_ITEM,
            CREATE_ITEM,
            CREATE_ITEM_GRAB,
            CREATE_ITEM_HOTBAR,
            MOVE_ITEMS,
        )

        /**
         * REI's namespace. A client announces only the channels it can *receive*, which are REI's
         * clientbound ones (`ci_msg`, `sync_displays`, `og_not_enough`, `request_tags_s2c`), never
         * the serverbound ones above — so presence is tested by namespace, not by a fixed list.
         */
        const val NAMESPACE = "roughlyenoughitems:"
    }

    /**
     * The mod loaders' own recipe channels. Since Minecraft 1.21.2 the vanilla server no longer
     * sends recipe data, and each loader added its own replacement; these are what a recipe viewer
     * actually reads its recipes from.
     */
    object Loader {
        /** Fabric API's `fabric-recipe-api-v1`. Present on Minecraft 1.21.10 and newer. */
        const val FABRIC_RECIPE_SYNC = "fabric:recipe_sync"

        /** NeoForge's equivalent. */
        const val NEOFORGE_RECIPE_CONTENT = "neoforge:recipe_content"
    }
}
