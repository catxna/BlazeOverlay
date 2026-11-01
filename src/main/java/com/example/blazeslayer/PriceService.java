package com.example.blazeslayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * What a drop is worth.
 *
 *  - Bazaar items  -> instant-sell price (highest buy order), minus the Bazaar tax
 *  - Everything else -> lowest BIN, minus the Auction House listing + claim fees
 *
 * All network work happens on one background thread; the game thread only ever reads an
 * immutable snapshot, so lookups are cheap and never block.
 *
 * Sources:
 *  - Bazaar:       Hypixel's public API (no key needed)
 *  - Lowest BIN:   Moulberry's lowestbin.json (what NEU uses), falling back to lb.tricked.pro
 *  - Item names:   Hypixel's public items resource (maps "Archfiend Dice" -> item id)
 */
final class PriceService {

    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final String[] LOWEST_BIN_URLS = {
            "https://moulberry.codes/lowestbin.json",
            "https://lb.tricked.pro/lowestbins"
    };

    private static final long REFRESH_MS = 5 * 60_000;   // normal refresh interval
    private static final long RETRY_MS = 60_000;         // after a failed refresh

    // ---- tax model (matches the in-game numbers) ---------------------------
    private static final double BAZAAR_TAX = 0.0125;     // 1.25% on instant sells
    private static final double AH_CLAIM_TAX = 0.01;     // 1% when collecting a sale above 1M
    private static final double AH_CLAIM_THRESHOLD = 1_000_000;
    // BIN listing fee: 1% under 10M, 2% from 10M to 100M, 2.5% from 100M up.
    // (The small flat duration fee is ignored; Derpy's 4x tax is not modelled.)

    /** Turn taxes off to see gross prices. */
    volatile boolean taxes = true;

    /**
     * Flat per-unit values that ignore the market (and tax). Key = item name in lower-case letters only,
     * matched with "contains". Edit the numbers here to change them.
     */
    private static final Object[][] FIXED_PRICES = {
            {"wisp", 20_000.0},               // Wisp's Ice-Flavored Water splash potion
            {"manadisintegrator", 1_000.0},   // Mana Disintegrator
            {"kelvininverter", 100_000.0},    // Kelvin Inverter: always 100k each, no tax
            {"netherstar", 30_000.0},         // Nether Star: 30k each, no tax
            {"rune", 1.0},                    // every rune (Fiery Burst, Lavatears, ...): 1 coin each, no tax
            {"netherracklookingsunshade", 10_000.0},   // Netherrack-Looking Sunshade: always 10k each, no tax
    };

    /** A resolved price. {@code gross} is before tax; {@code fixed} means a flat value from FIXED_PRICES. */
    record Price(String id, boolean bazaar, double gross, boolean fixed) {
        String source() { return fixed ? "fixed" : bazaar ? "BZ" : "BIN"; }
    }

    private static final class Snapshot {
        final Map<String, Double> bazaar;      // id -> instant-sell price
        final Map<String, Double> bazaarBuy;   // id -> instant-buy price (lowest sell offer)
        final Map<String, Double> bin;         // id -> lowest BIN
        final Map<String, String> names;       // normalised item name -> id
        final ConcurrentHashMap<String, String> resolved = new ConcurrentHashMap<>();   // drop name -> id ("" = unknown)

        Snapshot(Map<String, Double> bazaar, Map<String, Double> bazaarBuy, Map<String, Double> bin, Map<String, String> names) {
            this.bazaar = bazaar;
            this.bazaarBuy = bazaarBuy;
            this.bin = bin;
            this.names = names;
        }

