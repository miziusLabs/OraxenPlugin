package io.th0rgal.oraxen.pack.generation;

import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.logs.Logs;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs pack generation through explicitly ordered worker and server-thread stages.
 * Bukkit-facing generation and events stay on the server scheduler; file IO stays
 * on the dedicated worker. A generation cannot overlap another generation.
 */
final class PackGenerationPipeline {
    private final ResourcePack resourcePack;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean shutdownRequested;
    private volatile ExecutorService worker;

    PackGenerationPipeline(ResourcePack resourcePack) {
        this.resourcePack = resourcePack;
    }

    boolean begin() {
        shutdownRequested = false;
        if (running.compareAndSet(false, true)) return true;
        Logs.logWarning("Resource-pack generation is already in progress, skipping duplicate request");
        return false;
    }

    void startSinglePack() {
        ExecutorService packWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Oraxen-PackWorker");
            thread.setDaemon(true);
            return thread;
        });
        worker = packWorker;
        submit(packWorker, "item asset generation", () -> {
            resourcePack.generateAsyncSafeItemAssets();
            onServerThread(() -> generateServerAssets(packWorker));
        });
    }

    void generateMultiVersion(boolean switchingFromSinglePack) {
        try {
            List<VirtualFile> output = resourcePack.prepareAndGenerateBaseAssets();
            if (output.isEmpty()) return;
            MultiVersionPackGenerator generator = new MultiVersionPackGenerator(
                    resourcePack.getPackFolder(), resourcePack.generatedShaderHashes());
            generator.generateMultipleVersions(output, switchingFromSinglePack);
        } finally {
            finish();
        }
    }

    private void generateServerAssets(ExecutorService packWorker) {
        runServerStage(packWorker, "asset generation", () -> {
            resourcePack.generateMiscAssets();
            resourcePack.applyPackModifiers();
            List<VirtualFile> output = resourcePack.snapshotOutput();
            submit(packWorker, "pack collection", () -> {
                resourcePack.collectPackFilesAsyncSafe(output);
                onServerThread(() -> finishCollection(packWorker, output));
            });
        });
    }

    private void finishCollection(ExecutorService packWorker, List<VirtualFile> output) {
        runServerStage(packWorker, "custom armor generation", () -> {
            resourcePack.finishCollection(output);
            submit(packWorker, "pack post-processing", () -> {
                resourcePack.processOutputAsyncSafe(output);
                onServerThread(() -> finishOutput(packWorker, output));
            });
        });
    }

    private void finishOutput(ExecutorService packWorker, List<VirtualFile> output) {
        runServerStage(packWorker, "pack finalization", () -> {
            List<VirtualFile> finalOutput = resourcePack.finishOutput(output);
            submit(packWorker, "pack writing", () -> {
                resourcePack.archiveWriter().write(finalOutput, () -> shutdownRequested);
                if (shutdownRequested) return;
                onServerThread(this::uploadAndFinish);
                packWorker.shutdown();
            });
        });
    }

    private void uploadAndFinish() {
        try {
            if (!shutdownRequested) resourcePack.uploadGeneratedPack();
        } finally {
            finish();
        }
    }

    private void onServerThread(Runnable stage) {
        if (!shutdownRequested) SchedulerUtil.runTask(stage);
    }

    private void runServerStage(ExecutorService packWorker, String name, Runnable stage) {
        if (shutdownRequested) {
            finish();
            return;
        }
        try {
            stage.run();
        } catch (Exception exception) {
            fail(packWorker, name, exception);
        }
    }

    private void submit(ExecutorService packWorker, String name, ThrowingStage stage) {
        try {
            packWorker.submit(() -> {
                try {
                    if (!shutdownRequested) stage.run();
                } catch (Exception exception) {
                    fail(packWorker, name, exception);
                }
            });
        } catch (RuntimeException exception) {
            fail(packWorker, name + " scheduling", exception);
        }
    }

    private void fail(ExecutorService packWorker, String stage, Exception exception) {
        Logs.logError("Failed during " + stage);
        exception.printStackTrace();
        packWorker.shutdown();
        finish();
    }

    void finish() {
        running.set(false);
        ExecutorService packWorker = worker;
        if (packWorker != null && packWorker.isShutdown()) worker = null;
    }

    void shutdown() {
        shutdownRequested = true;
        ExecutorService packWorker = worker;
        if (packWorker != null) {
            packWorker.shutdownNow();
            worker = null;
        }
        finish();
    }

    @FunctionalInterface
    private interface ThrowingStage {
        void run() throws Exception;
    }
}
