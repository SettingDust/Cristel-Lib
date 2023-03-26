package net.cristellib;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import net.cristellib.api.builtinpacks.BuiltInDataPacks;
import net.cristellib.api.builtinpacks.RuntimePack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.GsonHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Path;
import java.util.Set;

public class CristelLib {
    public static final String MOD_ID = "cristellib";

    public static final Logger LOGGER = LogManager.getLogger("Cristel Lib");

    public static RuntimePack DATA_PACK = new RuntimePack("Runtime Pack", 10, CristelLibExpectPlatform.getResourceDirectory(MOD_ID, "assets/cristellib/textures/icon.png"));

    private static final CristelLibRegistry REGISTRY = new CristelLibRegistry();

    public static void init() {
        BuiltInDataPacks.registerPack(DATA_PACK, Component.literal("Cristel Lib Config Pack"), () -> true);

        CristelLibRegistry.configs = ImmutableMap.copyOf(CristelLibExpectPlatform.getConfigs(REGISTRY));

        for(Set<StructureConfig> pack : CristelLibRegistry.configs.values()){
            for(StructureConfig config : pack){
                config.writeConfig();
                config.addSetsToRuntimePack();
            }
        }

    }


    public static void writeStreamToFile(String filename, ResourceLocation from, PackResources packResources){
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        IoSupplier<InputStream> stream = packResources.getResource(PackType.SERVER_DATA, from);
        if(stream == null){
            LOGGER.error("null stream");
            return;
        }
        JsonObject jsonObject;
        try {
            jsonObject = GsonHelper.parse(new BufferedReader(new InputStreamReader(stream.get())));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        Path path = CristelLibExpectPlatform.getConfigDirectory().resolve(filename + ".json");
        try (FileWriter fileWriter = new FileWriter(path.toFile()); JsonWriter jsonWriter = gson.newJsonWriter(fileWriter)) {
            jsonWriter.jsonValue(gson.toJson(jsonObject));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
