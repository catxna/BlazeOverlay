package com.example.blazeslayer;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blaze Slayer Helper (Fabric, Minecraft 26.3). Passive and information-only.
 *
 *  - Kill time (whole fight, all phases) + spawn time (quest start -> boss spawn),
 *    shown in front of you for 1s after the boss dies
 *  - Attunement + remaining hits shown just under the crosshair for the whole fight
 *  - Hypixel's own on-screen attunement text (dagger swap) is hidden
 *  - Session slayer drop counter
 *  - Profit tracker (T4 Blaze): drops valued at Bazaar insta-sell (Bazaar items) or lowest BIN
 *    (everything else), minus the quest cost per boss and the gummies you eat (Bazaar instant-buy). See PriceService.
 *
 * Profit: /blaze profit   (full breakdown in chat)
 *         /blaze cost N   (quest cost, default 150,000 = T4)
 *         /blaze tax      (toggle Bazaar/AH taxes in the profit maths)
 *         /blaze price X  (how an item name is priced; handy when a drop shows "?")
 *         /blaze refresh  (re-download prices now; they also refresh every 5 minutes)
 * Debug: /blaze debug  (echo slayer/drop chat lines + state changes)
 *        /blaze scan   (list named entities near you + what the mod thinks)
 *        /blaze swap   (toggle hiding of Hypixel's own attunement swap display)
 */
public class BlazeSlayerMod implements ClientModInitializer {

    // Used by HudMixin (the mixin can't reach the mod's instance fields any other way)
    private static BlazeSlayerMod instance;
    private static int mixinCalls = 0;

    // ---- tweak these ------------------------------------------------------
    private static final long RESULT_SHOW_MS = 1000;
    private static final long DROP_AFTER_MS = 15_000;     // drops count this long after a kill
    private static final long DROP_BEFORE_MS = 10_000;    // ...or this long before the kill message
    private static final long POST_KILL_IGNORE_MS = 5_000; // ignore lingering boss stands after a kill/fail
    private static final long POISON_MS = 20_000;          // Twilight Arrow Poison symbol is shown this long
    private static final int  HITS_PER_PHASE = 8;         // every attunement phase starts at this many hits
    private static final int  ATT_CONFIRM_TICKS = 6;       // a different attunement must read the same this long (0.3s) before it is shown
    private static final int  ATT_STALE_TICKS = 40;        // after a phase ends, reads of the old attunement are ignored this long
    // Order the boss cycles through. Used to show the next phase the moment the last hit lands;
    // a real tag read always overrides it.
    private static final String[] ATT_ORDER = {"ASHEN", "SPIRIT", "AURIC", "CRYSTAL"};
    private static final long GUMMY_MS = 3_600_000;       // Smoldering Polarization I: 1:00:00 per Re-heated Gummy Polar Bear
    private static final long GUMMY_WARN_MS = 120_000;     // notify this long before the effect runs out
    private static final long GUMMY_NOTICE_MS = 2_000;     // how long the on-screen notice stays up
    private static final String POISON_SYMBOL = "\u16E4"; // Twilight Arrow Poison symbol
    private static final int  HIT_MIN_TICKS = 5;          // 100% attack speed = 5 invulnerability ticks (0.25s): fastest a hit can register
    private static final int  MAX_DROP_LINES = 12;
    private static final int  DROPS_X = 5, DROPS_Y = 4;
    // -----------------------------------------------------------------------

    private static final Pattern ATTUNEMENT =
            Pattern.compile("\\b(ASHEN|AURIC|SPIRIT|CRYSTAL)\\b", Pattern.CASE_INSENSITIVE);
    // matches "7 Hits" / "7 hit" or "♨ 7"
    private static final Pattern HITS =
            Pattern.compile("(?:(\\d+)\\s*hits?\\b)|(?:\\u2668\\s*(\\d+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP = Pattern.compile(
            "^(PRAY TO RNGESUS|INSANE|CRAZY RARE|VERY RARE|UNCOMMON|RARE|SLAYER RARE)\\s+DROP!?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_PREFIX =
            Pattern.compile("^\\s*\\d+\\s*x\\s+", Pattern.CASE_INSENSITIVE);                // "5x Item"
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile(
            "\\s*(?:\\(\\s*\\d+\\s*x\\s*\\)|\\(\\s*x\\s*\\d+\\s*\\)|\\bx\\s*\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE);                                                      // "Item (5x)" / "Item x5"
    // Same shapes as the two patterns above, but capturing the amount (profit needs the real stack size)
    private static final Pattern QTY_PREFIX = Pattern.compile("^\\s*(\\d+)\\s*x\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern QTY_SUFFIX = Pattern.compile(
            "\\s*(?:\\(\\s*(\\d+)\\s*x\\s*\\)|\\(\\s*x\\s*(\\d+)\\s*\\)|\\bx\\s*(\\d+))\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MF_SUFFIX = Pattern.compile("\\s*\\(\\+.*\\)\\s*$");
    private static final long T4_QUEST_COST = 150_000;    // Inferno Demonlord Tier IV
    private static final String BOSS_KEY = "DEMONLORD";
    private static final String DERELICT_ITEM = "Derelict Ashes";   // guaranteed boss drop, never shown in chat
    // Tier IV drops 110-155 Derelict Ashes per kill (Hypixel wiki); the real average is 132.5, rounded to 135 on purpose
    private static final double DERELICT_AVG = 135;

    private boolean dropsHudEnabled = true;
    private boolean hideSwapDisplay = true;   // hide Hypixel's own on-screen attunement text
    private boolean debug = false;
    private FabricClientCommandSource sink;   // used to print debug lines to chat

    // Quest / fight state
    private long questStart = 0;         // when "Slayer Quest Started" appeared (0 = none)
    private boolean inFight = false;     // true from first boss sighting until kill/fail
    private long fightStart = 0;
    private boolean bossVisible = false; // is a Demonlord nametag visible right now
    private long ignoreBossUntil = 0;
    private long spawnMs = -1;
    private long poisonUntil = 0;        // Twilight Arrow Poison symbol shown until this time
    private boolean poisonSeen = false;  // symbol was on the boss tag in the last scan
    private long lastIgnoreDbg = 0;
    private long lastDbgAt = 0;
    private String attunement = "-";
    private int hits = -1;               // remaining hits, -1 = unknown / not visible / next phase
    private int lastHitTick = 0;
    private int lastRejectedRead = -1;   // debug de-dupe for isDemonlordRead
    private String pendingAtt = null;    // different attunement being confirmed (flicker filter)
    private int pendingSince = 0;
    private String prevAtt = null;       // attunement of the phase that just ended
    private int ignorePrevUntil = 0;         // client tick of the last hit-count decrease
    private long lastKillMs = 0;
    private long dropContextUntil = 0;

    // Result popup
    private long resultUntil = 0;
    private long resultKillMs = 0;
    private long resultSpawnMs = -1;
    private int resultKillColor = 0xFFFF9900;    // popup colours: green = faster than the session average, orange = about average, red = slower
    private int resultSpawnColor = 0xFFFF9900;
    private final List<Long> killTimes = new ArrayList<>();    // every timed kill / spawn this session (for the gradient)
    private final List<Long> spawnTimes = new ArrayList<>();

    // Session stats
    private boolean trackerActive = false;   // the session tracker only runs after /blaze tracker
    private long sessionStart = 0;  // 0 = not started yet (starts when the first boss spawns)
    private int bossesKilled = 0;
    private long totalKillMs = 0;   // sum of fight durations
    private int timedKills = 0;     // kills where the duration was known
    private long totalSpawnMs = 0;  // sum of spawn times
    private int spawnTimed = 0;     // kills where the spawn time was known
    private long totalQuestMs = 0;  // sum of spawn + kill
    private int questTimed = 0;     // kills where both were known
    private final Map<String, Integer> drops = new LinkedHashMap<>();
    private final Map<String, Rarity> dropRarity = new LinkedHashMap<>();
    private final List<Object[]> pendingDrops = new ArrayList<>(); // {Long time, String item, Rarity, Integer qty}

    // Profit tracking
    private final PriceService prices = new PriceService();
    private final Map<String, Double> dropQty = new LinkedHashMap<>();    // item -> total units (drops counts events, this counts items)
    private final Map<String, Double> itemValue = new LinkedHashMap<>(); // item -> net coins for all units (NaN = no price)
    private long questCost = T4_QUEST_COST;
    private int questsCharged = 0;      // bosses killed + quests failed: each one cost questCost
    private int questsFailed = 0;
    private double revenue = 0;         // net value of every priced drop
    private int unpriced = 0;           // distinct dropped items we could not price
    private int gummiesEaten = 0;       // counted from the "You ate" chat line (not /blaze gummy add)
    private double gummyUnit = Double.NaN;   // current Bazaar instant-buy price of one gummy (NaN = unknown)
    /** Every drop the tracker can see, for /blaze ids (checks each name resolves to a live price). */
    private static final String[] KNOWN_DROPS = {
            "High Class Archfiend Dice", "Archfiend Dice", "Subzero Inverter", "Kelvin Inverter",
            "Wilson's Engineering Plans", "Flame Dye", "Scorched Books", "Fiery Burst Rune I", "Lavatears Rune I",
            "Flawed Opal Gemstone", "Scorched Power Crystal", "Gabagool Distillate", "Nether Wart Distillate",
            "Magma Cream Distillate", "Glowstone Distillate", "Blaze Rod Distillate", "Mana Disintegrator",
            "Bundle of Magma Arrows", "Enchanted Blaze Powder", "Wisp's Ice-Flavored Water I Splash Potion",
            "Enchanted Book (Duplex I)", "Enchanted Book (Fire Aspect III)", "Enchanted Book (Smoldering I)",
            "Inferno Demonlord Shard", "Paragon Shard", "Derelict Ashes", "Netherrack-Looking Sunshade",
            "Re-heated Gummy Polar Bear"
    };
    private static final String GUMMY_ITEM = "Re-heated Gummy Polar Bear";

    private int tick = 0;

    // Re-heated Gummy Polar Bear timer (real time, so it keeps running through relogs)
    private long gummyUntil = 0;         // when the stacked effect ends (0 = none active)
    private boolean gummyWarned = false; // 2-minute notice already shown for the current expiry
    private long gummyNoticeUntil = 0;

    // Reflection handles for vanilla on-screen text (title / subtitle / action bar).
    // Fabric has no event for these, so the Gui's Component fields are found by reflection.
    private final List<Object[]> textRefs = new ArrayList<>();   // {owner object, java.lang.reflect.Field}
    private boolean textInit = false;
    private boolean titleFailed = false;
    private String swapStatus = "not set up yet (join a world first)";
    private String lastTitleDbg = "";

    /** Ordered lowest -> highest; only used to sort the drop list. */
    private enum Rarity {
        UNCOMMON, RARE, VERY_RARE, SLAYER_RARE, CRAZY_RARE, INSANE, RNGESUS;

        static Rarity parse(String prefix) {
            String p = prefix.toUpperCase(Locale.ROOT);
            if (p.startsWith("PRAY")) return RNGESUS;
            if (p.startsWith("INSANE")) return INSANE;
            if (p.startsWith("CRAZY")) return CRAZY_RARE;
            if (p.startsWith("VERY")) return VERY_RARE;
            if (p.startsWith("SLAYER")) return SLAYER_RARE;
            if (p.startsWith("UNCOMMON")) return UNCOMMON;
            return RARE;
        }
    }

    private record Stand(Entity entity, String text) {}
    private record Att(String name, int hits) {}

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            if (hideSwapDisplay) hideSwapTitle(client);
            checkGummy();
            tick++;
            prices.refreshIfDue();                        // no-op unless a refresh is due; the download runs off-thread
            if (tick % 20 == 0) recomputeProfit();        // once a second: picks up fresh prices

            if (inFight || questStart > 0 || tick % 4 == 0) scan(client);   // every tick in a fight (hit pacing) and while waiting for the boss (exact spawn time), else every 4th
        });

        // Action-bar (overlay) messages that show an attunement are dropped before they render
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay || !hideSwapDisplay) return true;
            String t = message.getString();
            if (ATTUNEMENT.matcher(t).find()) {
                dbg("hid action bar: " + t);
                return false;
            }
            return true;
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String t = message.getString();
            if (!overlay) onChat(t);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            resetFight();
            questStart = 0;
        });

        // Registered first = runs before the vanilla title is drawn, every frame
        HudElementRegistry.addFirst(
                Identifier.fromNamespaceAndPath("blazeslayerhelper", "hide_swap"),
                (g, delta) -> hideSwapTitle(Minecraft.getInstance()));

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("blazeslayerhelper", "hud"),
                this::drawHud);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("blaze")
                        .executes(ctx -> reply(ctx, "Blaze Slayer Helper loaded. /blaze tracker | drops | reset | ids | profit | cost | tax | price | refresh | debug | scan | swap | gummy"))
                        .then(ClientCommands.literal("drops").executes(ctx -> {
                            dropsHudEnabled = !dropsHudEnabled;
                            return reply(ctx, "Drop list " + (dropsHudEnabled ? "on" : "off"));
                        }))
                        .then(ClientCommands.literal("swap").executes(ctx -> {
                            hideSwapDisplay = !hideSwapDisplay;
                            sink = ctx.getSource();
                            return reply(ctx, "Hiding Hypixel's attunement swap display: " + (hideSwapDisplay ? "on" : "off")
                                    + " | mixin hook calls: " + mixinCalls + " | " + swapStatus);
                        }))
                        .then(ClientCommands.literal("reset").executes(ctx -> {
                            resetSession();
                            return reply(ctx, "Session reset");
                        }))
                        .then(ClientCommands.literal("tracker").executes(ctx -> {
                            if (trackerActive) {
                                trackerActive = false;
                                return reply(ctx, "Tracker stopped. Nothing more is recorded; /blaze profit still shows this session until you start again.");
                            }
                            resetSession();                       // launching = a fresh session
                            trackerActive = true;
                            dropsHudEnabled = true;
                            if (inFight) sessionStart = fightStart;   // launched mid-fight: the clock starts with that boss
                            return reply(ctx, "Tracker started (fresh session). /blaze tracker again to stop.");
                        }))
                        .then(ClientCommands.literal("debug").executes(ctx -> {
                            debug = !debug;
                            sink = ctx.getSource();
                            return reply(ctx, "Debug " + (debug ? "on" : "off"));
                        }))
                        .then(ClientCommands.literal("gummy")
                                .executes(ctx -> {
                                    long rem = gummyUntil - System.currentTimeMillis();
                                    return reply(ctx, rem > 0 ? "Gummy time left: " + fmtTimer(rem)
                                            : "No gummy active");
                                })
                                .then(ClientCommands.literal("add").executes(ctx -> {
                                    addGummy(System.currentTimeMillis());
                                    return reply(ctx, "Added 1 gummy: " + fmtTimer(gummyUntil - System.currentTimeMillis()) + " left");
                                }))
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    gummyUntil = 0;
                                    gummyWarned = false;
                                    gummyNoticeUntil = 0;
                                    return reply(ctx, "Gummy timer cleared");
                                })))
                        .then(ClientCommands.literal("profit").executes(this::profitReport))
                        .then(ClientCommands.literal("refresh").executes(ctx -> {
                            prices.refreshNow();
                            return reply(ctx, "Refreshing prices...");
                        }))
                        .then(ClientCommands.literal("tax").executes(ctx -> {
                            prices.taxes = !prices.taxes;
                            recomputeProfit();
                            return reply(ctx, "Taxes in profit maths: " + (prices.taxes ? "on" : "off (gross prices)"));
                        }))
                        .then(ClientCommands.literal("cost")
                                .executes(ctx -> reply(ctx, "Quest cost: " + fmtCoins(questCost)))
                                .then(ClientCommands.argument("coins", LongArgumentType.longArg(0)).executes(ctx -> {
                                    questCost = LongArgumentType.getLong(ctx, "coins");
                                    recomputeProfit();
                                    return reply(ctx, "Quest cost set to " + fmtCoins(questCost));
                                })))
                        .then(ClientCommands.literal("ids").executes(ctx -> {
                            reply(ctx, "Checking every known drop against live prices (" + prices.status() + ")");
                            int bad = 0;
                            for (String name : KNOWN_DROPS) {
                                PriceService.Price p = prices.lookup(name);
                                if (p == null) {
                                    bad++;
                                    reply(ctx, "NO PRICE: " + name + " (try /blaze price " + name + ")");
                                } else {
                                    reply(ctx, name + " -> " + p.id() + " | " + p.source() + " " + fmtCoins(p.gross()));
                                }
                            }
                            return reply(ctx, bad == 0 ? "All " + KNOWN_DROPS.length + " drops priced" : bad + " of " + KNOWN_DROPS.length + " have no price");
                        }))
                        .then(ClientCommands.literal("price")
                                .executes(ctx -> reply(ctx, "Usage: /blaze price <item name>, e.g. /blaze price Flame Dye"))
                                .then(ClientCommands.argument("item", StringArgumentType.greedyString()).executes(ctx ->
                                        reply(ctx, priceInfo(StringArgumentType.getString(ctx, "item"))))))
                        .then(ClientCommands.literal("scan").executes(ctx -> {
                            sink = ctx.getSource();
                            return scanReport(ctx);
                        }))));
    }

    // ------------------------------------------------------------ helpers

    private void resetSession() {
        drops.clear();
        dropRarity.clear();
        pendingDrops.clear();
        bossesKilled = 0;
        totalKillMs = 0;
        timedKills = 0;
        totalQuestMs = 0;
        totalSpawnMs = 0;
        spawnTimed = 0;
        questTimed = 0;
        dropQty.clear();
        itemValue.clear();
        questsCharged = 0;
        questsFailed = 0;
        revenue = 0;
        unpriced = 0;
        gummiesEaten = 0;
        killTimes.clear();
        spawnTimes.clear();
        sessionStart = 0;   // restarts when the next boss spawns
    }

    private static final int GRAD_BEST = 0xFF00AA00, GRAD_MID = 0xFFFF9900, GRAD_WORST = 0xFFAA0000;   // green -> orange -> red

    /** HUD text (everything except the attunement line) is drawn at this multiple of its original size (1.20 = 20% bigger). */
    private static final float TEXT_SIZE = 1.20f;

    private static int lerpColor(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Within this fraction of the average a time counts as "same-ish" and is plain orange (0.03 = within 3%). */
    private static final double SAME_BAND = 0.03;
    /** How far from the average a time has to be for the full green / full red (0.10 = 10% faster / slower). */
    private static final double GRAD_RANGE = 0.10;

    /**
     * Colours a time by how it compares to the current average of the earlier kills / spawns (the same
     * average the session table shows): orange when it is within SAME_BAND of the average, shading to green as
     * it gets faster and to red as it gets slower, full colour at GRAD_RANGE away from the average.
     * With no earlier data (tracker off, or the first one) there is nothing to compare to, so it is orange.
     */
    private static int rankColor(long v, List<Long> previous) {
        if (previous.isEmpty()) return GRAD_MID;
        double avg = 0;
        for (long x : previous) avg += x;
        avg /= previous.size();
        if (avg <= 0) return GRAD_MID;
        double dev = (v - avg) / avg;                                         // -0.2 = 20% faster than average
        double mag = Math.abs(dev);
        if (mag <= SAME_BAND) return GRAD_MID;                                // same-ish as the average
        double t = Math.min(1, (mag - SAME_BAND) / (GRAD_RANGE - SAME_BAND)); // 0 = just outside the band, 1 = full colour
        return lerpColor(GRAD_MID, dev < 0 ? GRAD_BEST : GRAD_WORST, t);      // faster = green, slower = red
    }

    // Flame-coloured chat title: "Blaze" fades from yellow-orange to red-orange between ember-red brackets
    private static final int TAG_BRACKET = 0xFFCC3300;
    private static final int[] TAG_FLAME = {0xFFFFB000, 0xFFFF9500, 0xFFFF7A00, 0xFFFF5E00, 0xFFFF4500};
    private static final int TAG_TEXT = 0xFFFFFFFF;   // the message after the title

    /** The "[Blaze]" title put in front of every chat line the mod prints. */
    private static MutableComponent blazeTag() {
        String word = "Blaze";
        MutableComponent tag = Component.literal("[").withColor(TAG_BRACKET);
        for (int i = 0; i < word.length(); i++) {
            tag.append(Component.literal(String.valueOf(word.charAt(i))).withColor(TAG_FLAME[i]));
        }
        return tag.append(Component.literal("]").withColor(TAG_BRACKET));
    }

    private static Component tagged(String s) {
        return blazeTag().append(Component.literal(" " + s).withColor(TAG_TEXT));
    }

    private int reply(CommandContext<FabricClientCommandSource> ctx, String s) {
        ctx.getSource().sendFeedback(tagged(s));
        return 1;
    }

    private void dbg(String s) {
        if (debug && sink != null) {
            long t = System.currentTimeMillis();
            String delta = lastDbgAt == 0 ? "" : "+" + fmt(t - lastDbgAt) + " ";
            lastDbgAt = t;
            sink.sendFeedback(tagged(delta + s));
        }
    }

    private static boolean near(Entity a, Entity b, double h, double v) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz < h * h && Math.abs(a.getY() - b.getY()) < v;
    }

    private static String fmt(long ms) { return String.format("%.1fs", ms / 1000.0); }

    private static int attColor(String a) {
        return switch (a) {
            case "ASHEN" -> 0xFF8A8A8A;
            case "AURIC" -> 0xFFFFFF55;
            case "SPIRIT" -> 0xFFFFFFFF;
            case "CRYSTAL" -> 0xFF55FFFF;
            default -> 0xFFAAAAAA;
        };
    }

    private static String norm(String item) {
        return item.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");   // "High Class" / "Highclass" / "sub-zero" all compare the same
    }

    /**
     * Drops that can happen at any time rather than only at the boss's death: the Smoldering I book
     * (regular tomb blazes) and the Nether Star (inside and outside the boss fight).
     */
    private static boolean isNonBossDrop(String item) {
        String n = norm(item);
        return n.contains("smoldering") || n.contains("netherstar");
    }

    private static boolean isFieryRune(String n) { return n.contains("fiery") && n.contains("rune"); }

    /** Everything is white except a handful of chase drops. */
    private static int dropColor(String item) {
        String n = norm(item);
        if (n.contains("highclassarchfiend")) return 0xFFFF5555;     // red
        if (n.contains("archfiend")) return 0xFFAA00AA;              // dark purple
        if (n.contains("paragonshard")) return 0xFFFFAA00;           // gold
        if (n.contains("wilson")) return 0xFFFF55FF;                 // pink
        if (n.contains("subzero")) return 0xFFFF55FF;                // pink
        if (isFieryRune(n)) return 0xFFFF55FF;                       // pink
        if (n.contains("flamedye")) return 0xFFAA0000;               // dark red
        return 0xFFFFFFFF;                                           // white
    }

    /** What an item has earned this session, for ordering the drop list. No price = lowest. */
    private double sortValue(String item) {
        Double v = itemValue.get(item);
        return v == null || Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v;
    }

    /** 1:23:45 for an hour or more, 23:45 below that. */
    private static String fmtTimer(long ms) {
        long t = Math.max(0, (ms + 999) / 1000);   // round up so it shows 0:01 until it is really over
        return t >= 3600 ? String.format("%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
                : String.format("%d:%02d", t / 60, t % 60);
    }

    /** Eating another gummy adds its hour on top of whatever is left. */
    private void addGummy(long now) {
        gummyUntil = Math.max(gummyUntil, now) + GUMMY_MS;
        if (gummyUntil - now > GUMMY_WARN_MS) gummyWarned = false;   // re-arm the notice
        dbg("gummy eaten, " + fmtTimer(gummyUntil - now) + " left");
    }

    private void checkGummy() {
        if (gummyUntil <= 0) return;
        long now = System.currentTimeMillis();
        long rem = gummyUntil - now;
        if (rem <= 0) {
            gummyUntil = 0;
            gummyWarned = false;
        } else if (rem <= GUMMY_WARN_MS && !gummyWarned) {
            gummyWarned = true;
            gummyNoticeUntil = now + GUMMY_NOTICE_MS;
            dbg("gummy warning");
        }
    }

    private static String fmtClock(long ms) {
        long t = Math.max(0, ms / 1000);
        return String.format("%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60);
    }

    /** Short loot-table names, matched on the normalised item name (first match wins). */
    // Display names: one or two whole words, nothing abbreviated mid-word.
    private static final String[][] SHORT_NAMES = {
            {"highclassarchfiend", "High Class Dice"},
            {"archfienddice", "Archfiend Dice"},
            {"subzeroinverter", "Subzero Inverter"},
            {"kelvininverter", "Kelvin Inverter"},
            {"wilson", "Wilson's Plans"},
            {"fieryburstrune", "Burst Rune"},
            {"lavatearsrune", "Lavatears Rune"},
            {"flamedye", "Flame Dye"},
            {"flawedopal", "Flawed Opal"},
            {"scorchedpowercrystal", "Power Crystal"},
            {"scorchedbooks", "Scorched Books"},
            {"gabagooldistillate", "Gabagool"},
            {"netherwartdistillate", "Wart Distillate"},
            {"magmacreamdistillate", "Magma Distillate"},
            {"glowstonedistillate", "Glow Distillate"},
            {"blazeroddistillate", "Rod Distillate"},
            {"manadisintegrator", "Disintegrator"},
            {"derelictashe", "Derelict Ashe"},
            {"smoldering", "Smoldering Book"},
            {"paragonshard", "Paragon Shard"},
            {"infernodemonlordshard", "Demonlord Shard"},
            {"duplex", "Duplex Book"},
            {"fireaspect", "Fire Aspect Book"},
            {"bundleofmagmaarrows", "Magma Arrows"},
            {"enchantedblazepowder", "Blaze Powder"},
            {"sunshade", "Sunshade"},
    };

    private static final java.util.Set<String> FILLER_WORDS =
            java.util.Set.of("enchanted", "gemstone", "of", "the", "a", "an", "bundle");

    /** Known alias if there is one, else at most the last two meaningful whole words. Never cuts a word. */
    private static String shortName(String item) {
        String n = norm(item);
        for (String[] e : SHORT_NAMES) if (n.contains(e[0])) return e[1];
        List<String> words = new ArrayList<>();
        for (String w : item.replaceAll("[()\\[\\]]", " ").trim().split("\\s+")) {
            if (w.isEmpty() || FILLER_WORDS.contains(w.toLowerCase(Locale.ROOT)) || w.matches("[IVX]+")) continue;
            words.add(w);
        }
        if (words.isEmpty()) return item.trim();
        return String.join(" ", words.subList(Math.max(0, words.size() - 2), words.size()));
    }

    /**
     * Counts one drop event for the list (a "30x Item" message is still 1 event there),
     * and adds the real number of units for the profit maths.
     */
    private void addDrop(String item, Rarity r, double qty) {
        if (!trackerActive) return;   // tracker not launched: nothing is recorded
        drops.merge(item, 1, Integer::sum);
        dropRarity.merge(item, r, (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
        dropQty.merge(item, Math.max(1.0, qty), Double::sum);
        recomputeProfit();
    }

    /** Stack size from "5x Item", "Item (5x)", "Item (x5)" or "Item x5"; 1 if there is none. */
    private static int parseQty(String item) {
        try {
            Matcher p = QTY_PREFIX.matcher(item);
            if (p.find()) return Math.max(1, Integer.parseInt(p.group(1)));
            Matcher s = QTY_SUFFIX.matcher(item);
            if (s.find()) {
                for (int i = 1; i <= 3; i++) if (s.group(i) != null) return Math.max(1, Integer.parseInt(s.group(i)));
            }
        } catch (NumberFormatException ignored) {
            // absurdly large number: treat as a single unit
        }
        return 1;
    }

    // ------------------------------------------------------------ profit

    /** Re-values every drop at the current prices. Cheap (a handful of items); called about once a second. */
    private void recomputeProfit() {
        double rev = 0;
        int missing = 0;
        itemValue.clear();
        for (Map.Entry<String, Double> e : dropQty.entrySet()) {
            PriceService.Price p = prices.lookup(e.getKey());
            if (p == null) {
                itemValue.put(e.getKey(), Double.NaN);
                missing++;
            } else {
                double v = prices.net(p) * e.getValue();
                itemValue.put(e.getKey(), v);
                rev += v;
            }
        }
        revenue = rev;
        unpriced = missing;
        Double g = prices.instaBuy(GUMMY_ITEM);
        gummyUnit = g == null ? Double.NaN : g;
    }

    private double totalCost() { return (double) questsCharged * questCost; }

    /** Gummies are bought at Bazaar instant-buy (no tax on buying). Unknown price counts as 0 until prices load. */
    private double gummyCost() { return Double.isNaN(gummyUnit) ? 0 : gummiesEaten * gummyUnit; }

    private double profit() { return revenue - totalCost() - gummyCost(); }

    /** 1.25b / 3.40m / 250.0k / 900, with a leading minus for negatives. */
    private static String fmtCoins(double c) {
        double a = Math.abs(c);
        String sign = c < 0 ? "-" : "";
        if (a >= 1_000_000_000) return sign + String.format(Locale.ROOT, "%.2fb", a / 1_000_000_000);
        if (a >= 1_000_000) return sign + String.format(Locale.ROOT, "%.2fm", a / 1_000_000);
        if (a >= 1_000) return sign + String.format(Locale.ROOT, "%.1fk", a / 1_000);
        return sign + String.format(Locale.ROOT, "%.0f", a);
    }

    /** 132.5 / 1,325 / 3: whole numbers without a decimal, fractional averages with one. */
    private static String fmtQty(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%,.0f", v) : String.format(Locale.ROOT, "%,.1f", v);
    }

    /** Count shown in the drop list: units for the averaged Derelict Ashes, drop events for everything else. */
    private String countLabel(String item) {
        if (norm(item).contains("derelictashe")) return "x" + fmtQty(dropQty.getOrDefault(item, 0.0));
        return "x" + drops.getOrDefault(item, 0);
    }

    private static String fmtExact(double c) { return String.format(Locale.ROOT, "%,.0f", c); }

    private int profitReport(CommandContext<FabricClientCommandSource> ctx) {
        recomputeProfit();
        long now = System.currentTimeMillis();
        reply(ctx, "Profit " + fmtCoins(profit()) + "  =  drops " + fmtCoins(revenue)
                + " - quests " + fmtCoins(totalCost()) + " (" + questsCharged + " x " + fmtCoins(questCost)
                + (questsFailed > 0 ? ", " + questsFailed + " failed" : "") + ")"
                + " - gummies " + fmtCoins(gummyCost()) + " (" + gummiesEaten
                + (Double.isNaN(gummyUnit) ? ", price unknown" : " x " + fmtCoins(gummyUnit)) + ")");
        if (sessionStart > 0 && now - sessionStart >= 60_000) {
            reply(ctx, "Per hour: " + fmtCoins(profit() / ((now - sessionStart) / 3_600_000.0)));
        }
        reply(ctx, "Taxes " + (prices.taxes ? "on (Bazaar 1.25%, AH listing + claim fee)" : "off")
                + " | prices " + prices.status());

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(itemValue.entrySet());
        sorted.sort((a, b) -> Double.compare(Double.isNaN(b.getValue()) ? -1 : b.getValue(),
                Double.isNaN(a.getValue()) ? -1 : a.getValue()));
        for (Map.Entry<String, Double> e : sorted) {
            double qty = dropQty.getOrDefault(e.getKey(), 0.0);
            PriceService.Price p = prices.lookup(e.getKey());
            if (p == null) {
                reply(ctx, shortName(e.getKey()) + " x" + fmtQty(qty) + ": no price (try /blaze price " + e.getKey() + ")");
            } else {
                reply(ctx, shortName(e.getKey()) + " x" + fmtQty(qty) + " @ " + fmtCoins(prices.net(p)) + " " + p.source()
                        + " = " + fmtCoins(e.getValue()));
            }
        }
        return 1;
    }

    private String priceInfo(String item) {
        PriceService.Price p = prices.lookup(item);
        if (p == null && !prices.ready()) return "Prices not loaded yet: " + prices.status();
        if (p == null) return "No price found for \"" + item + "\" (id guesses: "
                + String.join(", ", PriceService.candidates(item, java.util.Map.of())) + ")";
        if (p.fixed()) return item + " -> fixed value " + fmtExact(p.gross()) + " each (set in PriceService.FIXED_PRICES)";
        return item + " -> " + p.id() + " | " + (p.bazaar() ? "Bazaar insta-sell " : "lowest BIN ") + fmtExact(p.gross())
                + " | after tax " + fmtExact(prices.net(p));
    }

    private static int parseHits(String text) {
        Matcher m = HITS.matcher(text);
        if (!m.find()) return -1;
        String g = m.group(1) != null ? m.group(1) : m.group(2);
        try { return Integer.parseInt(g); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Is this hit count plausibly the Demonlord's, given what we were already showing?
     * When Quazii dies while both he and the Demonlord are SPIRIT, his tag has the same attunement
     * name, so the name alone can't separate them. The Demonlord's count can only be 1 hit away
     * from the last one shown (the hit that was in flight may or may not be on the tag yet), and
     * drops at most one more per HIT_MIN_TICKS of real time. Anything outside that window is
     * another entity's count and is ignored. Unknown counts are always accepted.
     */
    private boolean isDemonlordRead(int read) {
        if (read < 0 || hits < 0) return true;
        int slack = 1 + (tick - lastHitTick) / HIT_MIN_TICKS;   // lower bound widens with time so we can't get stuck
        boolean ok = read <= hits + 1 && read >= hits - slack;
        if (!ok && read != lastRejectedRead) {
            dbg("ignored " + attunement + " " + read + " hits: not 1 hit from tracked " + hits + " (not the Demonlord)");
        }
        lastRejectedRead = ok ? -1 : read;
        return ok;
    }

    /**
     * Applies a hit count read from the boss tag. The count can only go down by one hit per
     * HIT_MIN_TICKS (100% attack speed), so a double-read or a late tag can't tick it twice.
     * 0 (or an unreadable count) means the next phase, so no "0 hits" is ever shown.
     */
    private void applyHits(int read) {
        if (read < 0) return;                       // count not readable this scan: keep what we have
        if (read == 0) {                            // last hit landed: next attunement, fresh count
            if (hits > 0) advancePhase();
            return;
        }
        if (hits < 0 || read > hits) { hits = read; lastHitTick = tick; return; }   // new phase / new count
        if (read < hits && tick - lastHitTick >= HIT_MIN_TICKS) {
            int allowed = (tick - lastHitTick) / HIT_MIN_TICKS;
            hits = Math.max(read, hits - allowed);
            lastHitTick = tick;
        }
    }

    /** 0 hits reached: show the next attunement straight away, starting at HITS_PER_PHASE. */
    private void advancePhase() {
        String old = attunement;
        String next = old;
        for (int i = 0; i < ATT_ORDER.length; i++) {
            if (ATT_ORDER[i].equals(old)) { next = ATT_ORDER[(i + 1) % ATT_ORDER.length]; break; }
        }
        prevAtt = old;
        ignorePrevUntil = tick + ATT_STALE_TICKS;
        pendingAtt = null;
        attunement = next;
        hits = HITS_PER_PHASE;
        lastHitTick = tick;
        dbg("0 hits -> next phase: " + next + " at " + HITS_PER_PHASE);
    }

    private void resetFight() {
        inFight = false;
        pendingAtt = null;
        prevAtt = null;
        poisonUntil = 0;
        poisonSeen = false;
        bossVisible = false;
        attunement = "-";
        hits = -1;
    }

    private List<Stand> namedEntities(Minecraft mc) {
        List<Stand> out = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.hasCustomName()) out.add(new Stand(e, e.getCustomName().getString()));
        }
        return out;
    }

    // ------------------------------------------------------------ hide Hypixel's swap display

    /**
     * Clears vanilla on-screen text (title, subtitle, action bar) that shows an attunement:
     * the text Hypixel flashes in the middle of the screen after you swap daggers.
     * It runs every frame before the vanilla HUD is drawn, and it looks at every Component
     * field of the Gui (and of the Gui's sub-objects), so it doesn't depend on field names.
     * If nothing can be found it turns itself off; /blaze swap reports what happened.
     */
    private void hideSwapTitle(Minecraft mc) {
        if (!hideSwapDisplay || titleFailed || mc.level == null) return;
        try {
            if (!textInit) {
                textInit = true;
                Object gui = findGui(mc);
                collectText(gui, 2, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                StringBuilder names = new StringBuilder();
                for (Object[] r : textRefs) names.append(((java.lang.reflect.Field) r[1]).getName()).append(' ');
                swapStatus = textRefs.isEmpty()
                        ? "no on-screen text fields found, hiding is unavailable"
                        : textRefs.size() + " text field(s) watched: " + names.toString().trim();
                dbg(swapStatus);
                if (textRefs.isEmpty()) { titleFailed = true; return; }
            }

            for (Object[] r : textRefs) {
                java.lang.reflect.Field f = (java.lang.reflect.Field) r[1];
                Object v = f.get(r[0]);
                if (!(v instanceof Component c)) continue;
                String text = c.getString();
                if (text.isEmpty()) continue;
                if (ATTUNEMENT.matcher(text).find() || text.toLowerCase(Locale.ROOT).contains("attun")) {
                    f.set(r[0], null);
                    dbg("hid on-screen text [" + f.getName() + "]: " + text);
                } else if (debug && !text.equals(lastTitleDbg)) {
                    lastTitleDbg = text;
                    dbg("on-screen text [" + f.getName() + "]: " + text);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            titleFailed = true;
            swapStatus = "failed: " + e;
            dbg("swap display hiding unavailable: " + e);
        }
    }

    /**
     * Called by HudMixin before the game sets a title or subtitle. Returns true when the text
     * is Hypixel's attunement swap display, and the mixin then cancels the call so the text
     * is never stored or drawn.
     */
    public static boolean shouldHideSwapText(Component text, String where) {
        BlazeSlayerMod m = instance;
        if (m == null) return false;
        mixinCalls++;
        if (!m.hideSwapDisplay || text == null) return false;
        String t = text.getString();
        if (t.isEmpty()) return false;
        boolean hide = ATTUNEMENT.matcher(t).find() || t.toLowerCase(Locale.ROOT).contains("attun");
        m.dbg((hide ? "hid " : "saw ") + where + " (mixin): " + t);
        return hide;
    }

    private static Object findGui(Minecraft mc) throws ReflectiveOperationException {
        try {
            java.lang.reflect.Field f = Minecraft.class.getDeclaredField("gui");
            f.setAccessible(true);
            return f.get(mc);
        } catch (NoSuchFieldException e) {
            for (java.lang.reflect.Field f : Minecraft.class.getDeclaredFields()) {
                if (f.getType().getName().equals("net.minecraft.client.gui.Gui")) {
                    f.setAccessible(true);
                    return f.get(mc);
                }
            }
            throw e;
        }
    }

    /** Collects every non-static Component field of owner and of its net.minecraft.client.gui sub-objects. */
    private void collectText(Object owner, int depth, java.util.Set<Object> seen) {
        if (owner == null || !seen.add(owner)) return;
        for (Class<?> c = owner.getClass(); c != null && c.getName().startsWith("net.minecraft"); c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    if (Component.class.isAssignableFrom(f.getType())) {
                        textRefs.add(new Object[]{owner, f});
                    } else if (depth > 0 && !f.getType().isPrimitive()) {
                        Object v = f.get(owner);
                        if (v != null && v.getClass().getName().startsWith("net.minecraft.client.gui")) {
                            collectText(v, depth - 1, seen);
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // skip fields we can't open
                }
            }
        }
    }

    // ------------------------------------------------------------ boss scanning

    /**
     * Finds YOUR boss's name entity: the Demonlord stacked on your ownership tag ("<your name>'s SLAYER BOSS").
     * The tag alone is not enough: Quazii's phase carries the very same tag, so a Demonlord-named stand
     * has to sit on it. Other players' Demonlords (and any Demonlord before yours has spawned) are ignored
     * on purpose, so nothing is shown and no timer starts until your own boss is out.
     */
    private Stand findBoss(Minecraft mc, List<Stand> all) {
        String me = mc.player.getName().getString().toLowerCase(Locale.ROOT);
        Pattern ownTag = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(me) + "['\u2019]s?\\s+slayer\\s+boss");
        Stand best = null;
        float bestDist = Float.MAX_VALUE;
        for (Stand anchor : all) {
            if (!isMyBossTag(anchor.text(), me, ownTag)) continue;
            for (Stand s : all) {
                if (!s.text().toUpperCase(Locale.ROOT).contains(BOSS_KEY)) continue;
                if (!near(anchor.entity(), s.entity(), 2.0, 4.0)) continue;
                float d = s.entity().distanceTo(anchor.entity());
                if (d < bestDist) { bestDist = d; best = s; }
            }
        }
        return best;
    }

    /**
     * "Catxna's SLAYER BOSS" (what Hypixel shows) where the name is exactly you, not a name that merely contains yours.
     * The older "Spawned by: Name" tag is still accepted too.
     */
    private static boolean isMyBossTag(String text, String meLower, Pattern ownTag) {
        String t = text.toLowerCase(Locale.ROOT);
        if (ownTag.matcher(t).find()) return true;
        int i = t.indexOf("spawned by");
        if (i < 0) return false;
        for (String tok : t.substring(i + "spawned by".length()).split("[^a-z0-9_]+")) {
            if (tok.equals(meLower)) return true;
        }
        return false;
    }

    /** Attunement name + remaining hits from the stands stacked on the Demonlord's nametag. */
    private Att findAttunement(Stand boss, List<Stand> all) {
        String att = null;
        int h = -1;
        float bestDist = Float.MAX_VALUE;
        for (Stand s : all) {
            if (!near(boss.entity(), s.entity(), 1.0, 2.5)) continue;
            if (s.text().toLowerCase(Locale.ROOT).contains("spawned by")) continue;
            Matcher m = ATTUNEMENT.matcher(s.text());
            if (!m.find()) continue;
            float d = s.entity().distanceTo(boss.entity());
            if (d < bestDist) {
                bestDist = d;
                att = m.group(1).toUpperCase(Locale.ROOT);
                h = parseHits(s.text());
            }
        }
        if (att == null) return null;

        // Hits may live on a neighbouring stand instead of the attunement one
        if (h < 0) {
            float bd = Float.MAX_VALUE;
            for (Stand s : all) {
                if (!near(boss.entity(), s.entity(), 1.0, 2.5)) continue;
                int v = parseHits(s.text());
                if (v < 0) continue;
                float d = s.entity().distanceTo(boss.entity());
                if (d < bd) { bd = d; h = v; }
            }
        }
        return new Att(att, h);
    }

    /** Twilight Arrow Poison symbol anywhere on the boss's tag stack. */
    private boolean findPoison(Stand boss, List<Stand> all) {
        for (Stand s : all) {
            if (!near(boss.entity(), s.entity(), 1.5, 3.5)) continue;
            if (s.text().contains(POISON_SYMBOL)) return true;
        }
        return false;
    }

    private void scan(Minecraft mc) {
        long now = System.currentTimeMillis();
        List<Stand> all = namedEntities(mc);
        Stand boss = findBoss(mc, all);

        if (boss != null && now < ignoreBossUntil && now - lastIgnoreDbg > 2000) {
            lastIgnoreDbg = now;
            dbg("boss tag seen but ignored for another " + fmt(ignoreBossUntil - now));
        }

        if (boss != null && now >= ignoreBossUntil) {
            bossVisible = true;
            if (!inFight) {
                // First sighting for this quest: this is the real start of the fight.
                inFight = true;
                fightStart = now;
                spawnMs = questStart > 0 ? now - questStart : -1;
                questStart = 0;
                if (trackerActive && sessionStart == 0) sessionStart = now;
                attunement = "-";
                hits = -1;
                dbg("boss spawned: \"" + boss.text() + "\" spawnTime=" + (spawnMs >= 0 ? fmt(spawnMs) : "?"));
            }
            Att a = findAttunement(boss, all);
            if (a != null) {
                if (attunement.equals("-")) {
                    attunement = a.name();                       // first read of the fight: nothing to flicker from
                    dbg("attunement -> " + a.name());
                    applyHits(a.hits());
                } else if (a.name().equals(attunement)) {
                    pendingAtt = null;
                    // Same attunement name can't tell the Demonlord from Quazii, so the hit count has to:
                    // only a read that is one hit away from what we were showing is the Demonlord.
                    if (isDemonlordRead(a.hits())) applyHits(a.hits());
                } else if (a.name().equals(prevAtt) && tick < ignorePrevUntil) {
                    // stale tag from the phase that just ended
                } else if (!a.name().equals(pendingAtt)) {
                    pendingAtt = a.name();                       // start confirming; keep showing the current one
                    pendingSince = tick;
                } else if (tick - pendingSince >= ATT_CONFIRM_TICKS) {
                    dbg("attunement -> " + a.name());
                    attunement = a.name();
                    pendingAtt = null;
                    hits = -1;                                   // fresh count, not paced against the old one
                    applyHits(a.hits());
                }
            } else {
                pendingAtt = null;                               // a flicker has to be unbroken to count
            }
            // else: attunement tag not readable this scan -> keep the last known attunement + hits
            // The symbol stays on the tag for the whole boss, so the 20s starts when it first appears and is
            // NOT restarted while it stays; it only re-arms after the symbol has left a visible tag.
            boolean seen = findPoison(boss, all);
            if (seen && !poisonSeen) {
                poisonUntil = now + POISON_MS;
                dbg("poison applied");
            }
            poisonSeen = seen;
        } else {
            // boss tag hidden (demon phase): keep poisonSeen as it was, so the returning tag isn't a new application
            // Boss stand gone (other phase / minibosses): the fight is NOT over,
            // only a kill message or a failed quest ends it.
            // Boss tag just vanished (demon phase): the last tag we read was one hit behind,
            // so take 1 off and keep showing that count until the boss is back.
            // That hit still has to respect the attack-speed cap, and the last hit means next phase (no 0).
            if (bossVisible && inFight && hits > 0) {
                if (hits == 1) {
                    advancePhase();                      // last hit landed while the tag was hidden
                } else if (tick - lastHitTick >= HIT_MIN_TICKS) {
                    hits--;
                    lastHitTick = tick;
                    dbg("demon phase, hits held at " + hits);
                } else {
                    dbg("demon phase, hit too soon after the last one, held at " + hits);
                }
            }
            bossVisible = false;
        }
    }

    private int scanReport(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;
        reply(ctx, "state: inFight=" + inFight + " bossVisible=" + bossVisible
                + " attunement=" + attunement + " hits=" + hits + " poisoned=" + (System.currentTimeMillis() < poisonUntil)
                + " questStart=" + (questStart > 0) + " kills=" + bossesKilled);
        int n = 0;
        for (Stand s : namedEntities(mc)) {
            float d = s.entity().distanceTo(mc.player);
            if (d > 15) continue;
            if (n++ >= 14) { reply(ctx, "..."); break; }
            reply(ctx, String.format("%.1fm | %s | %s", d, s.entity().getType().toShortString(), s.text()));
        }
        if (n == 0) reply(ctx, "no named entities within 15 blocks");
        return 1;
    }

    // ------------------------------------------------------------ chat

    private void onChat(String raw) {
        String text = raw.trim();
        if (text.startsWith("[Blaze]")) return;   // our own chat lines
        String up = text.toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (debug && (up.contains("SLAYER") || up.contains("DROP") || up.contains("BOSS")
                || up.contains("GUMMY") || up.contains("POLARIZATION"))) {
            dbg("chat: " + text);
        }

        // Eating one: "You ate a Re-heated Gummy Polar Bear" (must start with "You ate", so bazaar/auction lines don't count)
        if (up.contains("GUMMY POLAR BEAR") && (up.startsWith("YOU ATE") || up.startsWith("YOU CONSUMED"))) {
            addGummy(now);
            if (trackerActive) gummiesEaten++;
            return;
        }

        if (up.contains("SLAYER QUEST STARTED")) {
            resetFight();
            spawnMs = -1;
            ignoreBossUntil = 0;
            questStart = now;
            dbg("quest started");
            return;
        }

        if (up.contains("SLAYER QUEST FAILED")) {
            if (trackerActive) {
                questsCharged++;      // the fee is gone whether or not the boss died
                questsFailed++;
            }
            resetFight();
            questStart = 0;
            ignoreBossUntil = now + POST_KILL_IGNORE_MS;
            return;
        }

        if (up.contains("SLAYER BOSS SLAIN") || up.contains("SLAYER QUEST COMPLETE")) {
            if (now - lastKillMs > 3000) handleKill(now);   // both lines can appear; count once
            lastKillMs = now;
            return;
        }

        Matcher m = DROP.matcher(text);
        if (m.matches()) {
            Rarity rarity = Rarity.parse(m.group(1));
            String item = MF_SUFFIX.matcher(m.group(2).trim()).replaceFirst("").trim();
            if (item.startsWith("(") && item.endsWith(")")) {
                item = item.substring(1, item.length() - 1).trim();
            }
            int qty = parseQty(item);   // read the stack size before the amount is stripped off
            item = AMOUNT_SUFFIX.matcher(AMOUNT_PREFIX.matcher(item).replaceFirst("")).replaceFirst("").trim();
            if (item.isEmpty()) return;
            if (item.toLowerCase(Locale.ROOT).contains("flawed opal")) rarity = Rarity.UNCOMMON;
            if (isNonBossDrop(item)) {
                // Dropped by Smoldering / Millennia-Aged Blazes, not the boss, so it never lines up with a kill message
                addDrop(item, rarity, qty);
                dbg("non-boss drop counted: " + item + " x" + qty + " (" + rarity + ")");
            } else if (now <= dropContextUntil) {
                addDrop(item, rarity, qty);
                dbg("drop counted: " + item + " x" + qty + " (" + rarity + ")");
            } else {
                pendingDrops.add(new Object[]{now, item, rarity, qty});   // might be claimed by an upcoming kill message
                pendingDrops.removeIf(o -> now - (Long) o[0] > DROP_BEFORE_MS);
            }
        }
    }

    private void handleKill(long now) {
        // Whole fight: from the first boss sighting (phase 1) to the kill message.
        long dur = inFight ? now - fightStart : -1;
        if (trackerActive) {
            bossesKilled++;
            questsCharged++;
        }
        resultKillMs = dur > 0 ? dur : -1;
        // Colours compare this kill against the average of the kills before it (so it isn't measured against itself)
        resultKillColor = dur > 0 ? rankColor(dur, killTimes) : GRAD_MID;
        resultSpawnColor = spawnMs >= 0 ? rankColor(spawnMs, spawnTimes) : GRAD_MID;
        if (trackerActive && dur > 0) {
            totalKillMs += dur;
            timedKills++;
            killTimes.add(dur);
            if (spawnMs >= 0) { totalQuestMs += spawnMs + dur; questTimed++; }   // quest = spawn + kill
        }
        if (trackerActive && spawnMs >= 0) { totalSpawnMs += spawnMs; spawnTimed++; spawnTimes.add(spawnMs); }
        resultSpawnMs = spawnMs;
        resultUntil = now + RESULT_SHOW_MS;
        dbg("kill detected, fight=" + (dur > 0 ? fmt(dur) : "unknown")
                + " spawn=" + (spawnMs >= 0 ? fmt(spawnMs) : "?"));

        dropContextUntil = now + DROP_AFTER_MS;
        for (Object[] o : pendingDrops) {
            if (now - (Long) o[0] <= DROP_BEFORE_MS) {
                addDrop((String) o[1], (Rarity) o[2], (Integer) o[3]);
                dbg("drop counted (pre-kill): " + o[1]);
            }
        }
        pendingDrops.clear();

        // Derelict Ashes always drop from the boss but Hypixel never prints them in chat, and the amount is a
        // range, so every confirmed kill (not a failed quest) credits the average of that range, priced from the Bazaar.
        addDrop(DERELICT_ITEM, Rarity.UNCOMMON, DERELICT_AVG);
        dbg("derelict ashes credited x" + DERELICT_AVG + " (average of the 110-155 range, rounded)");

        resetFight();
        questStart = 0;
        ignoreBossUntil = Math.max(ignoreBossUntil, now + POST_KILL_IGNORE_MS);
    }

    // ------------------------------------------------------------ HUD

    private static final int POISON_COLOR = 0xFFAA00AA;   // dark purple
    private static final int AVG_COLOR = 0xFFE6E6E6;       // Avg kill / spawn / quest values all share this colour
    private static final float HUD_SCALE = 0.75f * TEXT_SIZE;   // top-left text (gummy timer + session table)

    /**
     * Attunement line split at a fixed seam on the crosshair's vertical centre line: the name is right-aligned
     * against the seam and the hit count is left-aligned from it, so the two always sit the same small distance
     * apart whatever the attunement (ASHEN or CRYSTAL) or the count (1 hit or 8 hits). The poison symbol has its
     * own reserved slot after the hit count, so it appearing never moves the name or the hits.
     */
    private void drawAttunement(GuiGraphicsExtractor g, Minecraft mc, String att, String hitsText, int color,
                                boolean poison, int cx, int y, float scale) {
        int gap = mc.font.width("  ");                    // total space between the name and the hit count
        int hitsW = mc.font.width("8 hits");               // widest count: up to 8, plural

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, y);
        pose.scale(scale, scale);
        int seamL = -gap / 2;                              // name ends here
        int seamR = gap - gap / 2;                         // hits start here
        g.text(mc.font, att, seamL - mc.font.width(att), 0, color, true);
        if (hitsText != null) g.text(mc.font, hitsText, seamR, 0, color, true);
        if (poison) g.text(mc.font, POISON_SYMBOL, seamR + hitsW + gap, 0, POISON_COLOR, true);
        pose.popMatrix();
    }

    private void drawCentered(GuiGraphicsExtractor g, Minecraft mc, String s, int cx, int y, int color, float scale) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, y);
        pose.scale(scale, scale);
        g.text(mc.font, s, -mc.font.width(s) / 2, 0, color, true);
        pose.popMatrix();
    }

    private void drawHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        long now = System.currentTimeMillis();

        // Demonlord only; shown for the entire fight, including phases where its tag is not visible
        if (inFight && !attunement.equals("-")) {
            String hitsText = hits >= 0 ? hits + (hits == 1 ? " hit" : " hits") : null;
            // Twilight Arrow Poison: flat 20s, always dark purple (the rest of the line keeps the attunement colour)
            drawAttunement(g, mc, attunement, hitsText, attColor(attunement), now < poisonUntil, cx, cy + 36, 1.5f);
        }

        // The gummy timer is a row inside the session table. Only when the table isn't showing (tracker off or
        // drop list hidden) does it get drawn on its own at the top left, so the timer is never lost.
        boolean tableShown = dropsHudEnabled && trackerActive;
        long gummyRem = gummyUntil > 0 ? gummyUntil - now : 0;
        int gummyColor = gummyRem <= GUMMY_WARN_MS ? 0xFFFF5555 : gummyRem <= 600_000 ? 0xFFFFFF55 : 0xFF55FF55;
        int topY = DROPS_Y;
        if (!tableShown && gummyRem > 0) {
            var hudPose = g.pose();
            hudPose.pushMatrix();
            hudPose.scale(HUD_SCALE, HUD_SCALE);
            String label = "Gummy ";
            g.text(mc.font, label, DROPS_X, topY, 0xFFAAAAAA, true);
            g.text(mc.font, fmtTimer(gummyRem), DROPS_X + mc.font.width(label), topY, gummyColor, true);
            hudPose.popMatrix();
        }
        if (now < gummyNoticeUntil && gummyUntil > now) {
            drawCentered(g, mc, "Gummy expires in " + fmtTimer(gummyUntil - now), cx, cy - 80, 0xFFFF5555, 2.0f * TEXT_SIZE);
        }

        if (now < resultUntil) {
            String kill = resultKillMs >= 0 ? fmt(resultKillMs) : "?";
            String spawn = resultSpawnMs >= 0 ? fmt(resultSpawnMs) : "?";
            drawCentered(g, mc, "Kill: " + kill, cx, cy - 47, resultKillColor, 2.0f * TEXT_SIZE);
            drawCentered(g, mc, "Spawn: " + spawn, cx, cy - 25, resultSpawnColor, 1.5f * TEXT_SIZE);
        }

        if (tableShown) {
            var tablePose = g.pose();
            tablePose.pushMatrix();
            tablePose.scale(HUD_SCALE, HUD_SCALE);
            int x = DROPS_X, y = topY;
            final int line = 10;
            g.text(mc.font, "Slayer Session", x, y, 0xFFFF5555, true); y += line + 3;
            String clock = sessionStart > 0 ? fmtClock(now - sessionStart) : "0:00:00";
            String avgKill = timedKills > 0 ? fmt(totalKillMs / timedKills) : "-";
            String avgSpawn = spawnTimed > 0 ? fmt(totalSpawnMs / spawnTimed) : "-";
            String avgQuest = questTimed > 0 ? fmt(totalQuestMs / questTimed) : "-";

            // Profit = drops (net of tax) - quest cost per boss finished/failed - gummies eaten
            boolean priced = prices.ready();
            double profit = profit();
            long elapsed = sessionStart > 0 ? now - sessionStart : 0;
            String profitStr = !priced && revenue == 0 && questsCharged == 0 && gummiesEaten == 0 ? "-" : fmtCoins(profit);
            String perHour = elapsed >= 60_000 ? fmtCoins(profit / (elapsed / 3_600_000.0)) : "-";
            int profitColor = profit >= 0 ? 0xFF55FF55 : 0xFFFF5555;

            // label (grey) + value (coloured), values lined up in one column; null = small gap
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{"Time", clock, 0xFFFFFFFF});
            rows.add(new Object[]{"Bosses", String.valueOf(bossesKilled), 0xFFFFFFFF});
            if (gummyRem > 0) rows.add(new Object[]{"Gummy", fmtTimer(gummyRem), gummyColor});
            rows.add(null);
            rows.add(new Object[]{"Avg kill", avgKill, AVG_COLOR});
            rows.add(new Object[]{"Avg spawn", avgSpawn, AVG_COLOR});
            rows.add(new Object[]{"Avg quest", avgQuest, AVG_COLOR});
            rows.add(null);
            rows.add(new Object[]{"Profit", profitStr, profitColor});
            rows.add(new Object[]{"Per hour", perHour, profitColor});
            int labelW = 0;
            for (Object[] r : rows) if (r != null) labelW = Math.max(labelW, mc.font.width((String) r[0]));
            for (Object[] r : rows) {
                if (r == null) { y += 4; continue; }
                g.text(mc.font, (String) r[0], x, y, 0xFFAAAAAA, true);
                g.text(mc.font, (String) r[1], x + labelW + 8, y, (Integer) r[2], true);
                y += line;
            }
            if (!priced) {
                g.text(mc.font, prices.failed() ? "Prices unavailable (/blaze refresh)" : "Prices loading...", x, y, 0xFFAAAAAA, true);
                y += line;
            } else if (unpriced > 0) {
                g.text(mc.font, unpriced + " unpriced (/blaze profit)", x, y, 0xFFAAAAAA, true);
                y += line;
            }
            y += 4;

            if (drops.isEmpty()) {
                g.text(mc.font, "No drops yet", x, y, 0xFFAAAAAA, true);
            } else {
                // Most profitable first: ordered by what each item has earned this session (net of tax).
                // Unpriced items sink to the bottom; ties keep first-drop order (List.sort is stable).
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(drops.entrySet());
                sorted.sort((d1, d2) -> Double.compare(sortValue(d2.getKey()), sortValue(d1.getKey())));
                int shown = Math.min(sorted.size(), MAX_DROP_LINES);

                // Two columns: names on the left, counts right-aligned in their own column
                int nameW = 0, countW = 0;
                for (int i = 0; i < shown; i++) {
                    nameW = Math.max(nameW, mc.font.width(shortName(sorted.get(i).getKey())));
                    countW = Math.max(countW, mc.font.width(countLabel(sorted.get(i).getKey())));
                }
                int countRight = x + nameW + 8 + countW;

                // Third column: what the item is worth in total (net of tax); "?" = no price found
                String[] values = new String[shown];
                int valW = 0;
                for (int i = 0; i < shown; i++) {
                    Double v = itemValue.get(sorted.get(i).getKey());
                    values[i] = v == null || Double.isNaN(v) ? "?" : fmtCoins(v);
                    valW = Math.max(valW, mc.font.width(values[i]));
                }
                int valRight = countRight + 8 + valW;
                for (int i = 0; i < shown; i++) {
                    String item = sorted.get(i).getKey();
                    String count = countLabel(item);
                    int color = dropColor(item);
                    g.text(mc.font, shortName(item), x, y, color, true);
                    g.text(mc.font, count, countRight - mc.font.width(count), y, color, true);
                    g.text(mc.font, values[i], valRight - mc.font.width(values[i]), y,
                            values[i].equals("?") ? 0xFF777777 : 0xFFFFAA00, true);
                    y += line;
                }
                if (sorted.size() > shown) {
                    g.text(mc.font, "+" + (sorted.size() - shown) + " more", x, y, 0xFFAAAAAA, true);
                }
            }
            tablePose.popMatrix();
        }
    }
}
