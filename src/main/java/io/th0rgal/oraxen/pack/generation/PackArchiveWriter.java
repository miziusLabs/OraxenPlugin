package io.th0rgal.oraxen.pack.generation;

import io.th0rgal.oraxen.utils.HashUtils;
import io.th0rgal.oraxen.utils.MinecraftVersion;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.ZipUtils;
import io.th0rgal.oraxen.utils.logs.Logs;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Writes the single-version archive after applying configured output transforms. */
final class PackArchiveWriter {
    private final File packFolder;
    private final File pack;
    private final TextShaderGenerator textShaderGenerator;

    PackArchiveWriter(File packFolder, File pack, TextShaderGenerator textShaderGenerator) {
        this.packFolder = packFolder;
        this.pack = pack;
        this.textShaderGenerator = textShaderGenerator;
    }

    void write(List<VirtualFile> output, BooleanSupplier cancelled) throws IOException {
        filterGeneratedCoreShadersBelow1214(output, MinecraftVersion.getCurrentVersion());
        UnprotectedPackWriter.writeConfigured(output, packFolder);
        PackObfuscator.obfuscate(output);
        if (cancelled.getAsBoolean()) return;
        ZipUtils.writeZipFile(pack, output);
    }

    private void filterGeneratedCoreShadersBelow1214(List<VirtualFile> output, MinecraftVersion targetVersion) {
        if (targetVersion.isAtLeast(new MinecraftVersion("1.21.4"))) {
            return;
        }

        Map<String, String> generatedShaderHashes = textShaderGenerator.getGeneratedCoreShaderHashes();
        if (generatedShaderHashes.isEmpty()) {
            return;
        }

        output.removeIf(file -> generatedShaderHashes.containsKey(file.getPath())
                && generatedShaderHashes.get(file.getPath()).equals(sha256(file)));
    }

    private String sha256(VirtualFile file) {
        try {
            byte[] content = file.getInputStream().readAllBytes();
            file.setInputStream(new ByteArrayInputStream(content));
            return HashUtils.sha256(content);
        } catch (IOException | IllegalStateException e) {
            Logs.logWarning("Failed to hash " + file.getPath() + " while filtering generated shaders: " + e.getMessage());
            return "";
        }
    }

    /**
     * Ensures {@code pack/pack.mcmeta} always has the correct {@code pack_format}
     * for the running server version.
     *
     * <p>
     * We can't rely on {@link #extractInPackIfNotExists(File)} because users often
     * keep their pack folder
     * across updates, and the embedded template may be outdated for newer Minecraft
     * versions.
     * </p>
     *
     * <p>
     * Also adds overlay entries for cross-version shader compatibility when the server
     * is running 1.21.4+ and animated glyphs may be used.
     * </p>
     */
}
