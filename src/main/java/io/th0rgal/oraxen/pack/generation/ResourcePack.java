package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonObject;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.api.events.OraxenPackGeneratedEvent;
import io.th0rgal.oraxen.configs.AppearanceMode;
import io.th0rgal.oraxen.configs.ResourcesManager;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.items.OraxenMeta;
import io.th0rgal.oraxen.pack.upload.UploadManager;
import io.th0rgal.oraxen.utils.*;
import io.th0rgal.oraxen.utils.customarmor.ComponentArmorModels;
import io.th0rgal.oraxen.utils.customarmor.CustomArmorType;
import io.th0rgal.oraxen.utils.customarmor.TrimArmorDatapack;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Material;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;

public class ResourcePack {

    private final Map<String, Collection<Consumer<File>>> packModifiers;
    private static Map<String, VirtualFile> outputFiles;
    private TrimArmorDatapack trimArmorDatapack;
    private ComponentArmorModels componentArmorModels;
    private static final File packFolder = new File(OraxenPlugin.get().getDataFolder(), "pack");
    private static final Set<String> LEGACY_SHADER_OVERLAY_DIRECTORIES = Set.of("overlay_1_21_6_plus", "overlay_1_21_4_5");
    private final File pack = new File(packFolder, packFolder.getName() + ".zip");
    private final SoundGenerator soundGenerator = new SoundGenerator();
    private final PackOutputProcessor outputProcessor = new PackOutputProcessor(soundGenerator);
    private final PackFileAssembler fileAssembler = new PackFileAssembler(packFolder);
    private final TextShaderGenerator textShaderGenerator = new TextShaderGenerator();
    private final PackMetadataGenerator metadataGenerator = new PackMetadataGenerator(packFolder, textShaderGenerator);
    private final FontAssetGenerator fontAssetGenerator = new FontAssetGenerator(packFolder, textShaderGenerator);
    private final ItemAssetGenerator itemAssetGenerator = new ItemAssetGenerator();
    private final PackArchiveWriter archiveWriter = new PackArchiveWriter(packFolder, pack, textShaderGenerator);
    /** Resolved multi-version flag (may differ from Settings if fallback occurred). */
    private boolean multiVersionResolved = false;
    private final PackGenerationPipeline pipeline = new PackGenerationPipeline(this);

    public ResourcePack() {
        // we use maps to avoid duplicate
        packModifiers = new HashMap<>();
        outputFiles = new java.util.concurrent.ConcurrentHashMap<>();
    }

    public void generate() {
        if (!pipeline.begin()) return;

        // Re-evaluate dispatch mode normalization on each generation (covers reload)
        io.th0rgal.oraxen.pack.dispatch.PackSender.resetDispatchNormalization();

        boolean multiVersionEnabled = Settings.MULTI_VERSION_PACKS.toBool();
        boolean isSelfHost = Settings.UPLOAD_TYPE.toString().equalsIgnoreCase("self-host");

        try {
            if (multiVersionEnabled) {
                if (isSelfHost) {
                    Logs.logError("Multi-version packs are incompatible with self-host upload!");
                    Logs.logError("SelfHost can only serve one file at /pack.zip");
                    Logs.logError("Falling back to single-pack mode for this generation");
                    multiVersionEnabled = false;
                } else if (!Settings.UPLOAD.toBool()) {
                    Logs.logWarning("Multi-version packs require upload to be enabled!");
                    Logs.logWarning("Falling back to single-pack mode for this generation");
                    multiVersionEnabled = false;
                }
            }

            this.multiVersionResolved = multiVersionEnabled;

            if (multiVersionEnabled) {
                // Detect mode-switch BEFORE nulling the old manager, so generateMultiVersion
                // knows this is a reload even though both managers will be null by then.
                boolean switchingFromSinglePack = OraxenPlugin.get().getUploadManager() != null;

                // Unregister and clear single-pack manager if switching from single-pack mode.
                // Clearing the reference prevents getPackURL()/getPackSHA1() from returning
                // stale data from the old mode's manager. setUploadManager(null) unregisters
                // the old manager, so a separate unregister() call is not needed.
                OraxenPlugin.get().setUploadManager(null);

                pipeline.generateMultiVersion(switchingFromSinglePack);
                return;
            }

            // Unregister and clear multi-version manager if switching from multi-version mode.
            // Without clearing, OraxenPlugin.getPackURL()/getPackSHA1() would still check
            // the stale multiVersionUploadManager first and return wrong pack data.
            // setMultiVersionUploadManager(null) internally calls unregister() on the
            // old manager, so a separate unregister() call is not needed.
            OraxenPlugin.get().setMultiVersionUploadManager(null);

            if (!prepareGenerationPreamble()) {
                pipeline.finish();
                return;
            }

            pipeline.startSinglePack();
        } catch (RuntimeException exception) {
            pipeline.finish();
            throw exception;
        }
    }

