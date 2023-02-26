package pl.skidam.automodpack.client;

import pl.skidam.automodpack.Download;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.utils.*;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pl.skidam.automodpack.StaticVariables.*;
import static pl.skidam.automodpack.config.ConfigTools.GSON;
import static pl.skidam.automodpack.utils.RefactorStrings.getETA;

public class ModpackUpdater {
    public static List<DownloadInfo> downloadInfos = new ArrayList<>();
    public static final int MAX_DOWNLOADS = 5; // at the same time
    public static List<CompletableFuture<Void>> downloadFutures = new ArrayList<>();
    public static Map<String, Boolean> changelogList = new HashMap<>(); // <file, true - downloaded, false - deleted>
    private static ExecutorService DOWNLOAD_EXECUTOR;
    public static boolean update;
    public static long totalBytesDownloaded = 0;
    public static long totalBytesToDownload = 0;
    private static int alreadyDownloaded = 0;
    private static int wholeQueue = 0;
    private static Config.ModpackContentFields serverModpackContent;
    public static Map<String, String> failedDownloads = new HashMap<>(); // <file, url>

    public static String getStage() {
        return alreadyDownloaded + "/" + wholeQueue;
    }

    public static int getTotalPercentageOfFileSizeDownloaded() {
        return (int) ((double) totalBytesDownloaded / (double) totalBytesToDownload * 100);
    }

