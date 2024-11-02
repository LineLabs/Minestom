package net.minestom.bootstrap;

import dev.nipafx.args.Args;
import dev.nipafx.args.ArgsParseException;
import net.linelabs.extensions.ExtensionCommand;
import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.extras.MojangAuth;
import net.minestom.server.extras.velocity.VelocityProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;

/**
 * Main class for bootstrapping the Minestom server.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * Default constructor for the Main class.
     */
    public Main() {
        // Default constructor
    }

    /**
     * Main method to start the Minestom server.
     *
     * @param args Command-line arguments
     * @throws ArgsParseException if there is an error parsing the arguments
     */
    public static void main(String[] args) throws ArgsParseException {
        log.debug("Creating Minestom Server with Args: {}", Arrays.toString(args));
        ServerArgs serverArgs = Args.parse(args, ServerArgs.class);
        createServer(serverArgs);
    }

    private static void createServer(ServerArgs serverArgs) {
        MinecraftServer minecraftServer = MinecraftServer.init();

        if (ServerFlag.EXTENSIONS_ENABLED) {
            MinecraftServer.getCommandManager().register(new ExtensionCommand(MinecraftServer.getExtensionManager()));
        }

        boolean isOnlineMode = serverArgs.onlineMode().orElse(false);
        if (isOnlineMode) {
            log.info("Online mode enabled, skins will be render!");
            MojangAuth.init();
        }

        String secret = serverArgs.velocitySecret().orElse(null);
        if (secret != null) {
            VelocityProxy.enable(secret);
            log.info("Velocity proxy enabled with secret: {}", secret);
        }

        minecraftServer.start(serverArgs.host(), serverArgs.port().orElse(ServerArgs.DEFAULT_PORT));
    }

    private record ServerArgs(
            String host,
            Optional<Integer> port,
            Optional<Boolean> onlineMode,
            Optional<String> velocitySecret) {

        private static final int DEFAULT_PORT = 25565;

    }

}