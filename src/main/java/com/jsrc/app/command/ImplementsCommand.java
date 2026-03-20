package com.jsrc.app.command;

import java.util.List;

import com.jsrc.app.model.HierarchyResult;
import com.jsrc.app.parser.model.ClassInfo;

public class ImplementsCommand implements Command {
    private final String ifaceName;

    public ImplementsCommand(String ifaceName) {
        this.ifaceName = ifaceName;
    }

    @Override
    public int execute(CommandContext ctx) {
        var allClasses = ctx.getAllClasses();
        List<String> implementors = allClasses.stream()
                .filter(ci -> ci.interfaces().stream().anyMatch(i -> {
                    String stripped = i.contains("<") ? i.substring(0, i.indexOf('<')) : i;
                    return stripped.equals(ifaceName);
                }))
                .map(ClassInfo::qualifiedName).toList();

        ctx.formatter().printHierarchy(new HierarchyResult(
                ifaceName, "", List.of(), List.of(), implementors));
        return implementors.size();
    }
}