    public static double getTotalDownloadSpeed() {
        double totalSpeed = 0;
        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);
        for (DownloadInfo downloadInfo : downloadInfosCopy) {
            if (downloadInfo != null) {
                totalSpeed += downloadInfo.getDownloadSpeed();
            }
        }
        if (totalSpeed <= 0) {
            return 0;
        }
        return Math.round(totalSpeed * 10.0) / 10.0;
    }

    public static String getTotalETA() {
        double totalBytesPerSecond = 0;

        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);
        for (DownloadInfo downloadInfo : downloadInfosCopy) { // this is done like that to avoid ConcurrentModificationException
            if (downloadInfo == null) continue;
            totalBytesPerSecond += downloadInfo.getBytesPerSecond();
        }

        if (totalBytesPerSecond <= 0) return "N/A";

        double totalETA = (totalBytesToDownload - totalBytesDownloaded) / totalBytesPerSecond;

        return getETA(totalETA);
    }

    public static Config.ModpackContentFields getServerModpackContent(String link) {
        try {
            HttpRequest getContent = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(3))
                    .setHeader("Minecraft-Username", MinecraftUserName.get())
                    .setHeader("User-Agent", "github/skidamek/automodpack/" + VERSION)
                    .uri(new URI(link))
                    .build();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<String> contentResponse = httpClient.send(getContent, HttpResponse.BodyHandlers.ofString());
            Config.ModpackContentFields serverModpackContent = GSON.fromJson(contentResponse.body(), Config.ModpackContentFields.class);

            if (serverModpackContent.list.size() < 1) {
                LOGGER.error("Modpack content is empty!");
                return null;
            }
            // check if modpackContent is valid/isn't malicious
            for (Config.ModpackContentFields.ModpackContentItems modpackContentItem : serverModpackContent.list) {
                String file = modpackContentItem.file.replace("\\", "/");
                String url = modpackContentItem.link.replace("\\", "/");
                if (file.contains("/../") || url.contains("/../")) {
                    LOGGER.error("Modpack content is invalid, it contains /../ in file name or url");
                    return null;
                }
            }

            return serverModpackContent;
        } catch (ConnectException e) {
            LOGGER.error("Couldn't connect to modpack server " + link);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content");
            e.printStackTrace();
        }
        return null;
    }

    public ModpackUpdater(String link, File modpackDir, boolean loadIfItsNotLoaded) {
        if (link == null || link.isEmpty() || modpackDir.toString() == null || modpackDir.toString().isEmpty()) return;

        try {
            DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(MAX_DOWNLOADS);

            serverModpackContent = getServerModpackContent(link);

            if (serverModpackContent == null)  { // server is down, or you don't have access to internet, but we still want to load selected modpack

                if (!loadIfItsNotLoaded) return;

                File modpackContentFile = new File(modpackDir + File.separator + "modpack-content.json");

                if (!modpackContentFile.exists()) return;

                Config.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);
                if (modpackContent == null) return;

                if (!ModpackUtils.isLoaded(modpackContent)) {
                    LOGGER.info("Modpack is not loaded, loading...");
                    // copy files to running directory
                    ModpackUtils.copyModpackFiles(modpackDir, serverModpackContent);
                    checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");
                    new ReLauncher.Restart(modpackDir);
                }

                return;
            }

            serverModpackContent.link = link;

            if (!modpackDir.exists()) modpackDir.mkdirs();

            File modpackContentFile = new File(modpackDir + File.separator + "modpack-content.json");

            if (modpackContentFile.exists()) {
                if (ModpackUtils.isUpdate(link, modpackDir) == ModpackUtils.UpdateType.NONE) {
                    // check if modpack is loaded now loaded
                    if (loadIfItsNotLoaded) {
                        if (!ModpackUtils.isLoaded(serverModpackContent)) {
                            LOGGER.info("Modpack is not loaded, loading...");
                            // copy files to running directory
                            ModpackUtils.copyModpackFiles(modpackDir, serverModpackContent);
                            checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");
                            new ReLauncher.Restart(modpackDir);
                        }
                    }
                    return;
                }
            } else if (!preload && ScreenTools.getScreen() != null) {
                if (serverModpackContent == null) return;
                CompletableFuture.runAsync(() -> {
                    while (!ScreenTools.getScreenString().contains("dangerscreen")) {
                        ScreenTools.setTo.danger(ScreenTools.getScreen(), link, modpackDir, loadIfItsNotLoaded, modpackContentFile);
                        new Wait(100);
                    }
                });
                return;
            }

            if (serverModpackContent == null) return;

            ModpackUpdaterMain(link, modpackDir, loadIfItsNotLoaded, modpackContentFile);

        } catch (Exception e) {
            LOGGER.error("Error while initializing modpack updater");
            e.printStackTrace();
        }
    }

    public static void ModpackUpdaterMain(String link, File modpackDir, boolean loadIfItsNotLoaded, File modpackContentFile) {

        long start = System.currentTimeMillis();

        try {
            List<Config.ModpackContentFields.ModpackContentItems> copyModpackContentList = new ArrayList<>(serverModpackContent.list);

            for (Config.ModpackContentFields.ModpackContentItems modpackContentField : serverModpackContent.list) {
                String fileName = modpackContentField.file;
                String serverChecksum = modpackContentField.hash;

                File fileInRunDir = new File("./" + fileName);

                if (!fileInRunDir.exists()) {
                    continue;
                }

                if (serverChecksum.equals(CustomFileUtils.getHash(fileInRunDir, "SHA-256"))) {
                    LOGGER.info("Skipping already downloaded file: " + fileName);
                    copyModpackContentList.remove(modpackContentField);
                } else if (modpackContentField.isEditable) {
                    LOGGER.info("Skipping editable file: " + fileName);
                    copyModpackContentList.remove(modpackContentField);
                }
            }

            for (Config.ModpackContentFields.ModpackContentItems modpackContentField : copyModpackContentList) {
                if (!modpackContentField.size.matches("^[0-9]*$")) continue;
                totalBytesToDownload += Long.parseLong(modpackContentField.size);
            }

            ModpackUpdater.wholeQueue = copyModpackContentList.size();

            LOGGER.info("In queue left {} files to download which is {}kb", wholeQueue, totalBytesToDownload / 1024);

            if (wholeQueue > 0) {
                for (Config.ModpackContentFields.ModpackContentItems modpackContentField : copyModpackContentList) {
                    while (downloadFutures.size() >= MAX_DOWNLOADS) { // Async Setting - max `some` download at the same time
                        downloadFutures = downloadFutures.stream()
                                .filter(future -> !future.isDone())
                                .collect(Collectors.toList());
                    }

                    String fileName = modpackContentField.file;
                    String serverChecksum = modpackContentField.hash;
                    boolean isEditable = modpackContentField.isEditable;

                    File downloadFile = new File(modpackDir + File.separator + fileName);
                    String url;
                    if (modpackContentField.link.startsWith("/")) { // AutoModpack host
                        url = link + modpackContentField.link;
                        url = Url.encode(url); // We need to change things like [ ] to %5B %5D etc.
                    } else { // Other host
                        url = modpackContentField.link; // This link just must work, so we don't need to encode it
                    }

                    downloadFutures.add(processAsync(url, downloadFile, serverChecksum));
                }

                CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0])).get();
            }

            // Downloads completed
            if (update) {
                // if was updated, delete old files from modpack
                List<String> files = serverModpackContent.list.stream().map(modpackContentField -> new File(modpackContentField.file).getName()).toList();

                try (Stream<Path> stream = Files.walk(modpackDir.toPath(), 10)) {
                    for (Path file : stream.toList()) {
                        if (Files.isDirectory(file)) continue;
                        if (file.equals(modpackContentFile.toPath())) continue;
                        if (!files.contains(file.toFile().getName())) {
                            LOGGER.info("Deleting " + file.toFile().getName());
                            CustomFileUtils.forceDelete(file.toFile(), true);
                            changelogList.put(file.toFile().getName(), false);
                        }
                        File fileInRunningDir = new File("." + file.toFile());
                        if (fileInRunningDir.exists() && CustomFileUtils.compareFileHashes(file.toFile(), fileInRunningDir, "SHA-256")) {
                            CustomFileUtils.forceDelete(fileInRunningDir, false);
                        }
                    }
                } catch (IOException e) {
                    LOGGER.error("An error occurred while trying to walk through the files in the modpack directory", e);
                }


                // clear empty directories
                CustomFileUtils.deleteEmptyFiles(modpackDir, true, serverModpackContent.list);
                CustomFileUtils.deleteEmptyFiles(new File("./"), false, serverModpackContent.list);
            }

            // Modpack updated

            // copy files to running directory
            ModpackUtils.copyModpackFiles(modpackDir, serverModpackContent);

            if (modpackContentFile.exists()) {
                CustomFileUtils.forceDelete(modpackContentFile, false);
            }

            Files.write(modpackContentFile.toPath(), GSON.toJson(serverModpackContent).getBytes());

            checkAndRemoveDuplicateMods(modpackDir + File.separator + "mods");

            if (!failedDownloads.isEmpty()) {
                StringBuilder failedFiles = new StringBuilder("null");
                for (Map.Entry<String, String> entry : failedDownloads.entrySet()) {
                    LOGGER.error("Failed to download: " + entry.getKey() + " from " + entry.getValue());
                    failedFiles.append(entry.getKey());
                }
                ScreenTools.setTo.error("Failed to download some files", "Failed to download: " + failedFiles, "More details in logs.");
                return;
            }

            LOGGER.info("Modpack is up-to-date! Took: " + (System.currentTimeMillis() - start) + " ms");

            if (loadIfItsNotLoaded) {
                if (!ModpackUtils.isLoaded(serverModpackContent)) {
                    LOGGER.warn("Modpack is not loaded!");
                    new ReLauncher.Restart(modpackDir);
                }
            }

            if (update) {
                new ReLauncher.Restart(modpackDir);
            }

        } catch (SocketTimeoutException | ConnectException e) {
            LOGGER.error("Modpack host of " + link + " is not responding", e);
        } catch (Exception e) {
            ScreenTools.setTo.error("Critical error while downloading modpack.", "\"" + e.getMessage() + "\"", "More details in logs.");
            e.printStackTrace();
        }
    }

    private static CompletableFuture<Void> processAsync(String url, File downloadFile, String serverChecksum) {
        return CompletableFuture.runAsync(() -> process(url, downloadFile, serverChecksum), DOWNLOAD_EXECUTOR);
    }

    // TODO remove this method when we finally manage to fix weird issue with different checksums
    private static void process(String url, File downloadFile, String serverChecksum) {
        if (!downloadFile.exists()) {
            downloadFile(url, downloadFile, serverChecksum);
            return;
        }

        try {
            String localChecksum = CustomFileUtils.getHash(downloadFile, "SHA-256");

            if (serverChecksum.equals(localChecksum)) { // up-to-date
                LOGGER.info("File " + downloadFile.getName() + " is up-to-date!");
                return;
            }

            LOGGER.warn(downloadFile.getName() + " Local checksum: " + localChecksum + " Server checksum: " + serverChecksum);
            downloadFile(url, downloadFile, serverChecksum);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadFile(String url, File downloadFile, String serverChecksum) {
        if (!update || !ScreenTools.getScreenString().contains("downloadscreen")) {
            ScreenTools.setTo.download();
        }
        update = true;
        DownloadInfo downloadInfo = new DownloadInfo(downloadFile.getName());
        downloadInfos.add(downloadInfo);

        int maxAttempts = 5;
        int attempts = 0;
        boolean success = false;
        long startTime = System.currentTimeMillis();

        while (attempts < maxAttempts && !success) {
            attempts++;
            LOGGER.info("Downloading {}... (attempt {})", downloadFile.getName(), attempts);
            LOGGER.info("URL: {}", url);

            try {
                Download downloadInstance = new Download();
                CompletableFuture.runAsync(() -> {
                    while (!downloadInstance.isDownloading()) {
                        new Wait(1);
                    }

                    long oldValue = 0;

                    while (downloadInstance.isDownloading()) {
                        oldValue = updateDownloadInfo(downloadInstance, downloadInfo, oldValue);
                        new Wait(25);
                    }
                });

                downloadInstance.download(url, downloadFile);

                String ourChecksum = CustomFileUtils.getHash(downloadFile, "SHA-256");

                long size = downloadInstance.getFileSize();

                if (serverChecksum.equals(ourChecksum)) {
                    success = true;
                } else if (attempts == maxAttempts && !downloadFile.toString().endsWith(".jar") && downloadFile.length() == size) {
                    // FIXME: it shouldn't even return wrong checksum if the size is correct...
                    LOGGER.warn("Checksums of {} do not match, but size is correct so we will assume it is correct lol", downloadFile.getName());
                    success = true;
                } else {
                    if (attempts != maxAttempts) {
                        LOGGER.warn("Checksums do not match, retrying... client: {} server: {}", ourChecksum, serverChecksum);
                    }
                    CustomFileUtils.forceDelete(downloadFile, false);
                    totalBytesDownloaded -= size;
                }
            } catch (SocketTimeoutException e) {
                LOGGER.error("Download of {} timed out, retrying...", downloadFile.getName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        downloadInfos.remove(downloadInfo);
        if (success) {
            LOGGER.info("{} downloaded successfully in {}ms", downloadFile.getName(), (System.currentTimeMillis() - startTime));
            changelogList.put(downloadFile.getName(), true);
            alreadyDownloaded++;
        } else {
            failedDownloads.put(downloadFile.getName(), url);
            LOGGER.error("Failed to download {} after {} attempts", downloadFile.getName(), attempts);
        }
    }

    private static long updateDownloadInfo(Download downloadInstance, DownloadInfo downloadInfo, long oldValue) {
        downloadInfo.setBytesDownloaded(downloadInstance.getTotalBytesRead());
        downloadInfo.setDownloadSpeed(downloadInstance.getBytesPerSecond() / 1024 / 1024);
        downloadInfo.setDownloading(downloadInstance.isDownloading());
        downloadInfo.setEta(downloadInstance.getETA());
        downloadInfo.setFileSize(downloadInstance.getFileSize());
        downloadInfo.setBytesPerSecond(downloadInstance.getBytesPerSecond());

        totalBytesDownloaded += downloadInstance.getTotalBytesRead() - oldValue;
        oldValue = downloadInstance.getTotalBytesRead();
        return oldValue;
    }


    // This method cancels the current download by interrupting the thread pool
    public static void cancelDownload() {
        try {
            LOGGER.info("Cancelling download for " + downloadFutures.size() + " files...");
            downloadFutures.forEach(future -> future.cancel(true));
            DOWNLOAD_EXECUTOR.shutdownNow();

            downloadFutures.clear();
            downloadInfos.clear();
            DOWNLOAD_EXECUTOR = null;
            totalBytesDownloaded = 0;
            alreadyDownloaded = 0;
            failedDownloads.clear();
            changelogList.clear();
            update = false;

            LOGGER.info("Download canceled");

            if (ScreenTools.getScreenString().contains("download")) {
                ScreenTools.setTo.title();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static DownloadInfo getDownloadInfo(String name) {
        List<DownloadInfo> downloadInfosCopy = new ArrayList<>(downloadInfos);
        for (DownloadInfo downloadInfo : downloadInfosCopy) { // this is done like that to avoid ConcurrentModificationException
            if (downloadInfo == null || downloadInfo.getFileName() == null) continue;
            if (downloadInfo.getFileName().equals(name)) {
                return downloadInfo;
            }
        }
        return null;
    }

    // removes mods from main mods folder that are having the same id as the ones in the modpack mods folder but different version/checksum
    private static void checkAndRemoveDuplicateMods(String modpackModsFile) {
        Map<String, String> mainMods = getMods("./mods/");
        Map<String, String> modpackMods = getMods(modpackModsFile);

        if (mainMods == null || modpackMods == null) return;

        // print out all the mods
//        System.out.println("--------------------");
//        System.out.println("Main mod folder mods:");
//        for (Map.Entry<String, String> mainMod : mainMods.entrySet()) {
//            System.out.println(mainMod.getKey() + " <-> " + mainMod.getValue());
//        }
//        System.out.println("--------------------");
//        System.out.println("Modpack mods:");
//        for (Map.Entry<String, String> modpackMod : modpackMods.entrySet()) {
//            System.out.println(modpackMod.getKey() + " <-> " + modpackMod.getValue());
//        }
//        System.out.println("--------------------");

        if (!hasDuplicateValues(mainMods)) return;

        for (Map.Entry<String, String> mainMod : mainMods.entrySet()) {
            String mainModFileName = mainMod.getKey();
            String mainModId = mainMod.getValue();

            if (mainModId == null || mainModFileName == null) {
                continue;
            }

            for (Map.Entry<String, String> modpackMod : modpackMods.entrySet()) {
                String modpackModFileName = modpackMod.getKey();
                String modpackModId = modpackMod.getValue();

                if (modpackModId == null || modpackModFileName == null) {
                    continue;
                }

                if (mainModId.equals(modpackModId) && !mainModFileName.equals(modpackModFileName)) {
                    File mainModFile = new File("./mods/" + mainModFileName);
                    LOGGER.info("Deleting {} from main mods folder...", mainModFile.getName());
                    CustomFileUtils.forceDelete(mainModFile, true);
                    break;
                }
            }
        }
    }

    private static Map<String, String> getMods(String modsDir) {
        Map<String, String> defaultMods = new HashMap<>();
        File defaultModsFolder = new File(modsDir);
        File[] defaultModsFiles = defaultModsFolder.listFiles();
        if (defaultModsFiles == null) return null;
        for (File defaultMod : defaultModsFiles) {
            if (!defaultMod.isFile() || !defaultMod.getName().endsWith(".jar")) continue;
            defaultMods.put(defaultMod.getName(), JarUtilities.getModIdFromJar(defaultMod, true));
        }
        return defaultMods;
    }

    private static boolean hasDuplicateValues(Map<String, String> map) {
        Set<String> values = new HashSet<>();
        for (String value : map.values()) {
            if (values.contains(value)) {
                return true;
            }
            values.add(value);
        }
        return false;
    }

}