        boolean has(String id) { return bazaar.containsKey(id) || bin.containsKey(id); }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BSH-prices");
        t.setDaemon(true);
        return t;
    });

    private volatile Snapshot snap;
    private volatile boolean loading = false;
    private volatile long nextAttempt = 0;
    private volatile long lastOk = 0;
    private volatile String lastError = "";

    // ------------------------------------------------------------ public API

    boolean ready() { return snap != null; }

    boolean loading() { return loading; }

    boolean failed() { return snap == null && !loading && !lastError.isEmpty(); }

    /** Cheap; call every tick. Starts a background refresh when one is due. */
    void refreshIfDue() {
        long now = System.currentTimeMillis();
        if (loading || now < nextAttempt) return;
        loading = true;
        nextAttempt = now + REFRESH_MS;
        EXEC.execute(this::refresh);
    }

    void refreshNow() {
        nextAttempt = 0;
        refreshIfDue();
    }

    String status() {
        if (snap == null) return loading ? "loading..." : lastError.isEmpty() ? "waiting" : "unavailable (" + lastError + ")";
        String age = "updated " + (System.currentTimeMillis() - lastOk) / 1000 + "s ago";
        return lastError.isEmpty() ? age : age + " (last refresh failed: " + lastError + ")";
    }

    /** Price for a drop name as it appears in chat, or null if it can't be priced. */
    Price lookup(String item) {
        if (item == null || item.isBlank()) return null;
        String n = norm(item);
        for (Object[] f : FIXED_PRICES) {
            if (n.contains((String) f[0])) return new Price("FIXED", false, (Double) f[1], true);   // works even before prices load
        }
        Snapshot s = snap;
        if (s == null) return null;
        String id = s.resolved.computeIfAbsent(item, k -> {
            String r = resolve(s, k);
            return r == null ? "" : r;
        });
        if (id.isEmpty()) return null;
        Double bz = s.bazaar.get(id);
        if (bz != null) return new Price(id, true, bz, false);
        Double bin = s.bin.get(id);
        return bin != null ? new Price(id, false, bin, false) : null;
    }

    /** Bazaar instant-buy price (what you pay for one unit), or null if it isn't a priced Bazaar item. */
    Double instaBuy(String item) {
        Snapshot s = snap;
        if (s == null || item == null || item.isBlank()) return null;
        String id = s.resolved.computeIfAbsent(item, k -> {
            String r = resolve(s, k);
            return r == null ? "" : r;
        });
        return id.isEmpty() ? null : s.bazaarBuy.get(id);
    }

    /** Coins you actually end up with for one unit. */
    double net(Price p) {
        if (p.fixed() || !taxes) return p.gross();
        if (p.bazaar()) return p.gross() * (1 - BAZAAR_TAX);
        double g = p.gross();
        double listing = g < 10_000_000 ? 0.01 : g < 100_000_000 ? 0.02 : 0.025;
        double fee = g * listing + (g > AH_CLAIM_THRESHOLD ? g * AH_CLAIM_TAX : 0);
        return g - fee;
    }

    // ------------------------------------------------------------ refresh

    private void refresh() {
        long now = System.currentTimeMillis();
        try {
            Snapshot old = snap;
            Map<String, Double> bz = old != null ? old.bazaar : Map.of();
            Map<String, Double> bzBuy = old != null ? old.bazaarBuy : Map.of();
            Map<String, Double> bin = old != null ? old.bin : Map.of();
            Map<String, String> names = old != null ? old.names : Map.of();
            StringBuilder err = new StringBuilder();

            try {
                Map<String, Double>[] b = fetchBazaar();
                bz = b[0];
                bzBuy = b[1];
            } catch (Exception e) { err.append("bazaar: ").append(msg(e)).append(' '); }
            try { bin = fetchLowestBin(); } catch (Exception e) { err.append("lowest bin: ").append(msg(e)).append(' '); }
            if (names.isEmpty()) {
                try { names = fetchNames(); } catch (Exception e) { err.append("item names: ").append(msg(e)).append(' '); }
            }

            if (!bz.isEmpty() || !bin.isEmpty()) {
                snap = new Snapshot(bz, bzBuy, bin, names);
                if (err.length() == 0) lastOk = now;
                else if (lastOk == 0) lastOk = now;
            }
            lastError = err.toString().trim();
            if (!lastError.isEmpty()) nextAttempt = now + RETRY_MS;
        } catch (RuntimeException e) {
            lastError = msg(e);
            nextAttempt = now + RETRY_MS;
        } finally {
            loading = false;
        }
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static JsonElement getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "BlazeSlayerHelper/1.0")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .GET().build();
        HttpResponse<InputStream> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream raw = r.body()) {
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            boolean gz = "gzip".equalsIgnoreCase(r.headers().firstValue("Content-Encoding").orElse(""));
            InputStream in = gz ? new GZIPInputStream(raw) : raw;
            try (Reader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(rd);
            }
        }
    }

    /**
     * Instant-sell = the best (highest) buy order. In Hypixel's Bazaar API that list is called
     * "sell_summary" (it is what you sell INTO), which is easy to read backwards. Taking the max
     * of the list means the order it comes in doesn't matter.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Double>[] fetchBazaar() throws IOException, InterruptedException {
        JsonObject root = getJson(BAZAAR_URL).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) throw new IOException("bazaar API said success=false");
        Map<String, Double> out = new HashMap<>();
        Map<String, Double> buy = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("products").entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            double best = 0;
            JsonArray sell = p.getAsJsonArray("sell_summary");
            if (sell != null) {
                for (JsonElement o : sell) best = Math.max(best, o.getAsJsonObject().get("pricePerUnit").getAsDouble());
            }
            if (best <= 0 && p.has("quick_status")) {
                best = p.getAsJsonObject("quick_status").get("sellPrice").getAsDouble();
            }
            if (best > 0) out.put(e.getKey(), best);

            // Instant-buy = the cheapest sell offer, which the API calls "buy_summary".
            double cheapest = Double.MAX_VALUE;
            JsonArray offers = p.getAsJsonArray("buy_summary");
            if (offers != null) {
                for (JsonElement o : offers) cheapest = Math.min(cheapest, o.getAsJsonObject().get("pricePerUnit").getAsDouble());
            }
            if (cheapest == Double.MAX_VALUE && p.has("quick_status")) {
                cheapest = p.getAsJsonObject("quick_status").get("buyPrice").getAsDouble();
            }
            if (cheapest > 0 && cheapest != Double.MAX_VALUE) buy.put(e.getKey(), cheapest);
        }
        if (out.isEmpty()) throw new IOException("bazaar returned no products");
        return new Map[]{out, buy};
    }

    private static Map<String, Double> fetchLowestBin() throws IOException, InterruptedException {
        Exception last = null;
        for (String url : LOWEST_BIN_URLS) {
            try {
                Map<String, Double> out = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : getJson(url).getAsJsonObject().entrySet()) {
                    JsonElement v = e.getValue();
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() > 0) {
                        out.put(e.getKey(), v.getAsDouble());
                    }
                }
                if (!out.isEmpty()) return out;
                last = new IOException("empty response from " + url);
            } catch (IOException | RuntimeException e) {
                last = e;   // try the next source
            }
        }
        throw last instanceof IOException io ? io : new IOException(last);
    }

    private static Map<String, String> fetchNames() throws IOException, InterruptedException {
        JsonObject root = getJson(ITEMS_URL).getAsJsonObject();
        Map<String, String> out = new HashMap<>();
        for (JsonElement el : root.getAsJsonArray("items")) {
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id") || !o.has("name")) continue;
            out.putIfAbsent(norm(o.get("name").getAsString()), o.get("id").getAsString());
        }
        return out;
    }

    // ------------------------------------------------------------ name -> item id

    /**
     * Every Blaze Slayer drop with its item id, checked before any guessing. The key is the item
     * name in lower-case letters only; the first key contained in the drop name wins, so the longer
     * "highclassarchfiend" must stay above "archfienddice". Whether an item is priced from the Bazaar
     * or from lowest BIN is decided automatically: if the id is on the Bazaar it uses insta-sell,
     * otherwise lowest BIN.
     */
    private static final String[][] OVERRIDES = {
            // Auction House (lowest BIN)
            {"highclassarchfiend", "HIGH_CLASS_ARCHFIEND_DICE"},
            {"archfienddice", "ARCHFIEND_DICE"},
            {"subzeroinverter", "SUBZERO_INVERTER"},
            {"kelvininverter", "KELVIN_INVERTER"},
            {"wilson", "WILSON_ENGINEERING_PLANS"},
            {"flamedye", "DYE_FLAME"},
            {"scorchedbooks", "SCORCHED_BOOKS"},
            // Enchanted books are not in Hypixel's items resource, so their ids can't be guessed from the name
            {"duplex", "ENCHANTMENT_ULTIMATE_REITERATE_1"},      // Duplex's internal name is Reiterate
            {"fireaspectiii", "ENCHANTMENT_FIRE_ASPECT_3"},
            {"smolderingi", "ENCHANTMENT_SMOLDERING_1"},
            {"fieryburstrune", "FIERY_BURST_RUNE;1"},
            {"lavatearsrune", "LAVATEARS_RUNE;1"},
            // Bazaar (insta-sell)
            {"flawedopal", "FLAWED_OPAL_GEM"},
            {"scorchedpowercrystal", "SCORCHED_POWER_CRYSTAL"},
            {"gabagooldistillate", "GABAGOOL_DISTILLATE"},
            {"netherwartdistillate", "NETHER_WART_DISTILLATE"},
            {"magmacreamdistillate", "MAGMA_CREAM_DISTILLATE"},
            {"glowstonedistillate", "GLOWSTONE_DISTILLATE"},
            {"blazeroddistillate", "BLAZE_ROD_DISTILLATE"},
            {"manadisintegrator", "MANA_DISINTEGRATOR"},
            {"paragonshard", "SHARD_PARAGON"},
            {"infernodemonlordshard", "SHARD_BURNINGSOUL"},      // internally still the Burningsoul shard
            {"burningsoulshard", "SHARD_BURNINGSOUL"},
            {"derelictashe", "DERELICT_ASHE"},
            {"enchantedblazepowder", "ENCHANTED_BLAZE_POWDER"},
            {"bundleofmagmaarrows", "BUNDLE_OF_MAGMA_ARROWS"},
            {"re-heatedgummy", "RE_HEATED_GUMMY_POLAR_BEAR"},
            {"reheatedgummy", "RE_HEATED_GUMMY_POLAR_BEAR"},
    };

    private static final Pattern TRAILING_LEVEL = Pattern.compile("^(.*\\S)\\s+([IVXL]+|\\d+)$");

    /** Lower-case letters only, colour codes dropped: "Sub-zero Inverter" == "subzero inverter". */
    static String norm(String s) {
        return s.replaceAll("\u00A7.", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static String snake(String s) {
        return s.replaceAll("\u00A7.", "").replace("'", "").replace("\u2019", "").trim()
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static int parseLevel(String s) {
        if (s.chars().allMatch(Character::isDigit)) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
        }
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) { case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; default -> -1; };
            if (v < 0) return -1;
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    /** Item ids to try, most likely first. */
    static List<String> candidates(String item, Map<String, String> names) {
        LinkedHashSet<String> c = new LinkedHashSet<>();
        String clean = item.replaceAll("\u00A7.", "").trim();
        String n = norm(clean);

        for (String[] o : OVERRIDES) if (n.contains(o[0])) c.add(o[1]);
        String byName = names.get(n);
        if (byName != null) c.add(byName);

        // "Fire Aspect III", "Enchanted Book (Duplex I)", "Fiery Burst Rune I" ...
        String core = clean.replaceAll("(?i)enchanted book", " ").replaceAll("[()\\[\\]]", " ").replaceAll("\\s+", " ").trim();
        String base = core;
        int lvl = -1;
        Matcher m = TRAILING_LEVEL.matcher(core);
        if (m.find()) {
            base = m.group(1);
            lvl = parseLevel(m.group(2));
        }
        String snake = snake(base);
        c.add(snake(clean));
        if (!snake.isEmpty()) {
            if (lvl > 0) {
                c.add("ENCHANTMENT_" + snake + "_" + lvl);
                c.add("ENCHANTMENT_ULTIMATE_" + snake + "_" + lvl);
                c.add(snake + ";" + lvl);
            } else {
                c.add(snake + ";1");
                c.add(snake);
            }
        }
        return new ArrayList<>(c);
    }

    private static String resolve(Snapshot s, String item) {
        List<String> cands = candidates(item, s.names);
        for (String id : cands) if (s.has(id)) return id;

        // Variant items (runes etc.) are keyed "BASE;level": take the lowest level that is listed.
        String best = null;
        int bestLvl = Integer.MAX_VALUE;
        for (String id : cands) {
            if (id.contains(";")) continue;
            String prefix = id + ";";
            for (String k : s.bin.keySet()) {
                if (!k.startsWith(prefix)) continue;
                try {
                    int l = Integer.parseInt(k.substring(prefix.length()));
                    if (l < bestLvl) { bestLvl = l; best = k; }
                } catch (NumberFormatException ignored) { }
            }
        }
        return best;
    }

    // ------------------------------------------------------------ test hook

    /** Lets tests (or a future offline mode) inject data without touching the network. */
    void setData(Map<String, Double> bazaar, Map<String, Double> bazaarBuy, Map<String, Double> bin, Map<String, String> names) {
        snap = new Snapshot(bazaar, bazaarBuy, bin, names);
        lastOk = System.currentTimeMillis();
    }
}
