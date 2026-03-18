package com.jsrc.app.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.jsrc.app.exception.JsrcIOException;
import com.jsrc.app.output.JsonWriter;
import com.jsrc.app.parser.model.ClassInfo;
import com.jsrc.app.spec.SpecParser;
import com.jsrc.app.spec.SpecVerifier;

public class VerifyCommand implements Command {
    private final String className;
    private final String specPath;

    public VerifyCommand(String className, String specPath) {
        this.className = className;
        this.specPath = specPath;
    }

    @Override
    public int execute(CommandContext ctx) {
        try {
            var spec = SpecParser.parse(Path.of(specPath));
            var allClasses = ctx.getAllClasses();
            ClassInfo ci = SummaryCommand.resolveOrExit(allClasses, className);
            if (ci == null) return 0;

            var result = SpecVerifier.verify(ci, spec);
            ctx.formatter().printResult(result);
            @SuppressWarnings("unchecked")
            List<?> discs = (List<?>) result.get("discrepancies");
            return discs.size();
        } catch (IOException e) {
            throw new JsrcIOException("Error reading spec: " + e.getMessage(), e);
        }
    }
}