    void processOutputAsyncSafe(List<VirtualFile> output) {
        outputProcessor.processAsyncSafe(output, multiVersionResolved);
    }

    PackArchiveWriter archiveWriter() {
        return archiveWriter;
    }

    public void shutdown() {
        pipeline.shutdown();
    }

    List<VirtualFile> snapshotOutput() {
        return new ArrayList<>(outputFiles.values());
    }

    void finishCollection(List<VirtualFile> output) {
        handleCustomArmor(output);
        applyArmorStandModelOverrides(output);
        Collections.sort(output);
    }

    List<VirtualFile> finishOutput(List<VirtualFile> output) {
        soundGenerator.generateSound(output);
        OraxenPackGeneratedEvent event = new OraxenPackGeneratedEvent(output);
        event.callEvent();
        return event.getOutput();
    }

    void uploadGeneratedPack() {
        UploadManager uploadManager = OraxenPlugin.get().getUploadManager();
        if (uploadManager != null) {
            uploadManager.uploadAsyncAndSendToPlayers(this, true, true);
        } else {
            uploadManager = new UploadManager(OraxenPlugin.get());
            OraxenPlugin.get().setUploadManager(uploadManager);
            uploadManager.uploadAsyncAndSendToPlayers(this, false, false);
        }
    }

