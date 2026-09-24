package com.realisticmarkets.mod.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.realisticmarkets.mod.RealisticMarkets;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * CLAUDE.md invariants checked against the data every loaded pack provides (vanilla, this mod, other mods):
 * only the three Tier 0 blocks have vanilla recipes, and mod items never drop from loot tables
 * except a mod block dropping itself.
 */
public class InvariantGameTests {
    private static final String NS = RealisticMarkets.MOD_ID + ":";
    private static final Set<String> TIER_0 = Set.of(
            NS + "basic_exchange", NS + "almanac_lectern", NS + "drafting_table");

    @GameTest
    public void onlyTierZeroBlocksHaveRecipes(GameTestHelper helper) {
        ResourceManager rm = helper.getLevel().getServer().getResourceManager();
        Set<String> tier0Found = new TreeSet<>();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> e : rm.listResources("recipe", id -> id.getPath().endsWith(".json")).entrySet()) {
            String result = resultId(read(e.getValue()));
            boolean ours = e.getKey().getNamespace().equals(RealisticMarkets.MOD_ID);
            if (result != null && result.startsWith(NS)) {
                if (TIER_0.contains(result)) tier0Found.add(result);
                else violations.add(e.getKey() + " makes " + result);
            } else if (ours) {
                violations.add(e.getKey() + " is a mod recipe for " + result);
            }
        }
        check(violations.isEmpty(), "forbidden recipes: " + violations);
        check(tier0Found.equals(TIER_0), "every Tier 0 block needs a recipe, found " + tier0Found);
        helper.succeed();
    }

    @GameTest
    public void modItemsOnlyDropFromTheirOwnBlocks(GameTestHelper helper) {
        ResourceManager rm = helper.getLevel().getServer().getResourceManager();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> e : rm.listResources("loot_table", id -> id.getPath().endsWith(".json")).entrySet()) {
            Identifier id = e.getKey();
            String own = id.getNamespace().equals(RealisticMarkets.MOD_ID) && id.getPath().startsWith("loot_table/blocks/")
                    ? NS + id.getPath().substring("loot_table/blocks/".length(), id.getPath().length() - ".json".length())
                    : null;
            collectModNames(read(e.getValue()), own, id.toString(), violations);
        }
        check(violations.isEmpty(), "mod items in loot tables: " + violations);
        helper.succeed();
    }

    private static void collectModNames(JsonElement el, String allowed, String where, List<String> out) {
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> f : el.getAsJsonObject().entrySet()) {
                JsonElement v = f.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    String s = v.getAsString();
                    if (s.startsWith(NS) && !s.equals(allowed)) out.add(where + " -> " + s);
                } else {
                    collectModNames(v, allowed, where, out);
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement v : el.getAsJsonArray()) collectModNames(v, allowed, where, out);
        }
    }

    /** {@code "result": "id"} or {@code "result": {"id": ...}}; null if the recipe has no plain result. */
    private static String resultId(JsonElement json) {
        if (!json.isJsonObject()) return null;
        JsonElement r = json.getAsJsonObject().get("result");
        if (r == null) return null;
        if (r.isJsonPrimitive()) return r.getAsString();
        JsonObject o = r.isJsonObject() ? r.getAsJsonObject() : null;
        return o != null && o.has("id") ? o.get("id").getAsString() : null;
    }

    private static JsonElement read(Resource resource) {
        try (Reader r = resource.openAsReader()) {
            return JsonParser.parseReader(r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
