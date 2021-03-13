/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.network.translators.world.block;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.nukkitx.nbt.*;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.Getter;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.network.translators.world.chunk.ChunkSection;
import org.geysermc.connector.network.translators.world.chunk.EmptyChunkProvider;
import org.geysermc.connector.utils.FileUtils;
import org.reflections.Reflections;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public abstract class BlockTranslator {
    /**
     * The Java block runtime ID of air
     */
    public static final int JAVA_AIR_ID = 0;
    public static int JAVA_WATER_ID;
    /**
     * The Bedrock block runtime ID of air
     */
    private final int bedrockAirId;
    private final int bedrockWaterId;

    private final Int2IntMap javaToBedrockBlockMap = new Int2IntOpenHashMap();
    private final Int2IntMap bedrockToJavaBlockMap = new Int2IntOpenHashMap();
    /**
     * Stores a list of differences in block identifiers.
     * Items will not be added to this list if the key and value is the same.
     */
    private static final Object2ObjectMap<String, String> JAVA_TO_BEDROCK_IDENTIFIERS = new Object2ObjectOpenHashMap<>();
    private static final BiMap<String, Integer> JAVA_ID_BLOCK_MAP = HashBiMap.create();
    private static final IntSet WATERLOGGED = new IntOpenHashSet();
    private final Object2IntMap<NbtMap> itemFrames = new Object2IntOpenHashMap<>();
    private final Map<String, NbtMap> flowerPotBlocks = new HashMap<>();

    // Bedrock carpet ID, used in LlamaEntity.java for decoration
    public static final int CARPET = 171;

    public static final Int2DoubleMap JAVA_RUNTIME_ID_TO_HARDNESS = new Int2DoubleOpenHashMap();
    public static final Int2BooleanMap JAVA_RUNTIME_ID_TO_CAN_HARVEST_WITH_HAND = new Int2BooleanOpenHashMap();
    public static final Int2ObjectMap<String> JAVA_RUNTIME_ID_TO_TOOL_TYPE = new Int2ObjectOpenHashMap<>();

    // The index of the collision data in collision.json
    public static final Int2IntMap JAVA_RUNTIME_ID_TO_COLLISION_INDEX = new Int2IntOpenHashMap();

    private static final Int2ObjectMap<String> JAVA_RUNTIME_ID_TO_PICK_ITEM = new Int2ObjectOpenHashMap<>();

    /**
     * Java numeric ID to java unique identifier, used for block names in the statistics screen
     */
    public static final Int2ObjectMap<String> JAVA_ID_TO_JAVA_IDENTIFIER_MAP = new Int2ObjectOpenHashMap<>();

    /**
     * Runtime command block ID, used for fixing command block minecart appearances
     */
    @Getter
    private final int bedrockRuntimeCommandBlockId;

    private final EmptyChunkProvider emptyChunkProvider;

    /**
     * A list of all Java runtime wool IDs, for use with block breaking math and shears
     */
    public static final IntSet JAVA_RUNTIME_WOOL_IDS = new IntOpenHashSet();
    public static final int JAVA_RUNTIME_COBWEB_ID;

    public static final int JAVA_RUNTIME_FURNACE_ID;
    public static final int JAVA_RUNTIME_FURNACE_LIT_ID;

    public static final int JAVA_RUNTIME_SPAWNER_ID;

    /**
     * Contains a map of Java blocks to their respective Bedrock block tag, if the Java identifier is different from Bedrock.
     * Required to fix villager trades with these blocks.
     */
    private final Map<String, NbtMap> javaIdentifierToBedrockTag;

    /**
     * Stores the raw blocks JSON until it is no longer needed.
     */
    public static JsonNode BLOCKS_JSON;

    static {
        InputStream stream = FileUtils.getResource("mappings/blocks.json");
        try {
            BLOCKS_JSON = GeyserConnector.JSON_MAPPER.readTree(stream);
        } catch (Exception e) {
            throw new AssertionError("Unable to load Java block mappings", e);
        }

        Reflections ref = GeyserConnector.getInstance().useXmlReflections() ? FileUtils.getReflections("org.geysermc.connector.network.translators.world.block.entity")
                : new Reflections("org.geysermc.connector.network.translators.world.block.entity");

        int javaRuntimeId = -1;
        int cobwebRuntimeId = -1;
        int furnaceRuntimeId = -1;
        int furnaceLitRuntimeId = -1;
        int spawnerRuntimeId = -1;
        int uniqueJavaId = -1;
        Iterator<Map.Entry<String, JsonNode>> blocksIterator = BLOCKS_JSON.fields();
        while (blocksIterator.hasNext()) {
            javaRuntimeId++;
            Map.Entry<String, JsonNode> entry = blocksIterator.next();
            String javaId = entry.getKey();

            // TODO fix this, (no block should have a null hardness)
            JsonNode hardnessNode = entry.getValue().get("block_hardness");
            if (hardnessNode != null) {
                JAVA_RUNTIME_ID_TO_HARDNESS.put(javaRuntimeId, hardnessNode.doubleValue());
            }

            try {
                JAVA_RUNTIME_ID_TO_CAN_HARVEST_WITH_HAND.put(javaRuntimeId, entry.getValue().get("can_break_with_hand").booleanValue());
            } catch (Exception e) {
                JAVA_RUNTIME_ID_TO_CAN_HARVEST_WITH_HAND.put(javaRuntimeId, false);
            }

            JsonNode toolTypeNode = entry.getValue().get("tool_type");
            if (toolTypeNode != null) {
                JAVA_RUNTIME_ID_TO_TOOL_TYPE.put(javaRuntimeId, toolTypeNode.textValue());
            }

            JsonNode collisionIndexNode = entry.getValue().get("collision_index");
            if (hardnessNode != null) {
                JAVA_RUNTIME_ID_TO_COLLISION_INDEX.put(javaRuntimeId, collisionIndexNode.intValue());
            }

            JsonNode pickItemNode = entry.getValue().get("pick_item");
            if (pickItemNode != null) {
                JAVA_RUNTIME_ID_TO_PICK_ITEM.put(javaRuntimeId, pickItemNode.textValue());
            }

            JAVA_ID_BLOCK_MAP.put(javaId, javaRuntimeId);

            BlockStateValues.storeBlockStateValues(entry.getKey(), javaRuntimeId, entry.getValue());

            String cleanJavaIdentifier = entry.getKey().split("\\[")[0];
            String bedrockIdentifier = entry.getValue().get("bedrock_identifier").asText();

            if (!JAVA_ID_TO_JAVA_IDENTIFIER_MAP.containsValue(cleanJavaIdentifier)) {
                uniqueJavaId++;
                JAVA_ID_TO_JAVA_IDENTIFIER_MAP.put(uniqueJavaId, cleanJavaIdentifier);
            }

            // Keeping this here since this is currently unchanged between versions
            if (!cleanJavaIdentifier.equals(bedrockIdentifier)) {
                JAVA_TO_BEDROCK_IDENTIFIERS.put(cleanJavaIdentifier, bedrockIdentifier);
            }

            if (javaId.contains("wool")) {
                JAVA_RUNTIME_WOOL_IDS.add(javaRuntimeId);

            } else if (javaId.contains("cobweb")) {
                cobwebRuntimeId = javaRuntimeId;

            } else if (javaId.startsWith("minecraft:furnace[facing=north")) {
                if (javaId.contains("lit=true")) {
                    furnaceLitRuntimeId = javaRuntimeId;
                } else {
                    furnaceRuntimeId = javaRuntimeId;
                }

            } else if (javaId.startsWith("minecraft:spawner")) {
                spawnerRuntimeId = javaRuntimeId;
            }
        }

        if (cobwebRuntimeId == -1) {
            throw new AssertionError("Unable to find cobwebs in palette");
        }
        JAVA_RUNTIME_COBWEB_ID = cobwebRuntimeId;

        if (furnaceRuntimeId == -1) {
            throw new AssertionError("Unable to find furnace in palette");
        }
        JAVA_RUNTIME_FURNACE_ID = furnaceRuntimeId;

        if (furnaceLitRuntimeId == -1) {
            throw new AssertionError("Unable to find lit furnace in palette");
        }
        JAVA_RUNTIME_FURNACE_LIT_ID = furnaceLitRuntimeId;

        if (spawnerRuntimeId == -1) {
            throw new AssertionError("Unable to find spawner in palette");
        }
        JAVA_RUNTIME_SPAWNER_ID = spawnerRuntimeId;

        BlockTranslator1_16_100.init();
        BlockTranslator1_16_210.init();
        BLOCKS_JSON = null; // We no longer require this so let it garbage collect away
    }

    public BlockTranslator(String paletteFile) {
        /* Load block palette */
        InputStream stream = FileUtils.getResource(paletteFile);

        NbtList<NbtMap> blocksTag;
        try (NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)))) {
            NbtMap blockPalette = (NbtMap) nbtInputStream.readTag();
            blocksTag = (NbtList<NbtMap>) blockPalette.getList("blocks", NbtType.COMPOUND);
        } catch (Exception e) {
            throw new AssertionError("Unable to get blocks from runtime block states", e);
        }

        javaIdentifierToBedrockTag = new Object2ObjectOpenHashMap<>();

        // New since 1.16.100 - find the block runtime ID by the order given to us in the block palette,
        // as we no longer send a block palette
        Object2IntMap<NbtMap> blockStateOrderedMap = new Object2IntOpenHashMap<>(blocksTag.size());

        for (int i = 0; i < blocksTag.size(); i++) {
            NbtMap tag = blocksTag.get(i);
            if (blockStateOrderedMap.containsKey(tag)) {
                throw new AssertionError("Duplicate block states in Bedrock palette: " + tag);
            }
            blockStateOrderedMap.put(tag, i);
        }

        int airRuntimeId = -1;
        int commandBlockRuntimeId = -1;
        int javaRuntimeId = -1;
        int javaWaterRuntimeId = -1;
        int bedrockWaterRuntimeId = -1;
        Iterator<Map.Entry<String, JsonNode>> blocksIterator = BLOCKS_JSON.fields();
        while (blocksIterator.hasNext()) {
            javaRuntimeId++;
            Map.Entry<String, JsonNode> entry = blocksIterator.next();
            String javaId = entry.getKey();

            NbtMap blockTag = buildBedrockState(entry.getValue());
            int bedrockRuntimeId = blockStateOrderedMap.getOrDefault(blockTag, -1);
            if (bedrockRuntimeId == -1) {
                throw new RuntimeException("Unable to find " + javaId + " Bedrock runtime ID! Built compound tag: \n" + blockTag);
            }

            switch (javaId) {
                case "minecraft:air":
                    airRuntimeId = bedrockRuntimeId;
                    break;
                case "minecraft:water[level=0]":
                    bedrockWaterRuntimeId = bedrockRuntimeId;
					javaWaterRuntimeId = javaRuntimeId;
                    break;
                case "minecraft:command_block[conditional=false,facing=north]":
                    commandBlockRuntimeId = bedrockRuntimeId;
                    break;
            }

            boolean waterlogged = entry.getKey().contains("waterlogged=true")
                    || javaId.contains("minecraft:bubble_column") || javaId.contains("minecraft:kelp") || javaId.contains("seagrass");

            if (waterlogged) {
                bedrockToJavaBlockMap.putIfAbsent(bedrockRuntimeId | 1 << 31, javaRuntimeId);
                WATERLOGGED.add(javaRuntimeId);
            } else {
                bedrockToJavaBlockMap.putIfAbsent(bedrockRuntimeId, javaRuntimeId);
            }

            String cleanJavaIdentifier = entry.getKey().split("\\[")[0];

            // Get the tag needed for non-empty flower pots
            if (entry.getValue().get("pottable") != null) {
                flowerPotBlocks.put(cleanJavaIdentifier, blockTag);
            }

            if (!cleanJavaIdentifier.equals(entry.getValue().get("bedrock_identifier").asText())) {
                javaIdentifierToBedrockTag.put(cleanJavaIdentifier, blockTag);
            }

            javaToBedrockBlockMap.put(javaRuntimeId, bedrockRuntimeId);
        }

        if (commandBlockRuntimeId == -1) {
            throw new AssertionError("Unable to find command block in palette");
        }
        bedrockRuntimeCommandBlockId = commandBlockRuntimeId;

        if (bedrockWaterRuntimeId == -1) {
            throw new AssertionError("Unable to find Bedrock water in palette");
        }
        bedrockWaterId = bedrockWaterRuntimeId;

        if (javaWaterRuntimeId == -1) {
            throw new AssertionError("Unable to find Java water in palette");
        }
        JAVA_WATER_ID = javaWaterRuntimeId;

        if (airRuntimeId == -1) {
            throw new AssertionError("Unable to find air in palette");
        }
        bedrockAirId = airRuntimeId;

        // Loop around again to find all item frame runtime IDs
        for (Object2IntMap.Entry<NbtMap> entry : blockStateOrderedMap.object2IntEntrySet()) {
            if (entry.getKey().getString("name").equals("minecraft:frame")) {
                itemFrames.put(entry.getKey(), entry.getIntValue());
            }
        }

        this.emptyChunkProvider = new EmptyChunkProvider(bedrockAirId);
    }

    public static void init() {
        // no-op
    }

    private NbtMap buildBedrockState(JsonNode node) {
        NbtMapBuilder tagBuilder = NbtMap.builder();
        String bedrockIdentifier = node.get("bedrock_identifier").textValue();
        tagBuilder.putString("name", bedrockIdentifier)
                .putInt("version", getBlockStateVersion());

        NbtMapBuilder statesBuilder = NbtMap.builder();

        // check for states
        if (node.has("bedrock_states")) {
            Iterator<Map.Entry<String, JsonNode>> statesIterator = node.get("bedrock_states").fields();

            while (statesIterator.hasNext()) {
                Map.Entry<String, JsonNode> stateEntry = statesIterator.next();
                JsonNode stateValue = stateEntry.getValue();
                switch (stateValue.getNodeType()) {
                    case BOOLEAN:
                        statesBuilder.putBoolean(stateEntry.getKey(), stateValue.booleanValue());
                        continue;
                    case STRING:
                        statesBuilder.putString(stateEntry.getKey(), stateValue.textValue());
                        continue;
                    case NUMBER:
                        statesBuilder.putInt(stateEntry.getKey(), stateValue.intValue());
                }
            }
        }
        tagBuilder.put("states", adjustBlockStateForVersion(bedrockIdentifier, statesBuilder).build());
        return tagBuilder.build();
    }

    /**
     * @return an adjusted state list, if necessary, that converts Geyser's new mapping to Bedrock's older version
     * of the mapping.
     */
    protected NbtMapBuilder adjustBlockStateForVersion(String bedrockIdentifier, NbtMapBuilder statesBuilder) {
        return statesBuilder;
    }

    public int getBedrockBlockId(int state) {
        return javaToBedrockBlockMap.get(state);
    }

    public int getJavaBlockState(int bedrockId) {
        return bedrockToJavaBlockMap.get(bedrockId);
    }

    /**
     * @param javaIdentifier the Java identifier of the block to search for
     * @return the Bedrock identifier if different, or else the Java identifier
     */
    public String getBedrockBlockIdentifier(String javaIdentifier) {
        return JAVA_TO_BEDROCK_IDENTIFIERS.getOrDefault(javaIdentifier, javaIdentifier);
    }

    public int getItemFrame(NbtMap tag) {
        return itemFrames.getOrDefault(tag, -1);
    }

    public boolean isItemFrame(int bedrockBlockRuntimeId) {
        return itemFrames.values().contains(bedrockBlockRuntimeId);
    }

    /**
     * Get the map of contained flower pot plants to Bedrock CompoundTag
     *
     * @return Map of flower pot blocks.
     */
    public Map<String, NbtMap> getFlowerPotBlocks() {
        return flowerPotBlocks;
    }

    public int getBedrockAirId() {
        return bedrockAirId;
    }

    public int getBedrockWaterId() {
        return bedrockWaterId;
    }

    /**
     * @return the "block state version" generated in the Bedrock block palette that completes an NBT indication of a
     * block state.
     */
    public abstract int getBlockStateVersion();

    public byte[] getEmptyChunkData() {
        return emptyChunkProvider.getEmptyLevelChunkData();
    }

    public ChunkSection getEmptyChunkSection() {
        return emptyChunkProvider.getEmptySection();
    }

    /**
     * @param javaId the Java string identifier to search for
     * @return the Java block state integer, or {@link #JAVA_AIR_ID} if there is no valid entry.
     */
    public static int getJavaBlockState(String javaId) {
        return JAVA_ID_BLOCK_MAP.getOrDefault(javaId, JAVA_AIR_ID);
    }

    public static boolean isWaterlogged(int state) {
        return WATERLOGGED.contains(state);
    }

    public static BiMap<String, Integer> getJavaIdBlockMap() {
        return JAVA_ID_BLOCK_MAP;
    }

    /**
     * Get the item a Java client would receive when pressing
     * the Pick Block key on a specific Java block state.
     *
     * @param javaId The Java runtime id of the block
     * @return The Java identifier of the item
     */
    public static String getPickItem(int javaId) {
        String itemIdentifier = JAVA_RUNTIME_ID_TO_PICK_ITEM.get(javaId);
        if (itemIdentifier == null) {
            return JAVA_ID_BLOCK_MAP.inverse().get(javaId).split("\\[")[0];
        }
        return itemIdentifier;
    }

    /**
     * @return a list of all Java block identifiers. For use with command suggestions.
     */
    public static String[] getAllBlockIdentifiers() {
        return JAVA_ID_TO_JAVA_IDENTIFIER_MAP.values().toArray(new String[0]);
    }

    /**
     * @param cleanJavaIdentifier the clean Java identifier of the block to look up
     *
     * @return the block tag of the block name mapped from Java to Bedrock.
     */
    public NbtMap getBedrockBlockNbt(String cleanJavaIdentifier) {
        return javaIdentifierToBedrockTag.get(cleanJavaIdentifier);
    }
}
