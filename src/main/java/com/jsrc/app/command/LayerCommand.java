package com.jsrc.app.command;

import java.nio.file.Path;

import com.jsrc.app.architecture.LayerResolver;
import com.jsrc.app.exception.BadUsageException;

public class LayerCommand implements Command {
    private final String layerName;

    public LayerCommand(String layerName) {
        this.layerName = layerName;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (ctx.config() == null || ctx.config().architecture().layers().isEmpty()) {
            throw new BadUsageException("No architecture layers defined in .jsrc.yaml");
        }
        var allClasses = ctx.getAllClasses();
        var resolver = new LayerResolver(ctx.config().architecture().layers());
        var layerClasses = resolver.filterByLayer(allClasses, layerName);
        ctx.formatter().printClasses(layerClasses, Path.of(ctx.rootPath()));
        return layerClasses.size();
    }
}
