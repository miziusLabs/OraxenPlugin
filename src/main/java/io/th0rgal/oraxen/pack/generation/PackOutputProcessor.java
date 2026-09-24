package io.th0rgal.oraxen.pack.generation;

import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.utils.VirtualFile;

import java.util.*;

/** Validates and combines generated assets before archive writing. */
final class PackOutputProcessor {
    private final SoundGenerator soundGenerator;

    PackOutputProcessor(SoundGenerator soundGenerator) {
        this.soundGenerator = soundGenerator;
    }

    /**
     * Post-processes the output: verifies textures, generates atlases,
     * merges duplicates, filters excluded extensions, and generates sounds.
     */
    void process(List<VirtualFile> output, boolean multiVersionResolved) {
        processAsyncSafe(output, multiVersionResolved);
        soundGenerator.generateSound(output);
    }

    void processAsyncSafe(List<VirtualFile> output, boolean multiVersionResolved) {
        Set<String> malformedTextures = new HashSet<>();
        if (Settings.VERIFY_PACK_FILES.toBool())
            malformedTextures = PackFileCollector.verifyPackFormatting(output);

        if (Settings.GENERATE_ATLAS_FILE.toBool())
            AtlasGenerator.generateAtlasFile(output, malformedTextures);

        if (Settings.MERGE_DUPLICATE_FONTS.toBool())
            DuplicationHandler.mergeFontFiles(output);
        if (Settings.MERGE_ITEM_MODELS.toBool())
            DuplicationHandler.mergeBaseItemFiles(output);
        DuplicationHandler.mergeVanillaItemDefinitions(output, multiVersionResolved);

        List<String> excludedExtensions = Settings.EXCLUDED_FILE_EXTENSIONS.toStringList();
        excludedExtensions.removeIf(f -> f.equals("png") || f.equals("json"));
        if (!excludedExtensions.isEmpty() && !output.isEmpty()) {
            List<VirtualFile> excluded = new ArrayList<>();
            for (VirtualFile virtual : output)
                for (String extension : excludedExtensions)
                    if (virtual.getPath().endsWith(extension))
                        excluded.add(virtual);
            output.removeAll(excluded);
        }
    }

}
