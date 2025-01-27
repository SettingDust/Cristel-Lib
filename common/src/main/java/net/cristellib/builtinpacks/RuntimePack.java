package net.cristellib.builtinpacks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.cristellib.CristelLib;
import net.cristellib.config.ConfigUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FeatureFlagsMetadataSection;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.flag.FeatureFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class RuntimePack implements PackResources {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Lock waiting = new ReentrantLock();
    private final Map<ResourceLocation, Supplier<byte[]>> data = new ConcurrentHashMap<>();
    private final Map<List<String>, Supplier<byte[]>> root = new ConcurrentHashMap<>();
    public final int packVersion;
    private final String name;


    public RuntimePack(String name, int version, @Nullable Path imageFile) {
        this.packVersion = version;
        this.name = name;
        if(imageFile != null){
            byte[] image = extractImageBytes(imageFile);
            if(image != null) this.addRootResource("pack.png", image);
        }
    }


    public byte[] addStructureSet(ResourceLocation identifier, JsonObject set) {
        return this.addDataForJsonLocation("worldgen/structure_set", identifier, set);
    }


    public byte[] addBiome(ResourceLocation identifier, JsonObject biome) {
        return this.addDataForJsonLocation("worldgen/biome", identifier, biome);
    }
    public byte[] addStructure(ResourceLocation identifier, JsonObject structure) {
        return this.addDataForJsonLocation("worldgen/structure", identifier, structure);
    }
    public byte[] addLootTable(ResourceLocation identifier, JsonObject table) {
        return this.addDataForJsonLocation("loot_tables", identifier, table);
    }

    public byte @Nullable [] addDataForJsonLocationFromPath(String prefix, ResourceLocation identifier, String fromSubPath, String fromModID) {
        if(ConfigUtil.getElement(fromModID, fromSubPath) instanceof JsonObject object){
            return addDataForJsonLocation(prefix, identifier, object);
        }
        return null;
    }

    public byte[] addDataForJsonLocation(String prefix, ResourceLocation identifier, JsonObject object) {
        return this.addAndSerializeDataForLocation(prefix, "json", identifier, object);
    }
    public byte[] addAndSerializeDataForLocation(String prefix, String end, ResourceLocation identifier, JsonObject object) {
        return this.addData(new ResourceLocation(identifier.getNamespace(), prefix + '/' + identifier.getPath() + '.' + end), serializeJson(object));
    }
    public byte[] addData(ResourceLocation path, byte[] data) {
        this.data.put(path, () -> data);
        return data;
    }

    public void removeData(ResourceLocation path) {
        this.data.remove(path);
    }

    public static byte @Nullable [] extractImageBytes(Path imageName) {
        InputStream stream;
        //BufferedImage bufferedImage;
        try {
            stream = Files.newInputStream(imageName.toAbsolutePath());
            return stream.readAllBytes();
            /*
            bufferedImage = ImageIO.read(stream);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "png", baos);
            return baos.toByteArray();

             */
        } catch (IOException e) {
            CristelLib.LOGGER.warn("Couldn't get image for path: " + imageName, e);
            return null;
        }
    }


    public static byte[] serializeJson(JsonObject object) {
        UnsafeByteArrayOutputStream ubaos = new UnsafeByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(ubaos, StandardCharsets.UTF_8);
        GSON.toJson(object, writer);
        try {
            writer.close();
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
        return ubaos.getBytes();
    }

    public byte[] addRootResource(String path, byte[] data) {
        this.root.put(Arrays.asList(path.split("/")), () -> data);
        return data;
    }


    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String @NotNull ... strings) {
        this.lock();
        Supplier<byte[]> supplier = this.root.get(Arrays.asList(strings));
        if(supplier == null) {
            this.waiting.unlock();
            return null;
        }
        this.waiting.unlock();
        return () -> new ByteArrayInputStream(supplier.get());
    }

    private void lock() {
        if(!this.waiting.tryLock()) {
            this.waiting.lock();
        }
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(@NotNull PackType packType, @NotNull ResourceLocation id) {
        this.lock();
        Supplier<byte[]> supplier = this.data.get(id);
        if(supplier == null) {
            this.waiting.unlock();
            return null;
        }
        this.waiting.unlock();
        return () -> new ByteArrayInputStream(supplier.get());
    }


    @Override
    public void listResources(@NotNull PackType packType, @NotNull String namespace, @NotNull String prefix, @NotNull ResourceOutput resourceOutput) {
        this.lock();
        for(ResourceLocation identifier : this.data.keySet()) {
            Supplier<byte[]> supplier = this.data.get(identifier);
            if(supplier == null) {
                this.waiting.unlock();
                continue;
            }

            if(identifier.getNamespace().equals(namespace) && identifier.getPath().contains(prefix + "/")) {
                /*
                List<String> identifierHere = Arrays.stream(identifier.getPath().split("/")).toList();
                List<String> identifierThere = Arrays.stream(prefix.split("/")).toList();
                if(new HashSet<>(identifierHere).containsAll(identifierThere)) {
                 */
                IoSupplier<InputStream> inputSupplier = () -> new ByteArrayInputStream(supplier.get());
                resourceOutput.accept(identifier, inputSupplier);

            }
        }
        this.waiting.unlock();
    }

    @Override
    public Set<String> getNamespaces(@NotNull PackType packType) {
        this.lock();
        Set<String> namespaces = new HashSet<>();
        for(ResourceLocation identifier : this.data.keySet()) {
            namespaces.add(identifier.getNamespace());
        }
        this.waiting.unlock();
        return namespaces;
    }


    @Nullable
    @Override
    public <T> T getMetadataSection(@NotNull MetadataSectionSerializer<T> metadataSectionSerializer) {
        InputStream stream = null;
        try {
            IoSupplier<InputStream> supplier = this.getRootResource("pack.mcmeta");
            if (supplier != null) {
                stream = supplier.get();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if(stream != null) {
            return FilePackResources.getMetadataFromStream(metadataSectionSerializer, stream);
        } else {
            if(metadataSectionSerializer.getMetadataSectionName().equals("pack")) {
                JsonObject object = new JsonObject();
                object.addProperty("pack_format", this.packVersion);
                object.addProperty("description", this.name);
                return metadataSectionSerializer.fromJson(object);
            }
            else if(metadataSectionSerializer.getMetadataSectionName().equals("features")){
                return metadataSectionSerializer.fromJson(FeatureFlagsMetadataSection.TYPE.toJson(new FeatureFlagsMetadataSection(FeatureFlags.DEFAULT_FLAGS)));
            }
            CristelLib.LOGGER.debug("'" + metadataSectionSerializer.getMetadataSectionName() + "' is an unsupported metadata key");
            return null;
        }
    }

    public boolean hasResource(ResourceLocation location){
        return data.containsKey(location);
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public String packId() {
        return this.name;
    }

    @Override
    public void close() {
        CristelLib.LOGGER.debug("Closing RDP: " + this.name);
    }

    public void load(Path dir) throws IOException {
        Stream<Path> stream = Files.walk(dir);
        for(Path file : (Iterable<Path>) () -> stream.filter(Files::isRegularFile).map(dir::relativize).iterator()) {
            String s = file.toString();
            if(s.startsWith("data")) {
                String path = s.substring("data".length() + 1);
                this.load(path, this.data, Files.readAllBytes(file));
            } else if(!s.startsWith("assets")) {
                byte[] data = Files.readAllBytes(file);
                this.root.put(Arrays.asList(s.split("/")), () -> data);
            }
        }
    }


    public void load(ZipInputStream stream) throws IOException {
        ZipEntry entry;
        while((entry = stream.getNextEntry()) != null) {
            String s = entry.toString();
            if(s.startsWith("data")) {
                String path = s.substring("data".length() + 1);
                this.load(path, this.data, this.read(entry, stream));
            } else if(!s.startsWith("assets")){
                byte[] data = this.read(entry, stream);
                this.root.put(Arrays.asList(s.split("/")), () -> data);
            }
        }
    }

    protected byte[] read(ZipEntry entry, InputStream stream) throws IOException {
        byte[] data = new byte[Math.toIntExact(entry.getSize())];
        if(stream.read(data) != data.length) {
            throw new IOException("Zip stream was cut off! (maybe incorrect zip entry length? maybe u didn't flush your stream?)");
        }
        return data;
    }

    protected void load(String fullPath, Map<ResourceLocation, Supplier<byte[]>> map, byte[] data) {
        int sep = fullPath.indexOf('/');
        String namespace = fullPath.substring(0, sep);
        String path = fullPath.substring(sep + 1);
        map.put(new ResourceLocation(namespace, path), () -> data);
    }


    public @Nullable JsonObject getResource(ResourceLocation location){
        IoSupplier<InputStream> stream = this.getResource(PackType.SERVER_DATA, location);
        JsonObject jsonObject;
        try {
            jsonObject = GsonHelper.parse(new BufferedReader(new InputStreamReader(stream.get())));
        } catch (IOException | NullPointerException ex) {
            CristelLib.LOGGER.error("Couldn't get JsonObject from location: " + location, ex);
            return null;
        }
        return jsonObject;
    }
}
