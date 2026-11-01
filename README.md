# Blaze Slayer Helper

Passive, information-only HUD and profit tracker for the Hypixel SkyBlock Inferno Demonlord (Blaze Slayer) fight.

The mod does not automate gameplay, attacks, movement, or item use. It reads boss/entity state and chat messages, then displays useful timing, attunement, drop, and profit information.

## Features

- Shows the current Blaze Slayer attunement and remaining hits near the crosshair.
- Shows the Twilight Arrow Poison indicator when detected.
- Displays kill time, spawn time, and a short result popup after each boss.
- Tracks session boss counts, drops, average timings, profit, and profit per hour.
- Tracks Re-heated Gummy Polar Bear duration and warns before it expires.
- Prices Bazaar items using instant-sell prices and other items using lowest BIN.
- Applies Bazaar and Auction House taxes to profit calculations by default.
- Refreshes market data automatically every five minutes.

## Requirements

- Minecraft `26.3`
- Fabric Loader `0.19.5` or newer
- Fabric API
- Java `25` or newer

This is a client-side mod. It is intended for use with a Fabric client connected to Hypixel SkyBlock.

## Installation

1. Install Fabric Loader for Minecraft 26.3.
2. Install the matching Fabric API.
3. Download the `blazeslayerhelper` JAR from a release, or build it locally.
4. Put the JAR in your Minecraft `mods` directory.
5. Launch the Fabric client and join Hypixel SkyBlock.

## Commands

All commands use the `/blaze` prefix:

| Command | Description |
| --- | --- |
| `/blaze tracker` | Start a fresh session tracker, or stop the active tracker. |
| `/blaze drops` | Toggle the session/drop HUD. |
| `/blaze profit` | Print a full profit breakdown in chat. |
| `/blaze cost` | Show the current quest cost. |
| `/blaze cost <coins>` | Set the quest cost used in profit calculations. |
| `/blaze tax` | Toggle Bazaar/Auction House taxes in profit calculations. |
| `/blaze refresh` | Refresh market prices immediately. |
| `/blaze price <item>` | Show how an item is resolved and priced. |
| `/blaze ids` | Check all known Blaze Slayer drops against current market data. |
| `/blaze gummy` | Show the active gummy timer. |
| `/blaze gummy add` | Add one hour to the gummy timer. |
| `/blaze gummy clear` | Clear the gummy timer. |
| `/blaze swap` | Toggle hiding Hypixel's attunement swap display. |
| `/blaze debug` | Toggle diagnostic messages in chat. |
| `/blaze scan` | List nearby named entities and the mod's interpretation. |
| `/blaze reset` | Reset the current session statistics. |

The default quest cost is `150,000` coins, suitable for a Tier IV Inferno Demonlord quest.

## Pricing and data sources

Market data is downloaded in the background so the game thread is not blocked:

- Bazaar prices: Hypixel's public SkyBlock Bazaar API.
- Lowest BIN prices: Moulberry's `lowestbin.json`, with `lb.tricked.pro` as a fallback.
- Item-name mappings: Hypixel's public SkyBlock items resource.

Prices may be unavailable temporarily if an upstream service is down. Use `/blaze refresh` to retry. Fixed-value items and the guaranteed Derelict Ashes estimate are handled locally.

## Building from source

Clone the repository and run:

```bash
./gradlew build
```

The built mod JAR is written to `build/libs/`. A sources JAR is generated alongside it.

The project uses Gradle, Fabric Loom, Java 25, and Minecraft 26.3. To run the development client:

```bash
./gradlew runClient
```

## License

Released under the [MIT License](LICENSE).
