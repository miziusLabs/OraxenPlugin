package io.th0rgal.oraxen.pack.generation;

import io.th0rgal.oraxen.utils.VirtualFile;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Reads pack files and imports uploaded packs into the virtual file list. */
final class PackFileAssembler {
    private final File packFolder;
    private final GlobalLanguageConverter languageConverter;
    private PackFileCollector fileCollector;

    PackFileAssembler(File packFolder) {
        this.packFolder = packFolder;
        this.languageConverter = new GlobalLanguageConverter(packFolder);
    }

    void reset() {
        fileCollector = new PackFileCollector(packFolder);
    }

    void collectAsyncSafe(List<VirtualFile> output) throws IOException {
        fileCollector.getFilesInFolder(packFolder, output, packFolder.getCanonicalPath(), packFolder.getName() + ".zip");

        File[] files = packFolder.listFiles();
        if (files != null)
            for (final File folder : files) {
                if (!folder.isDirectory()) continue;
                if (folder.getName().equals("uploads") || folder.getName().equals("__MACOSX")) continue;
                fileCollector.getAllFiles(folder, output,
                        folder.getName().matches("models|textures|lang|font|sounds") ? "assets/minecraft" : "");
            }

        mergeUploadedPacks(output);
        languageConverter.convert(output);
    }

    private void mergeUploadedPacks(List<VirtualFile> output) {
        PackMerger packMerger = new PackMerger(packFolder);
        List<VirtualFile> mergedFiles = packMerger.mergeUploadedPacks();
        PackMcmetaUtils.mergeOverlayEntriesIntoOutput(output, packMerger.getMergedOverlayEntries());

        if (!mergedFiles.isEmpty()) {
            output.addAll(mergedFiles);
        }
    }

}
