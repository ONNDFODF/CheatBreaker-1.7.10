package com.cheatbreaker.client.cosmetic;

import com.cheatbreaker.client.CheatBreaker;
import com.cheatbreaker.client.emote.EmoteManager;
import com.cheatbreaker.client.ui.screen.PlayerViewer;
import com.cheatbreaker.client.util.ClientVersion;
import com.cheatbreaker.client.util.Logging;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

/**
 * 本地装扮目录：把客户端自带的全部披风、翅膀、帽子和表情解锁到装扮界面里，
 * 并且离线保存"已装备"状态。
 *
 * <p>原本装扮列表完全由 player assets 服务器下发（{@code PlayerCosmeticsPacket}），
 * 服务器不通时 {@link CosmeticsManager#getList()} 恒为空，装扮界面什么也点不到。
 * 这里改为直接扫描 classpath 上的装扮资源生成完整目录，注册进同一个
 * {@link CosmeticsManager}，所以界面、渲染、最近使用、收藏等逻辑都不用改。
 *
 * <p>装备状态以 {@code 类型:名字}（和 {@link RecentCosmeticsManager#getKey(Cosmetic)} 一致）
 * 存到 {@code config/cheatbreaker-client-<版本>-<分支>/local_cosmetics.json}，
 * 启动时回填，因此重启后仍是上次穿戴的样子。
 */
public final class LocalCosmetics {
    private static final String CAPE_DIRECTORY = "client/cosmetics/capes";
    private static final String WINGS_DIRECTORY = "client/cosmetics/wings";
    private static final String HAT_DIRECTORY = "client/cosmetics/hats";
    private static final String THUMBNAIL_DIRECTORY = "/thumbnail/";
    private static final String THUMBNAIL_SUFFIX = "_thumbnail.png";
    private static final String STATE_FILE = "local_cosmetics.json";
    private static final String EQUIPPED_KEY = "equipped";

    /**
     * 翅膀模型的缩放系数。
     *
     * <p>翅膀模型本体是一个 56x56 单位的矩形，按 Minecraft 的 16 单位 = 1 方块换算，
     * 单侧 3.5 方块、双侧 7 方块的翼展；玩家肩宽只有 0.5 方块，直接用 1.0 会大得离谱。
     * 服务端下发的数据里这个值通常远小于 1，本地目录统一用这里给出的系数。
     */
    private static final float WINGS_SCALE = 0.12F;
    /** 已装备的装扮，键为 {@code 类型:名字}。 */
    private static final Set<String> equippedKeys = new LinkedHashSet<String>();
    private static boolean initialized;

    private LocalCosmetics() {
    }

    /** 构建本地装扮目录并应用已装备内容。需要在 {@link CosmeticsManager} 创建之后调用一次。 */
    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        loadState();

        CheatBreaker cheatBreaker = CheatBreaker.getInstance();
        if (cheatBreaker == null || cheatBreaker.getCosmeticsManager() == null) {
            return;
        }

        CosmeticsManager manager = cheatBreaker.getCosmeticsManager();
        List<Cosmetic> capes = buildTextured(CAPE_DIRECTORY, CosmeticType.CAPE, false);
        List<Cosmetic> wings = buildTextured(WINGS_DIRECTORY, CosmeticType.WINGS, true);
        List<Cosmetic> hats = buildHats();

        manager.getCosmeticList().clear();
        manager.getCosmeticList().addAll(capes);
        manager.getCosmeticList2().clear();
        manager.getCosmeticList2().addAll(wings);
        manager.getCosmeticList3().clear();
        manager.getCosmeticList3().addAll(hats);

        unlockAllEmotes();
        applyToPreview();

