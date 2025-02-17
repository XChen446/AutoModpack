package pl.skidam.automodpack_core.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.loader.LoaderManagerService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class FileInspection {

    public static boolean isMod(Path file) {
        return getModID(file) != null || hasSpecificServices(file);
    }

    public static Path getAutoModpackJar() {
        try {
            // TODO find better way to parse that path
            URI uri = FileInspection.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            // Example: union:/home/skidam/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/1.18.2/.minecraft/mods/automodpack-forge-4.0.0-beta0-1.18.2.jar%2354!/
            // Format it into proper path like: /home/skidam/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/1.18.2/.minecraft/mods/automodpack-forge-4.0.0-beta0-1.18.2.jar

            String path = uri.getPath();
            int index = path.indexOf('!');
            if (index != -1) {
                path = path.substring(0, index);
            }

            index = path.indexOf('#');
            if (index != -1) {
                path = path.substring(0, index);
            }

            // check for windows
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
            }

            return Path.of(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Checks for neo/forge mod locators
    public static boolean hasSpecificServices(Path file) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return false;
        }

        String[] services = {
                "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
                "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator",
                "META-INF/services/net.neoforged.neoforgespi.locating.IModLocator",
                "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator",
                "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
                "META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper"
        };

        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            for (String service : services) {
                if (zipFile.getEntry(service) != null) {
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    public static boolean isModCompatible(Path file) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return false;
        }

        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            String loader = GlobalVariables.LOADER;
            String entryName = switch (loader) {
                case "fabric" -> "fabric.mod.json";
                case "quilt" -> "quilt.mod.json";
                case "forge" -> "META-INF/mods.toml";
                case "neoforge" -> "META-INF/neoforge.mods.toml";
                default -> null;
            };

            if (loader.equals("forge") || loader.equals("neoforge")) {
                if (hasSpecificServices(file)) {
                    return true;
                }
            }

            return entryName != null && zipFile.getEntry(entryName) != null;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }


    public static ZipEntry getMetadataEntry(ZipFile zipFile) {
        var currentLoader = GlobalVariables.LOADER;

        // first get preferred metadata for current loader if exits
        ZipEntry entry = switch (currentLoader) {
            case "fabric" -> zipFile.getEntry("fabric.mod.json");
            case "quilt" -> zipFile.getEntry("quilt.mod.json");
            case "forge" -> zipFile.getEntry("META-INF/mods.toml");
            case "neoforge" -> zipFile.getEntry("META-INF/neoforge.mods.toml");
            default -> null;
        };

        if (entry != null) {
            return entry;
        }

        // get any existing
        String[] entriesToCheck = {
                "fabric.mod.json",
                "META-INF/neoforge.mods.toml",
                "META-INF/mods.toml",
                "quilt.mod.json",
        };

        for (String entryName : entriesToCheck) {
            entry = zipFile.getEntry(entryName);
            if (entry != null) {
                return entry;
            }
        }

        return null;
    }

    public static String getModVersion(Path file) {
        return (String) getModInfo(file, "version");
    }

    public static String getModID(Path file) {
        return (String) getModInfo(file, "modId");
    }

    public static Set<String> getAllProvidedIDs(Path file) {
        return (Set<String>) getModInfo(file, "provides");
    }

    public static Set<String> getModDependencies(Path file) {
        return (Set<String>) getModInfo(file, "dependencies");
    }

    public static LoaderManagerService.EnvironmentType getModEnvironment(Path file) {
        return (LoaderManagerService.EnvironmentType) getModInfo(file, "environment");
    }

    private static Object getModInfo(Path file, String infoType) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
        }

        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            ZipEntry entry = getMetadataEntry(zipFile);
            if (entry == null) {
                return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
            }

            Gson gson = new Gson();
            try (InputStream stream = zipFile.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {

                if (entry.getName().endsWith("mods.toml")) {
                    return getModInfoFromToml(reader, infoType, file);
                } else {
                    return getModInfoFromJson(reader, gson, infoType);
                }
            }
        } catch (ZipException ignored) {
        } catch (IOException e) {
            e.printStackTrace();
        }

        return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
    }

    private static Object getModInfoFromToml(BufferedReader reader, String infoType, Path file) {
        try {
            TomlParseResult result = Toml.parse(reader);
            result.errors().forEach(error -> GlobalVariables.LOGGER.error(error.toString()));

            TomlArray modsArray = result.getArray("mods");
            if (modsArray == null) {
                return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
            }

            switch (infoType) {
                case "version" -> {
                    String modVersion = null;
                    for (Object o : modsArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            modVersion = mod.getString("version");
                        }
                    }
                    return modVersion;
                }
                case "modId" -> {
                    String modID = null;
                    for (Object o : modsArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            modID = mod.getString("modId");
                        }
                    }
                    return modID;
                }
                case "provides" -> {
                    Set<String> providedIDs = new HashSet<>();
                    for (Object o : modsArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            TomlArray providesArray = mod.getArray("provides");
                            if (providesArray != null) {
                                for (int j = 0; j < providesArray.size(); j++) {
                                    String id = providesArray.getString(j);
                                    if (id != null && !id.isEmpty()) {
                                        providedIDs.add(id);
                                    }
                                }
                            }
                        }
                    }
                    return providedIDs;
                }
                case "dependencies" -> {
                    String modID = getModID(file);
                    TomlArray dependenciesArray = result.getArray("dependencies.\"" + modID + "\"");
                    if (dependenciesArray == null) {
                        return Set.of();
                    }

                    Set<String> dependencies = new HashSet<>();
                    for (Object o : dependenciesArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            dependencies.add(mod.getString("modId"));
                        }
                    }
                    return dependencies;
                }
                case "environment" -> { // There's no way to check that on neo/forge
                    return null;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
    }

    private static Object getModInfoFromJson(BufferedReader reader, Gson gson, String infoType) {
        JsonObject json = gson.fromJson(reader, JsonObject.class);

        switch (infoType) {
            case "version" -> {
                if (json.has("version")) {
                    return json.get("version").getAsString();
                } else if (json.has("quilt_loader") && json.get("quilt_loader").getAsJsonObject().has("version")) {
                    return json.get("quilt_loader").getAsJsonObject().get("version").getAsString();
                }
            }
            case "modId" -> {
                if (json.has("id")) {
                    return json.get("id").getAsString();
                } else if (json.has("quilt_loader") && json.get("quilt_loader").getAsJsonObject().has("id")) {
                    return json.get("quilt_loader").getAsJsonObject().get("id").getAsString();
                }
            }
            case "provides" -> {
                Set<String> providedIDs = new HashSet<>();
                if (json.has("provides")) {
                    for (JsonElement provides : json.get("provides").getAsJsonArray()) {
                        providedIDs.add(provides.getAsString());
                    }
                } else if (json.has("quilt_loader") && json.get("quilt_loader").getAsJsonObject().has("provides")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    for (JsonElement provides : quiltLoader.get("provides").getAsJsonArray()) {
                        JsonObject providesObject = provides.getAsJsonObject();
                        String id = providesObject.get("id").getAsString();
                        providedIDs.add(id);
                    }
                }
                return providedIDs;
            }
            case "dependencies" -> {
                Set<String> dependencies = new HashSet<>();
                if (json.has("depends")) {
                    JsonObject depends = json.get("depends").getAsJsonObject();
                    if (depends != null) { // Dont use asMap() since its only on gson 2.10^ - forge 1.18
                        dependencies.addAll(depends.entrySet().stream().map(Map.Entry::getKey).toList());
                    }
                } else if (json.has("quilt_loader") && json.get("quilt_loader").getAsJsonObject().has("depends")) {
                    JsonObject depends = json.get("quilt_loader").getAsJsonObject().get("depends").getAsJsonObject();
                    if (depends != null) { // Dont use asMap() since its only on gson 2.10^ - forge 1.18
                        dependencies.addAll(depends.entrySet().stream().map(Map.Entry::getKey).toList());
                    }
                }
                return dependencies;
            }
            case "environment" -> {
                if (json.has("environment")) {
                    String environment = json.get("environment").getAsString();
                    return switch (environment) {
                        case "client" -> LoaderManagerService.EnvironmentType.CLIENT;
                        case "server" -> LoaderManagerService.EnvironmentType.SERVER;
                        default -> LoaderManagerService.EnvironmentType.UNIVERSAL;
                    };
                } else if (json.has("quilt_loader") && json.get("minecraft").getAsJsonObject().has("environment")) {
                    String environment = json.get("minecraft").getAsJsonObject().get("environment").getAsString();
                    return switch (environment) {
                        case "client" -> LoaderManagerService.EnvironmentType.CLIENT;
                        case "server" -> LoaderManagerService.EnvironmentType.SERVER;
                        default -> LoaderManagerService.EnvironmentType.UNIVERSAL;
                    };
                }
            }
        }

        return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
    }

    private static final String forbiddenChars = "\\/:*\"<>|!?.";

    public static boolean isInValidFileName(String fileName) {
        // Check for each forbidden character in the file name
        for (char c : forbiddenChars.toCharArray()) {
            if (fileName.indexOf(c) != -1) {
                return true;
            }
        }

        // Check if the file name is empty or just contains whitespace
        return fileName.trim().isEmpty();
    }

    public static String fixFileName(String fileName) {
        // Replace forbidden characters with underscores
        for (char c : forbiddenChars.toCharArray()) {
            fileName = fileName.replace(c, '-');
        }

        // Remove leading and trailing whitespace
        fileName = fileName.trim();

        return fileName;
    }
}