    /**
     * Prepares the environment and generates all base pack assets.
     * This is shared logic between single-pack and multi-version generation.
     *
     * @return List of generated VirtualFiles ready for zipping
     */
    private boolean prepareGenerationPreamble() {
        // Reset state
        outputFiles.clear();
        textShaderGenerator.reset();

        // Snapshot which default folders are missing before any directories get created,
        // so extraction only ever targets folders the user has not provided themselves.
        Set<String> missingDefaultFolders = missingDefaultFolders();

        makeDirsIfNotExists(packFolder, new File(packFolder, "assets"));
        cleanLegacyShaderOverlayDirectories();

        componentArmorModels = CustomArmorType.getSetting() == CustomArmorType.COMPONENT ? new ComponentArmorModels()
                : null;
        trimArmorDatapack = CustomArmorType.getSetting() == CustomArmorType.TRIMS ? new TrimArmorDatapack() : null;
        fileAssembler.reset();

        if (Settings.GENERATE_DEFAULT_ASSETS.toBool())
            extractDefaultFolders(missingDefaultFolders);
        extractRequired();

        if (!Settings.GENERATE.toBool())
            return false;

        if (Settings.HIDE_SCOREBOARD_NUMBERS.toBool() && PluginUtils.isEnabled("HappyHUD")) {
            Logs.logError("HappyHUD detected with hide_scoreboard_numbers enabled!");
            Logs.logWarning(
                    "Recommend following this guide for compatibility: https://docs.oraxen.com/compatibility/happyhud");
        }

        try {
            Files.deleteIfExists(pack.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!metadataGenerator.isMcmetaGenerationDisabled()) {
            extractInPackIfNotExists(new File(packFolder, "pack.mcmeta"));
        }
        extractInPackIfNotExists(new File(packFolder, "pack.png"));
        metadataGenerator.updatePackMcmeta();

        return true;
    }

    private void cleanLegacyShaderOverlayDirectories() {
        for (String directory : LEGACY_SHADER_OVERLAY_DIRECTORIES) {
            File legacyOverlayDirectory = new File(packFolder, directory);
            if (!legacyOverlayDirectory.isDirectory()) continue;

            try {
                FileUtils.deleteDirectory(legacyOverlayDirectory);
                if (Settings.DEBUG.toBool()) {
                    Logs.logInfo("Removed legacy shader overlay directory: " + directory);
                }
            } catch (IOException e) {
                Logs.logWarning("Failed to remove legacy shader overlay directory " + directory + ": " + e.getMessage());
            }
        }
    }

    /**
     * Prepares the environment and generates all base pack assets.
     * This is shared logic between single-pack and multi-version generation.
     *
     * @return List of generated VirtualFiles ready for zipping
     */
    List<VirtualFile> prepareAndGenerateBaseAssets() {
        if (!prepareGenerationPreamble()) {
            return new ArrayList<>();
        }

        generateAsyncSafeItemAssets();
        return generateBaseAssets();
    }

    /**
     * Generates all base pack assets (items, fonts, shaders, blocks, etc.).
     * This is the core generation logic shared between single-pack and multi-version.
     *
     * @return List of generated VirtualFiles
     */
    private List<VirtualFile> generateBaseAssets() {
        generateMiscAssets();
        applyPackModifiers();

        List<VirtualFile> output = new ArrayList<>(outputFiles.values());
        collectPackFiles(output);
        applyArmorStandModelOverrides(output);
        outputProcessor.process(output, multiVersionResolved);

        return output;
    }

    private void applyArmorStandModelOverrides(List<VirtualFile> output) {
        Map<String, org.bukkit.util.Vector> scaleByModelPath = new LinkedHashMap<>();

        for (Map.Entry<String, ItemBuilder> entry : OraxenItems.getEntries()) {
            ItemBuilder item = entry.getValue();
            if (!item.hasOraxenMeta()) continue;

            OraxenMeta meta = item.getOraxenMeta();
            if (!meta.hasPackInfos() || !meta.hasArmorStandHeadScale()) continue;

            String modelFilePath = meta.getModelPath() + "/" + meta.getModelName() + ".json";
            org.bukkit.util.Vector newScale = meta.getArmorStandHeadScale();
            org.bukkit.util.Vector existingScale = scaleByModelPath.get(modelFilePath);

            if (existingScale != null && !sameScale(existingScale, newScale)) {
                Logs.logWarning("Multiple armor-stand furniture items target the same model with different scale values: " + modelFilePath);
                Logs.logWarning("Using the latest configured scale from item <gold>" + entry.getKey(), true);
            }

            scaleByModelPath.put(modelFilePath, newScale);
        }

        if (scaleByModelPath.isEmpty()) return;

        for (VirtualFile virtualFile : output) {
            org.bukkit.util.Vector scale = scaleByModelPath.get(virtualFile.getPath());
            if (scale == null) continue;

            JsonObject modelJson = virtualFile.toJsonObject();
            if (modelJson == null) continue;

            ModelGenerator.applyHeadScale(modelJson, scale);
            InputStream previousStream = virtualFile.getInputStream();
            if (previousStream != null) {
                try {
                    previousStream.close();
                } catch (IOException ignored) {
                }
            }
            virtualFile.setInputStream(new ByteArrayInputStream(modelJson.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private boolean sameScale(org.bukkit.util.Vector first, org.bukkit.util.Vector second) {
        return Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    void generateAsyncSafeItemAssets() {
        itemAssetGenerator.generate(multiVersionResolved);
    }

    /** Generates non-item assets: fonts, shaders, scoreboard tweaks, and armor. */
    void generateMiscAssets() {
        fontAssetGenerator.generate(multiVersionResolved);
        metadataGenerator.updatePackMcmetaOverlays();
        // No version guard here: hideScoreboardNumbers() dispatches per version
        // (packet listener on Paper 1.20.3+, shaders below, warning on 26.x+).
        if (Settings.HIDE_SCOREBOARD_NUMBERS.toBool())
            textShaderGenerator.hideScoreboardNumbers();
        textShaderGenerator.hideScoreboardOrTablistBackgrounds();
    }

    /** Runs registered pack modifier callbacks. */
    void applyPackModifiers() {
        for (final Collection<Consumer<File>> modifiers : packModifiers.values())
            for (Consumer<File> modifier : modifiers)
                modifier.accept(packFolder);
    }

    /**
     * Collects all pack files from the pack folder into the output list,
     * merges uploaded packs, converts lang files, and handles custom armor.
     */
    private void collectPackFiles(List<VirtualFile> output) {
        try {
            fileAssembler.collectAsyncSafe(output);
            handleCustomArmor(output);
            Collections.sort(output);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void collectPackFilesAsyncSafe(List<VirtualFile> output) throws IOException {
        fileAssembler.collectAsyncSafe(output);
    }

    /**
     * Generates multiple resource pack versions for different Minecraft client versions.
     * This method delegates to MultiVersionPackGenerator when multi_version_packs is enabled.
     *
     * @param switchingFromSinglePack true if we're switching from single-pack mode (treat as reload)
     */
    Map<String, String> generatedShaderHashes() {
        return textShaderGenerator.getGeneratedCoreShaderHashes();
    }

    private static final List<String> DEFAULT_PACK_FOLDERS = List.of(
            "assets", "models", "font", "lang", "textures", "sounds");

    /**
     * Determines which default pack folders are missing at generation time instead of
     * once at startup, so pack regeneration on reload never overwrites user-provided
     * files (e.g. pack/lang) in folders that were created after the plugin was enabled.
     */
    private Set<String> missingDefaultFolders() {
        Set<String> missingFolders = new HashSet<>();
        for (String folder : DEFAULT_PACK_FOLDERS)
            if (!new File(packFolder, folder).exists())
                missingFolders.add(folder);
        return missingFolders;
    }

    private void extractDefaultFolders(Set<String> foldersToExtract) {
        if (foldersToExtract.isEmpty())
            return;

        ResourcesManager.browseJar(entry ->
            extract(entry, OraxenPlugin.get().getResourceManager(), isSuitable(entry.getName(), foldersToExtract))
        );
    }

    private boolean isSuitable(String entryName, Set<String> foldersToExtract) {
        String name = StringUtils.substringAfter(entryName, "pack/").split("/")[0];
        return foldersToExtract.contains(name);
    }

    private void extractRequired() {
        ResourcesManager.browseJar(entry -> {
            if (entry.getName().startsWith("pack/textures/models/armor/leather_layer_")
                    || entry.getName().startsWith("pack/textures/required")
                    || entry.getName().startsWith("pack/models/required")) {
                OraxenPlugin.get().getResourceManager().extractFileIfTrue(entry,
                        !OraxenPlugin.get().getDataFolder().toPath().resolve(entry.getName()).toFile().exists());
            }
        });
    }

    private void extract(ZipEntry entry, ResourcesManager resourcesManager, boolean isSuitable) {
        final String name = entry.getName();
        resourcesManager.extractFileIfTrue(entry, isSuitable);
    }

    @SafeVarargs
    public final void addModifiers(String groupName, final Consumer<File>... modifiers) {
        packModifiers.compute(groupName, (key, existing) -> {
            List<Consumer<File>> merged = new ArrayList<>();
            if (existing != null) merged.addAll(existing);
            merged.addAll(Arrays.asList(modifiers));
            return merged;
        });
    }

    public static void addOutputFiles(final VirtualFile... files) {
        for (VirtualFile file : files)
            outputFiles.put(file.getPath(), file);
    }

    public File getFile() {
        return pack;
    }

    public File getPackFolder() {
        return packFolder;
    }

    private void extractInPackIfNotExists(final File file) {
        if (!file.exists())
            OraxenPlugin.get().saveResource("pack/" + file.getName(), true);
    }

    private void makeDirsIfNotExists(final File... folders) {
        for (final File folder : folders)
            if (!folder.exists())
                folder.mkdirs();
    }

    public static void writeStringToVirtual(String folder, String name, String content) {
        addOutputFiles(
                new VirtualFile(normalizeVirtualFolder(folder), name, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
    }

    public static String normalizeVirtualPath(String folder, String name) {
        String normalizedFolder = normalizeVirtualFolder(folder);
        String normalizedName = name == null ? "" : name.trim();
        while (normalizedName.startsWith("/")) normalizedName = normalizedName.substring(1);
        return normalizedFolder.isEmpty() ? normalizedName : normalizedFolder + "/" + normalizedName;
    }

    private static String normalizeVirtualFolder(String folder) {
        String normalizedFolder = folder == null ? "" : folder.trim();
        while (normalizedFolder.endsWith("/")) normalizedFolder = normalizedFolder.substring(0, normalizedFolder.length() - 1);
        while (normalizedFolder.startsWith("/")) normalizedFolder = normalizedFolder.substring(1);
        return normalizedFolder;
    }

    public static void deleteFileFromVirtualAndDisk(String folder, String name) {
        folder = !folder.endsWith("/") ? folder : folder.substring(0, folder.length() - 1);
        String virtualPath = folder.isEmpty() ? name : folder + "/" + name;
        outputFiles.remove(virtualPath);

        File file = new File(packFolder, virtualPath);
        if (file.exists()) {
            file.delete();
            if (Settings.DEBUG.toBool()) {
                Logs.logInfo("Deleted stale file from disk: " + virtualPath);
            }
        }
    }

    private void handleCustomArmor(List<VirtualFile> output) {
        CustomArmorType customArmorType = CustomArmorType.getSetting();

        switch (customArmorType) {
            case COMPONENT -> componentArmorModels.generatePackFiles(output);
            case TRIMS -> {
                if (trimArmorDatapack == null)
                    trimArmorDatapack = new TrimArmorDatapack();
                trimArmorDatapack.clearOldDataPack();
                trimArmorDatapack.generateAssets(output);
            }
            default -> {
            } // Handle NONE
        }
    }


}
