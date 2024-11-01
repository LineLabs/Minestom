package net.linelabs.extensions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandContext;
import net.minestom.server.command.builder.arguments.ArgumentString;
import net.minestom.server.command.builder.arguments.ArgumentType;

import java.util.Collection;

public final class ExtensionCommand extends Command {

    private static final ArgumentString NAME = ArgumentType.String("name");

    private final ExtensionManager extensionManager;

    public ExtensionCommand(ExtensionManager extensionManager) {
        super("extension", "ext");
        this.extensionManager = extensionManager;

        setCondition(this::isAllowed);
        setDefaultExecutor(this::defaultExecutor);
        addSyntax(this::handleUnload, ArgumentType.Literal("unload"), NAME);
        addSyntax(this::handleList, ArgumentType.Literal("list"));
    }

    private void defaultExecutor(CommandSender sender, CommandContext commandContext) {
        sender.sendMessage(Component.text("Correct usage: /extension unload <name>", NamedTextColor.RED));
    }

    private void handleUnload(CommandSender sender, CommandContext commandContext) {
        String extensionName = commandContext.get(NAME);

        if (extensionName == null) {
            sender.sendMessage(Component.text("No Extension provided!", NamedTextColor.RED));
            return;
        }

        try {
            this.extensionManager.unloadExtension(extensionName);
            sender.sendMessage(Component.text("Extension ", NamedTextColor.GRAY)
                    .append(Component.text(extensionName, NamedTextColor.GREEN))
                    .append(Component.text(" unloaded!", NamedTextColor.GRAY)));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleList(CommandSender sender, CommandContext commandContext) {
        Collection<Extension> extensions = this.extensionManager.getExtensions();
        sender.sendMessage(Component.text("Loaded Extensions (", NamedTextColor.GRAY)
                .append(Component.text(extensions.size(), NamedTextColor.GREEN))
                .append(Component.text("):", NamedTextColor.GRAY)));

        for (Extension extension : extensions) {
            sender.sendMessage(Component.text(extension.getOrigin().getName(), NamedTextColor.GRAY));
        }
    }

    private boolean isAllowed(CommandSender sender, String commandName) {
        return sender.hasPermission("net.linelabs.commands.extension");
    }

}