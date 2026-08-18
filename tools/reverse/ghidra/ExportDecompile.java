import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class ExportDecompile extends GhidraScript {
    private PrintWriter writer(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
    }

    private String tsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private String safeName(String value) {
        String cleaned = value == null ? "function" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120);
        }
        return cleaned.isEmpty() ? "function" : cleaned;
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: ExportDecompile.java <output-directory>");
        }

        File outDir = new File(args[0]);
        File functionDir = new File(outDir, "functions");
        outDir.mkdirs();
        functionDir.mkdirs();

        try (PrintWriter meta = writer(new File(outDir, "program.txt"))) {
            meta.println("name=" + currentProgram.getName());
            meta.println("format=" + currentProgram.getExecutableFormat());
            meta.println("language=" + currentProgram.getLanguageID());
            meta.println("compiler_spec=" + currentProgram.getCompilerSpec().getCompilerSpecID());
            meta.println("image_base=" + currentProgram.getImageBase());
            meta.println("min_address=" + currentProgram.getMinAddress());
            meta.println("max_address=" + currentProgram.getMaxAddress());
        }

        try (PrintWriter symbols = writer(new File(outDir, "symbols.tsv"))) {
            symbols.println("address\tname\tnamespace\ttype\tsource\tprimary\tdynamic\texternal");
            SymbolIterator it = currentProgram.getSymbolTable().getAllSymbols(true);
            while (it.hasNext() && !monitor.isCancelled()) {
                Symbol s = it.next();
                String namespace = s.getParentNamespace() == null ? "" : s.getParentNamespace().getName();
                symbols.printf("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s%n",
                        tsv(String.valueOf(s.getAddress())),
                        tsv(s.getName()),
                        tsv(namespace),
                        tsv(String.valueOf(s.getSymbolType())),
                        tsv(String.valueOf(s.getSource())),
                        s.isPrimary(),
                        s.isDynamic(),
                        s.isExternal());
            }
        }

        DecompInterface decompiler = new DecompInterface();
        decompiler.toggleCCode(true);
        decompiler.toggleSyntaxTree(true);
        decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) {
            throw new IllegalStateException("Ghidra decompiler could not open the program");
        }

        int ok = 0;
        int failed = 0;
        try (PrintWriter index = writer(new File(outDir, "functions.tsv"));
             PrintWriter combined = writer(new File(outDir, "decompiled.c"));
             PrintWriter errors = writer(new File(outDir, "decompile_errors.tsv"))) {

            index.println("address\tname\tnamespace\tsignature\tcalling_convention\tthunk\texternal\tstatus\tfile");
            errors.println("address\tname\terror");

            FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
            while (functions.hasNext() && !monitor.isCancelled()) {
                Function f = functions.next();
                String address = String.valueOf(f.getEntryPoint());
                String namespace = f.getParentNamespace() == null ? "" : f.getParentNamespace().getName();
                String fileName = safeName(address.replace(':', '_') + "_" + f.getName()) + ".c";
                String status = "ok";
                String code = null;

                try {
                    DecompileResults result = decompiler.decompileFunction(f, 180, monitor);
                    if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                        code = result.getDecompiledFunction().getC();
                    } else {
                        status = "failed";
                        String error = result == null ? "no decompiler result" : result.getErrorMessage();
                        errors.printf("%s\t%s\t%s%n", tsv(address), tsv(f.getName()), tsv(error));
                    }
                } catch (Exception ex) {
                    status = "failed";
                    errors.printf("%s\t%s\t%s%n", tsv(address), tsv(f.getName()), tsv(ex.toString()));
                }

                if (code != null) {
                    try (PrintWriter one = writer(new File(functionDir, fileName))) {
                        one.println("/* " + address + " " + f.getName() + " */");
                        one.println(code);
                    }
                    combined.println("\n/* ========================================================================== */");
                    combined.println("/* " + address + " " + f.getName() + " */");
                    combined.println("/* ========================================================================== */");
                    combined.println(code);
                    ok++;
                } else {
                    failed++;
                }

                index.printf("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s%n",
                        tsv(address),
                        tsv(f.getName()),
                        tsv(namespace),
                        tsv(f.getSignature().toString()),
                        tsv(f.getCallingConventionName()),
                        f.isThunk(),
                        f.isExternal(),
                        status,
                        code == null ? "" : tsv("functions/" + fileName));
            }
        } finally {
            decompiler.dispose();
        }

        try (PrintWriter summary = writer(new File(outDir, "summary.txt"))) {
            summary.println("decompiled_ok=" + ok);
            summary.println("decompiled_failed=" + failed);
            summary.println("cancelled=" + monitor.isCancelled());
        }
    }
}
