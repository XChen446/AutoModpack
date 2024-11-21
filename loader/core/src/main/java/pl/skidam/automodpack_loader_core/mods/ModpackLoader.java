package pl.skidam.automodpack_loader_core.mods;

import pl.skidam.automodpack_core.loader.ModpackLoaderService;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public class ModpackLoader implements ModpackLoaderService {
    @Override
    public void loadModpack(List<Path> modpackMods) {
        throw new AssertionError("Loader class not found");
    }

    @Override
    public boolean prepareModpack(Path modpackDir, Set<String> workaroundMods) {
        throw new AssertionError("Loader class not found");
    }
}