        int emotes = cheatBreaker.getEmoteManager() == null ? 0 : cheatBreaker.getEmoteManager().getOwnedEmotes().size();
        Logging.infoTagged("Cosmetics", "Unlocked %d capes, %d wings, %d hats and %d emotes", capes.size(), wings.size(), hats.size(), emotes);
    }

    /** 把当前装备状态写回配置文件。装扮界面每次切换装备都会走到这里。 */
    public static void save() {
        CheatBreaker cheatBreaker = CheatBreaker.getInstance();
        if (cheatBreaker == null || cheatBreaker.getCosmeticsManager() == null) {
            return;
        }

        equippedKeys.clear();
        collectEquipped(cheatBreaker.getCosmeticsManager().getCosmeticList());
        collectEquipped(cheatBreaker.getCosmeticsManager().getCosmeticList2());
        collectEquipped(cheatBreaker.getCosmeticsManager().getCosmeticList3());
        writeState();
        applyToPreview();
    }

    /** 把已装备的披风 / 翅膀 / 帽子同步到菜单里的预览模型。 */
    public static void applyToPreview() {
        CheatBreaker cheatBreaker = CheatBreaker.getInstance();
        if (cheatBreaker == null || cheatBreaker.getCosmeticsManager() == null) {
            return;
        }

        CosmeticsManager manager = cheatBreaker.getCosmeticsManager();
        Cosmetic cape = findEquipped(manager.getCosmeticList());
        Cosmetic wings = findEquipped(manager.getCosmeticList2());
        Cosmetic hat = findEquipped(manager.getCosmeticList3());

        PlayerViewer.guiButtonPress = cape == null ? null : cape.getTexture();

        if (PlayerViewer.previewPlayer != null) {
            PlayerViewer.previewPlayer.setCosmetic2(wings);
            PlayerViewer.previewPlayer.setCosmetic3(hat);
        }
    }

    /** 把已装备的装扮同步到世界里的本地玩家。 */
    public static void applyToPlayer(EntityPlayer player) {
        if (player == null) {
            return;
        }

        CheatBreaker cheatBreaker = CheatBreaker.getInstance();
        if (cheatBreaker == null || cheatBreaker.getCosmeticsManager() == null) {
            return;
        }

        CosmeticsManager manager = cheatBreaker.getCosmeticsManager();
        player.setCosmetic(findEquipped(manager.getCosmeticList()));
        player.setCosmetic2(findEquipped(manager.getCosmeticList2()));
        player.setCosmetic3(findEquipped(manager.getCosmeticList3()));
    }

    /** 表情全部解锁（内置动作表情 + 资源表情）。 */
    private static void unlockAllEmotes() {
        CheatBreaker cheatBreaker = CheatBreaker.getInstance();
        if (cheatBreaker == null || cheatBreaker.getEmoteManager() == null) {
            return;
        }

        EmoteManager emoteManager = cheatBreaker.getEmoteManager();
        Set<Integer> all = new LinkedHashSet<Integer>();

        for (Object id : EmoteManager.emoteClassIds.keySet()) {
            all.add((Integer)id);
        }

        all.addAll(EmoteManager.resourceEmoteIds.keySet());

        List<Integer> owned = emoteManager.getOwnedEmotes();
        owned.clear();
        owned.addAll(all);
        emoteManager.refreshLocalEmote();
    }

    /** 披风 / 翅膀：目录下每个 png 就是一个可用装扮。 */
    private static List<Cosmetic> buildTextured(String directory, CosmeticType type, boolean skipThumbnails) {
        List<Cosmetic> cosmetics = new ArrayList<Cosmetic>();

        for (String path : CosmeticResources.list(directory)) {
            if (!path.endsWith(".png") || path.endsWith(THUMBNAIL_SUFFIX)) {
                continue;
            }

            if (skipThumbnails && path.contains(THUMBNAIL_DIRECTORY)) {
                continue;
            }

            cosmetics.add(create(path, type, fileStem(path)));
        }

        return cosmetics;
    }

    /**
     * 帽子：每个 {@code <帽子目录>/<变体>_thumbnail.png} 是一个可选变体，
     * 纹理优先取同名 png，其次取模型 json 里引用的纹理，最后退回目录里任意一张贴图。
     */
    private static List<Cosmetic> buildHats() {
        Map<String, List<String>> byDirectory = new LinkedHashMap<String, List<String>>();

        for (String path : CosmeticResources.list(HAT_DIRECTORY)) {
            int slash = path.lastIndexOf('/');
            if (slash < 0) {
                continue;
            }

            String directory = path.substring(0, slash);
            List<String> files = byDirectory.get(directory);
            if (files == null) {
                files = new ArrayList<String>();
                byDirectory.put(directory, files);
            }

            files.add(path.substring(slash + 1));
        }

        List<Cosmetic> cosmetics = new ArrayList<Cosmetic>();

        for (Map.Entry<String, List<String>> entry : byDirectory.entrySet()) {
            String directory = entry.getKey();
            List<String> files = entry.getValue();
            String modelTexture = readModelTexture(directory);

            for (String file : files) {
                if (!file.endsWith(THUMBNAIL_SUFFIX)) {
                    continue;
                }

                String variant = file.substring(0, file.length() - THUMBNAIL_SUFFIX.length());
                String texture = resolveHatTexture(directory, variant, modelTexture, files);
                if (texture == null) {
                    continue;
                }

                cosmetics.add(create(texture, CosmeticType.HAT, variant));
            }
        }

        return cosmetics;
    }

    /** 选出帽子变体实际要用的贴图路径。 */
    private static String resolveHatTexture(String directory, String variant, String modelTexture, List<String> files) {
        String texture = directory + "/" + variant + ".png";
        if (CosmeticResources.exists(texture)) {
            return texture;
        }

        if (modelTexture != null) {
            String candidate = directory + "/" + modelTexture + ".png";
            if (CosmeticResources.exists(candidate)) {
                return candidate;
            }
        }

        for (String file : files) {
            if (file.endsWith(".png") && !file.endsWith(THUMBNAIL_SUFFIX)) {
                return directory + "/" + file;
            }
        }

        return null;
    }

    /** 读取帽子模型 json 里引用的第一张贴图名（不带扩展名），读不到返回 {@code null}。 */
    private static String readModelTexture(String directory) {
        String name = directory.substring(directory.lastIndexOf('/') + 1);
        String path = directory + "/" + name + ".json";

        try (InputStream stream = CosmeticResources.open(path)) {
            if (stream == null) {
                return null;
            }

            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement textures = json.get("textures");
            if (textures == null || !textures.isJsonObject()) {
                return null;
            }

            for (Map.Entry<String, JsonElement> entry : textures.getAsJsonObject().entrySet()) {
                if ("particle".equals(entry.getKey())) {
                    continue;
                }

                String value = entry.getValue().getAsString();
                if (value == null || value.isEmpty() || value.startsWith("#")) {
                    continue;
                }

                String textureName = value.substring(value.lastIndexOf('/') + 1);
                return textureName.endsWith(".png") ? textureName.substring(0, textureName.length() - 4) : textureName;
            }
        } catch (Exception e) {
            Logging.warnTagged("Cosmetics", "Could not read hat model %s: %s", path, e);
        }

        return null;
    }

    /** 用资源路径构造一个本地装扮条目。 */
    private static Cosmetic create(String texturePath, CosmeticType type, String name) {
        boolean equipped = equippedKeys.contains(type.getId() + ":" + name);
        float scale = type == CosmeticType.WINGS ? WINGS_SCALE : 1.0F;

        return new Cosmetic(
            Math.abs((long)texturePath.hashCode()),
            localPlayerId(),
            name,
            type,
            scale,
            equipped,
            "minecraft:" + texturePath,
            null
        );
    }

    private static Cosmetic findEquipped(List<Cosmetic> cosmetics) {
        for (Cosmetic cosmetic : cosmetics) {
            if (cosmetic.isEquipped()) {
                return cosmetic;
            }
        }

        return null;
    }

    private static void collectEquipped(List<Cosmetic> cosmetics) {
        for (Cosmetic cosmetic : cosmetics) {
            if (cosmetic.isEquipped()) {
                equippedKeys.add(RecentCosmeticsManager.getKey(cosmetic));
            }
        }
    }

    /** 本地玩家的标准 UUID 字符串，和后端下发数据用的是同一个格式。 */
    private static String localPlayerId() {
        String playerId = Minecraft.getMinecraft().getSession().getPlayerID();
        CheatBreaker cheatBreaker = CheatBreaker.getInstance();

        return cheatBreaker == null || cheatBreaker.getPlayerDataManager() == null
            ? playerId
            : cheatBreaker.getPlayerDataManager().formatUuid(playerId);
    }

    private static String fileStem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }

    private static File stateFile() {
        CheatBreaker cheatBreaker = CheatBreaker.getInstance();
        String branch = cheatBreaker == null || cheatBreaker.getBranch() == null ? "master" : cheatBreaker.getBranch();
        File directory = new File(
            Minecraft.getMinecraft().mcDataDir,
            "config" + File.separator + "cheatbreaker-client-" + ClientVersion.version + "-" + branch
        );

        return new File(directory, STATE_FILE);
    }

    private static void loadState() {
        File file = stateFile();
        if (!file.isFile()) {
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement equipped = json.get(EQUIPPED_KEY);

            if (equipped != null && equipped.isJsonArray()) {
                JsonArray array = equipped.getAsJsonArray();

                for (JsonElement element : array) {
                    String key = element.getAsString();
                    if (key != null && !key.isEmpty()) {
                        equippedKeys.add(key);
                    }
                }
            }

            Logging.debugTagged("Cosmetics", "Loaded %d equipped cosmetics from %s", equippedKeys.size(), file);
        } catch (Exception e) {
            Logging.warnTagged("Cosmetics", "Could not read %s: %s", file, e);
        }
    }

    private static void writeState() {
        File file = stateFile();

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Logging.warnTagged("Cosmetics", "Could not create directory %s", parent);
                return;
            }

            JsonArray array = new JsonArray();
            for (String key : equippedKeys) {
                array.add(key);
            }

            JsonObject json = new JsonObject();
            json.add(EQUIPPED_KEY, array);

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(new GsonBuilder().setPrettyPrinting().create().toJson(json));
            }
        } catch (IOException e) {
            Logging.errorTagged("Cosmetics", "Could not save %s: %s", file, e);
        }
    }
}
