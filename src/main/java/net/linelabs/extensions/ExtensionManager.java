package net.linelabs.extensions;

import com.google.gson.Gson;
import net.minestom.dependencies.DependencyGetter;
import net.minestom.dependencies.ResolvedDependency;
import net.minestom.dependencies.maven.MavenRepository;
import net.minestom.server.ServerFlag;
import net.minestom.server.ServerProcess;
import net.minestom.server.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ExtensionManager {

    public final static Logger log = LoggerFactory.getLogger(ExtensionManager.class);

    private final static Gson gson = new Gson();

    private final ServerProcess serverProcess;

    // LinkedHashMaps are HashMaps that preserve order
    private final Map<String, Extension> extensions = new LinkedHashMap<>();
    private final Map<String, Extension> immutableExtensions = Collections.unmodifiableMap(extensions);

    private final File extensionFolder = new File(ServerFlag.EXTENSIONS_FOLDER);
    private final File dependenciesFolder = new File(extensionFolder, ".libs");
    private Path extensionDataRoot = extensionFolder.toPath();

    private enum State {
        DO_NOT_START,
        NOT_STARTED,
        STARTED,
        PRE_INIT,
        INIT,
        POST_INIT
    }

    private State state = State.NOT_STARTED;

    public ExtensionManager(ServerProcess serverProcess) {
        this.serverProcess = serverProcess;
    }

    /**
     * Gets if the extensions should be loaded during startup.
     * <p>
     * Default value is 'true'.
     *
     * @return true if extensions are loaded in {@link net.minestom.server.MinecraftServer#start(java.net.SocketAddress)}
     */
    public boolean shouldLoadOnStartup() {
        return state != State.DO_NOT_START;
    }

    /**
     * Used to specify if you want extensions to be loaded and initialized during startup.
     * <p>
     * Only useful before the server start.
     *
     * @param loadOnStartup true to load extensions on startup, false to do nothing
     */
    public void setLoadOnStartup(boolean loadOnStartup) {
        Check.stateCondition(state.ordinal() > State.NOT_STARTED.ordinal(), "Extensions have already been initialized");
        this.state = loadOnStartup ? State.NOT_STARTED : State.DO_NOT_START;
    }

    @NotNull
    public File getExtensionFolder() {
        return extensionFolder;
    }

    public @NotNull Path getExtensionDataRoot() {
        return extensionDataRoot;
    }

    public void setExtensionDataRoot(@NotNull Path dataRoot) {
        this.extensionDataRoot = dataRoot;
    }

    @NotNull
    public Collection<Extension> getExtensions() {
        return immutableExtensions.values();
    }

    @Nullable
    public Extension getExtension(@NotNull String name) {
        return extensions.get(name.toLowerCase());
    }

    public boolean hasExtension(@NotNull String name) {
        return extensions.containsKey(name);
    }

    //
    // Init phases
    //

    @ApiStatus.Internal
    public void start() {
        if (state == State.DO_NOT_START) {
            log.warn("Extension loadOnStartup option is set to false, extensions are therefore neither loaded or initialized.");
            return;
        }
        Check.stateCondition(state != State.NOT_STARTED, "ExtensionManager has already been started");
        loadExtensions();
        state = State.STARTED;
    }

    @ApiStatus.Internal
    public void gotoPreInit() {
        if (state == State.DO_NOT_START) {
            return;
        }
        Check.stateCondition(state != State.STARTED, "Extensions have already done pre initialization");
        extensions.values().forEach(Extension::preInitialize);
        state = State.PRE_INIT;
    }

    @ApiStatus.Internal
    public void gotoInit() {
        if (state == State.DO_NOT_START) {
            return;
        }
        Check.stateCondition(state != State.PRE_INIT, "Extensions have already done initialization");
        extensions.values().forEach(Extension::initialize);
        state = State.INIT;
    }

    @ApiStatus.Internal
    public void gotoPostInit() {
        if (state == State.DO_NOT_START) {
            return;
        }
        Check.stateCondition(state != State.INIT, "Extensions have already done post initialization");
        extensions.values().forEach(Extension::postInitialize);
        state = State.POST_INIT;
    }

    //
    // Loading
    //

    /**
     * Loads all extensions in the extension folder into this server.
     * <br><br>
     * <p>
     * Pipeline:
     * <br>
     * Finds all .jar files in the extensions folder.
     * <br>
     * Per each jar:
     * <br>
     * Turns its extension.json into a DiscoveredExtension object.
     * <br>
     * Verifies that all properties of extension.json are correctly set.
     * <br><br>
     * <p>
     * It then sorts all those jars by their load order (making sure that an extension's dependencies load before it)
     * <br>
     * Note: Cyclic dependencies will stop both extensions from being loaded.
     * <br><br>
     * <p>
     * Afterwards, it loads all external dependencies and adds them to the extension's files
     * <br><br>
     * <p>
     * Then removes any invalid extensions (Invalid being its Load Status isn't SUCCESS)
     * <br><br>
     * <p>
     * After that, it set its classloaders so each extension is self-contained,
     * <br><br>
     * <p>
     * Removes invalid extensions again,
     * <br><br>
     * <p>
     * and loads all of those extensions into Minestom
     * <br>
     * (Extension fields are set via reflection after each extension is verified, then loaded.)
     * <br><br>
     * <p>
     * If the extension successfully loads, add it to the global extension Map (Name to Extension)
     * <br><br>
     * <p>
     * And finally make a scheduler to clean observers per extension.
     */
    private void loadExtensions() {
        // Initialize folders
        {
            // Make extensions folder if necessary
            if (!extensionFolder.exists()) {
                if (!extensionFolder.mkdirs()) {
                    log.error("Could not find or create the extension folder, extensions will not be loaded!");
                    return;
                }
            }

            // Make dependencies folder if necessary
            if (!dependenciesFolder.exists()) {
                if (!dependenciesFolder.mkdirs()) {
                    log.error("Could not find nor create the extension dependencies folder, extensions will not be loaded!");
                    return;
                }
            }
        }

        // Load extensions
        {
            // Get all extensions and order them accordingly.
            List<DiscoveredExtension> discoveredExtensions = discoverExtensions();

            // Don't waste resources on doing extra actions if there is nothing to do.
            if (discoveredExtensions.isEmpty()) {
                return;
            }

            // Create classloaders for each extension (so that they can be used during dependency resolution)
            Iterator<DiscoveredExtension> extensionIterator = discoveredExtensions.iterator();
            while (extensionIterator.hasNext()) {
                DiscoveredExtension discoveredExtension = extensionIterator.next();
                try {
                    discoveredExtension.createClassLoader();
                } catch (Exception ex) {
                    discoveredExtension.loadStatus = DiscoveredExtension.LoadStatus.FAILED_TO_SETUP_CLASSLOADER;
                    serverProcess.exception().handleException(ex);
                    log.error("Failed to load extension {}", discoveredExtension.getName());
                    log.error("Failed to load extension", ex);
                    extensionIterator.remove();
                }
            }

            discoveredExtensions = generateLoadOrder(discoveredExtensions);
            loadDependencies(discoveredExtensions);

            // remove invalid extensions
            discoveredExtensions.removeIf(ext -> ext.loadStatus != DiscoveredExtension.LoadStatus.LOAD_SUCCESS);

            // Load the extensions
            for (DiscoveredExtension discoveredExtension : discoveredExtensions) {
                try {
                    loadExtension(discoveredExtension);
                } catch (Exception ex) {
                    discoveredExtension.loadStatus = DiscoveredExtension.LoadStatus.LOAD_FAILED;
                    log.error("Failed to load extension {}", discoveredExtension.getName());
                    serverProcess.exception().handleException(ex);
                }
            }
        }
    }

    public boolean loadDynamicExtension(@NotNull File jarFile) throws FileNotFoundException {
        if (!jarFile.exists()) {
            throw new FileNotFoundException("File '" + jarFile.getAbsolutePath() + "' does not exists. Cannot load extension.");
        }

        log.info("Discover dynamic extension from jar {}", jarFile.getAbsolutePath());
        DiscoveredExtension discoveredExtension = discoverFromJar(jarFile);
        List<DiscoveredExtension> extensionsToLoad = Collections.singletonList(discoveredExtension);
        return loadExtensionList(extensionsToLoad);
    }

    /**
     * Loads an extension into Minestom.
     *
     * @param discoveredExtension The extension. Make sure to verify its integrity, set its class loader, and its files.
     * @return An extension object made from this DiscoveredExtension
     */
    private @Nullable Extension loadExtension(@NotNull DiscoveredExtension discoveredExtension) {
        // Create Extension (authors, version etc.)
        String extensionName = discoveredExtension.getName();
        String mainClass = discoveredExtension.getEntrypoint();

        if (extensions.containsKey(extensionName.toLowerCase())) {
            log.error("An extension called '{}' has already been registered.", extensionName);
            return null;
        }

        ExtensionClassLoader loader = discoveredExtension.getClassLoader();
        Class<?> jarClass;
        try {
            jarClass = Class.forName(mainClass, true, loader);
        } catch (ClassNotFoundException ex) {
            log.error("Could not find main class '{}' in extension '{}'.",
                    mainClass, extensionName, ex);
            return null;
        }

        Class<? extends Extension> extensionClass;
        try {
            extensionClass = jarClass.asSubclass(Extension.class);
        } catch (ClassCastException ex) {
            log.error("Main class '{}' in '{}' does not extend the 'Extension' superclass.", mainClass, extensionName, ex);
            return null;
        }

        Constructor<? extends Extension> constructor;
        try {
            constructor = extensionClass.getDeclaredConstructor();
            // Let's just make it accessible, plugin creators don't have to make this public.
            constructor.setAccessible(true);
        } catch (NoSuchMethodException ex) {
            log.error("Main class '{}' in '{}' does not define a no-args constructor.", mainClass, extensionName, ex);
            return null;
        }
        Extension extension = null;
        try {
            extension = constructor.newInstance();
        } catch (InstantiationException ex) {
            log.error("Main class '{}' in '{}' cannot be an abstract class.", mainClass, extensionName, ex);
            return null;
        } catch (IllegalAccessException ignored) {
            // We made it accessible, should not occur
        } catch (InvocationTargetException ex) {
            log.error(
                    "While instantiating the main class '{}' in '{}' an exception was thrown.",
                    mainClass,
                    extensionName,
                    ex.getTargetException()
            );
            return null;
        }

        // add dependents to pre-existing extensions, so that they can easily be found during reloading
        for (String dependencyName : discoveredExtension.getDependencies()) {
            Extension dependency = extensions.get(dependencyName.toLowerCase());
            if (dependency == null) {
                log.warn("Dependency {} of {} is null? This means the extension has been loaded without its dependency, which could cause issues later.", dependencyName, discoveredExtension.getName());
            } else {
                dependency.getDependents().add(discoveredExtension.getName());
            }
        }

        // add to a linked hash map, as they preserve order
        extensions.put(extensionName.toLowerCase(), extension);
        return extension;
    }

    /**
     * Get all extensions from the extensions folder and make them discovered.
     * <p>
     * It skims the extension folder, discovers and verifies each extension, and returns those created DiscoveredExtensions.
     *
     * @return A list of discovered extensions from this folder.
     */
    private @NotNull List<DiscoveredExtension> discoverExtensions() {
        List<DiscoveredExtension> extensions = new LinkedList<>();

        File[] fileList = extensionFolder.listFiles();
        if (fileList != null) {
            // Loop through all files in extension folder
            for (File file : fileList) {
                // Ignore folders
                if (file.isDirectory()) {
                    continue;
                }

                // Ignore non .jar files
                if (!file.getName().endsWith(".jar")) {
                    continue;
                }

                DiscoveredExtension extension = discoverFromJar(file);
                if (extension != null && extension.loadStatus == DiscoveredExtension.LoadStatus.LOAD_SUCCESS) {
                    extensions.add(extension);
                }
            }
        }

        //TODO(mattw): Extract this into its own method to load an extension given classes and resources directory.
        //TODO(mattw): Should show a warning if one is set and not the other. It is most likely a mistake.

        // this allows developers to have their extension discovered while working on it, without having to build a jar and put in the extension folder
        if (ServerFlag.EXTENSIONS_DEV_CLASSES != null && ServerFlag.EXTENSIONS_DEV_RESOURCES != null) {
            log.info("Found indev folders for extension. Adding to list of discovered extensions.");
            final File extensionJsonFile = new File(ServerFlag.EXTENSIONS_DEV_RESOURCES, "extension.json");
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(extensionJsonFile))) {
                DiscoveredExtension extension = gson.fromJson(reader, DiscoveredExtension.class);
                extension.files.add(new File(ServerFlag.EXTENSIONS_DEV_CLASSES).toURI().toURL());
                extension.files.add(new File(ServerFlag.EXTENSIONS_DEV_RESOURCES).toURI().toURL());
                extension.setDataDirectory(getExtensionDataRoot().resolve(extension.getName()));

                // Verify integrity and ensure defaults
                DiscoveredExtension.verifyIntegrity(extension);

                if (extension.loadStatus == DiscoveredExtension.LoadStatus.LOAD_SUCCESS) {
                    extensions.add(extension);
                }
            } catch (IOException ex) {
                serverProcess.exception().handleException(ex);
            }
        }
        return extensions;
    }

    /**
     * Grabs a discovered extension from a jar.
     *
     * @param file The jar to grab it from (a .jar is a formatted .zip file)
     * @return The created DiscoveredExtension.
     */
    private @Nullable DiscoveredExtension discoverFromJar(@NotNull File file) {
        try (var zipFile = new ZipFile(file)) {
            ZipEntry extensionJsonEntry = zipFile.getEntry("extension.json");

            if (extensionJsonEntry == null) {
                throw new IllegalStateException("Missing extension.json in extension " + file.getName() + ".");
            }

            var reader = new InputStreamReader(zipFile.getInputStream(extensionJsonEntry));

            // Initialize DiscoveredExtension from GSON.
            DiscoveredExtension extension = gson.fromJson(reader, DiscoveredExtension.class);
            extension.setOriginalJar(file);
            extension.files.add(file.toURI().toURL());
            extension.setDataDirectory(getExtensionDataRoot().resolve(extension.getName()));

            // Verify integrity and ensure defaults
            DiscoveredExtension.verifyIntegrity(extension);

            return extension;
        } catch (IOException ex) {
            serverProcess.exception().handleException(ex);
            return null;
        }
    }

    private @NotNull List<DiscoveredExtension> generateLoadOrder(@NotNull List<DiscoveredExtension> discoveredExtensions) {
        // Extension --> Extensions it depends on.
        Map<DiscoveredExtension, List<DiscoveredExtension>> dependencyMap = new HashMap<>();

        // Put dependencies in dependency map
        {
            Map<String, DiscoveredExtension> extensionMap = new HashMap<>();

            // go through all the discovered extensions and assign their name in a map.
            for (DiscoveredExtension discoveredExtension : discoveredExtensions) {
                extensionMap.put(discoveredExtension.getName().toLowerCase(), discoveredExtension);
            }

            allExtensions:
            // go through all the discovered extensions and get their dependencies as extensions
            for (DiscoveredExtension discoveredExtension : discoveredExtensions) {
                List<DiscoveredExtension> dependencies = new ArrayList<>(discoveredExtension.getDependencies().length);

                // Map the dependencies into DiscoveredExtensions.
                for (String dependencyName : discoveredExtension.getDependencies()) {
                    DiscoveredExtension dependencyExtension = extensionMap.get(dependencyName.toLowerCase());

                    // Specifies an extension we don't have.
                    if (dependencyExtension == null) {
                        // attempt to see if it is not already loaded (happens with dynamic (re)loading)
                        if (extensions.containsKey(dependencyName.toLowerCase())) {
                            dependencies.add(extensions.get(dependencyName.toLowerCase()).getOrigin());
                            continue; // Go to the next loop in this dependency loop, this iteration is done.
                        } else {
                            // dependency isn't loaded, move on.
                            log.error("Extension {} requires an extension called {}.", discoveredExtension.getName(), dependencyName);
                            log.error("However the extension {} could not be found.", dependencyName);
                            log.error("Therefore {} will not be loaded.", discoveredExtension.getName());
                            discoveredExtension.loadStatus = DiscoveredExtension.LoadStatus.MISSING_DEPENDENCIES;
                            continue allExtensions; // the above labeled loop will go to the next extension as this dependency is invalid.

                        }
                    }

                    // This will add null for an unknown-extension
                    dependencies.add(dependencyExtension);
                }

                dependencyMap.put(discoveredExtension, dependencies);
            }
        }

        // List containing the load order.
        List<DiscoveredExtension> sortedLoadOrder = new LinkedList<>();

        // TODO actually have to read this
        {
            // entries with empty lists
            List<Map.Entry<DiscoveredExtension, List<DiscoveredExtension>>> loadableExtensions;

            // While there are entries with no more elements (no more dependencies)
            while (!(
                    loadableExtensions = dependencyMap.entrySet().stream().filter(entry -> isLoaded(entry.getValue())).toList()
            ).isEmpty()
            ) {
                // Get all "loadable" (not actually being loaded!) extensions and put them in the sorted list.
                for (Map.Entry<DiscoveredExtension, List<DiscoveredExtension>> entry : loadableExtensions) {
                    // Add to sorted list.
                    sortedLoadOrder.add(entry.getKey());
                    // Remove to make the next iterations a little quicker (hopefully) and to find cyclic dependencies.
                    dependencyMap.remove(entry.getKey());

                    // Remove this dependency from all the lists (if they include it) to make way for next level of extensions.
                    for (List<DiscoveredExtension> dependencies : dependencyMap.values()) {
                        dependencies.remove(entry.getKey());
                    }
                }
            }
        }

        // Check if there are cyclic extensions.
        if (!dependencyMap.isEmpty()) {
            log.error("Minestom found {} cyclic extensions.", dependencyMap.size());
            log.error("Cyclic extensions depend on each other and can therefore not be loaded.");
            for (Map.Entry<DiscoveredExtension, List<DiscoveredExtension>> entry : dependencyMap.entrySet()) {
                DiscoveredExtension discoveredExtension = entry.getKey();
                log.error("{} could not be loaded, as it depends on: {}.",
                        discoveredExtension.getName(),
                        entry.getValue().stream().map(DiscoveredExtension::getName).collect(Collectors.joining(", ")));
            }

        }

        return sortedLoadOrder;
    }

    /**
     * Checks if this list of extensions are loaded
     *
     * @param extensions The list of extensions to check against.
     * @return If all of these extensions are loaded.
     */
    private boolean isLoaded(@NotNull List<DiscoveredExtension> extensions) {
        return extensions.isEmpty() // Don't waste CPU on checking an empty array
                        // Make sure the internal extensions list contains all of these.
                        || extensions.stream().allMatch(ext -> this.extensions.containsKey(ext.getName().toLowerCase()));
    }

    private void loadDependencies(@NotNull List<DiscoveredExtension> extensions) {
        for (DiscoveredExtension discoveredExtension : extensions) {
            try {
                DependencyGetter getter = new DependencyGetter();
                DiscoveredExtension.ExternalDependencies externalDependencies = discoveredExtension.getExternalDependencies();
                List<MavenRepository> repositories = new LinkedList<>();

                for (DiscoveredExtension.ExternalDependencies.Repository repository : externalDependencies.repositories) {
                    if (repository.name == null || repository.name.isEmpty()) {
                        throw new IllegalStateException("Missing 'name' element in repository object.");
                    }

                    if (repository.url == null || repository.url.isEmpty()) {
                        throw new IllegalStateException("Missing 'url' element in repository object.");
                    }

                    repositories.add(new MavenRepository(repository.name, repository.url));
                }

                getter.addMavenResolver(repositories);

                for (String artifact : externalDependencies.artifacts) {
                    ResolvedDependency resolvedDependency = getter.get(artifact, dependenciesFolder);
                    addDependencyFile(resolvedDependency, discoveredExtension);
                    log.trace("Dependency of extension {}: {}", discoveredExtension.getName(), resolvedDependency);
                }

                ExtensionClassLoader extensionClassLoader = discoveredExtension.getClassLoader();
                for (String dependencyName : discoveredExtension.getDependencies()) {
                    var resolved = extensions.stream()
                            .filter(ext -> ext.getName().equalsIgnoreCase(dependencyName))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("Unknown dependency '" + dependencyName + "' of '" + discoveredExtension.getName() + "'"));

                    ExtensionClassLoader dependencyClassLoader = resolved.getClassLoader();

                    extensionClassLoader.addChild(dependencyClassLoader);
                    log.trace("Dependency of extension {}: {}", discoveredExtension.getName(), resolved);
                }
            } catch (Exception ex) {
                discoveredExtension.loadStatus = DiscoveredExtension.LoadStatus.MISSING_DEPENDENCIES;
                log.error("Failed to load dependencies for extension {}", discoveredExtension.getName());
                log.error("Extension '{}' will not be loaded", discoveredExtension.getName());
                log.error("This is the exception", ex);
            }
        }
    }

    private void addDependencyFile(@NotNull ResolvedDependency dependency, @NotNull DiscoveredExtension extension) {
        URL location = dependency.getContentsLocation();
        extension.files.add(location);
        extension.getClassLoader().addURL(location);
        log.trace("Added dependency {} to extension {} classpath", location.toExternalForm(), extension.getName());

        // recurse to add full dependency tree
        if (!dependency.getSubdependencies().isEmpty()) {
            log.trace("Dependency {} has subdependencies, adding...", location.toExternalForm());
            for (ResolvedDependency subDependency : dependency.getSubdependencies()) {
                addDependencyFile(subDependency, extension);
            }
            log.trace("Dependency {} has had its subdependencies added.", location.toExternalForm());
        }
    }

    private boolean loadExtensionList(@NotNull List<DiscoveredExtension> extensionsToLoad) {
        // ensure correct order of dependencies
        log.debug("Reorder extensions to ensure proper load order");
        extensionsToLoad = generateLoadOrder(extensionsToLoad);
        loadDependencies(extensionsToLoad);

        // setup new classloaders for the extensions to reload
        for (DiscoveredExtension toReload : extensionsToLoad) {
            log.debug("Setting up classloader for extension {}", toReload.getName());
//            toReload.setMinestomExtensionClassLoader(toReload.makeClassLoader()); //TODO: Fix this
        }

        List<Extension> newExtensions = new LinkedList<>();
        for (DiscoveredExtension toReload : extensionsToLoad) {
            // reload extensions
            log.info("Actually load extension {}", toReload.getName());
            Extension loadedExtension = loadExtension(toReload);
            if (loadedExtension != null) {
                newExtensions.add(loadedExtension);
            }
        }

        if (newExtensions.isEmpty()) {
            log.error("No extensions to load, skipping callbacks");
            return false;
        }

        log.info("Load complete, firing preinit, init and then postinit callbacks");
        // retrigger preinit, init and postinit
        newExtensions.forEach(Extension::preInitialize);
        newExtensions.forEach(Extension::initialize);
        newExtensions.forEach(Extension::postInitialize);
        return true;
    }

    //
    // Shutdown / Unload
    //

    /**
     * Shutdowns all the extensions by unloading them.
     */
    public void shutdown() {// copy names, as the extensions map will be modified via the calls to unload
        Set<String> extensionNames = new HashSet<>(extensions.keySet());
        for (String extensionName : extensionNames) {
            if (extensions.containsKey(extensionName)) { // is still loaded? Because extensions can depend on one another, it might have already been unloaded
                unloadExtension(extensionName);
            }
        }
    }

    private void unloadExtension(@NotNull String extensionName) {
        Extension extension = extensions.get(extensionName.toLowerCase());

        if (extension == null) {
            throw new IllegalArgumentException("Extension " + extensionName + " is not currently loaded.");
        }

        List<String> dependents = new LinkedList<>(extension.getDependents()); // copy dependents list

        for (String dependentId : dependents) {
            Extension dependentExtension = extensions.get(dependentId.toLowerCase());
            if (dependentExtension != null) { // check if extension isn't already unloaded.
                log.info("Unloading dependent extension {} (because it depends on {})", dependentId, extensionName);
                unload(dependentExtension);
            }
        }

        log.info("Unloading extension {}", extensionName);
        unload(extension);
    }

    private void unload(@NotNull Extension ext) {
        ext.preTerminate();
        ext.terminate();

        ext.getExtensionClassLoader().terminate();

        ext.postTerminate();

        // remove from loaded extensions
        String id = ext.getOrigin().getName().toLowerCase();
        extensions.remove(id);

        // cleanup classloader
        // TODO: Is it necessary to remove the CLs since this is only called on shutdown?
    }
}