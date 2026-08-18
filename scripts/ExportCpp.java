import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class ExportCpp extends GhidraScript {
    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static PrintWriter writer(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        return new PrintWriter(new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(file), StandardCharsets.UTF_8)));
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        String root = args.length > 0 ? args[0] : "output/decompile/raw";
        String programName = safeName(currentProgram.getName());
        File programDir = new File(root, programName);
        programDir.mkdirs();

        File cFile = new File(programDir, programName + "_decompiled.c");
        File cppFile = new File(programDir, programName + "_decompiled.cpp");
        File indexFile = new File(programDir, "functions.tsv");
        File symbolsFile = new File(programDir, "ghidra_symbols.tsv");
        File failuresFile = new File(programDir, "decompile_failures.tsv");
        File summaryFile = new File(programDir, "summary.txt");

        DecompInterface decompiler = new DecompInterface();
        decompiler.toggleCCode(true);
        decompiler.toggleSyntaxTree(true);
        decompiler.setSimplificationStyle("decompile");

        if (!decompiler.openProgram(currentProgram)) {
            throw new RuntimeException("Could not open program in Ghidra decompiler: " + currentProgram.getName());
        }

        int total = 0;
        int completed = 0;
        int failed = 0;

        try (
            PrintWriter cOut = writer(cFile);
            PrintWriter cppOut = writer(cppFile);
            PrintWriter indexOut = writer(indexFile);
            PrintWriter symbolOut = writer(symbolsFile);
            PrintWriter failureOut = writer(failuresFile)
        ) {
            String banner = "/* Best-effort Ghidra decompilation of " + currentProgram.getName() + ".\n" +
                            " * This is reconstructed pseudo-source, not the original source code.\n" +
                            " */\n\n";
            cOut.print(banner);
            cOut.println("#include <stdint.h>");
            cOut.println("#include <stddef.h>\n");
            cppOut.print(banner);
            cppOut.println("#include <cstdint>");
            cppOut.println("#include <cstddef>\n");

            indexOut.println("entry\tname\tsignature");
            failureOut.println("entry\tname\terror");
            symbolOut.println("address\ttype\tname");

            SymbolIterator symbols = currentProgram.getSymbolTable().getAllSymbols(true);
            while (symbols.hasNext() && !monitor.isCancelled()) {
                Symbol symbol = symbols.next();
                String name = symbol.getName(true).replace('\t', ' ');
                symbolOut.println(symbol.getAddress() + "\t" + symbol.getSymbolType() + "\t" + name);
            }

            FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
            while (functions.hasNext() && !monitor.isCancelled()) {
                Function function = functions.next();
                total++;
                String entry = function.getEntryPoint().toString();
                String name = function.getName(true).replace('\t', ' ');
                String signature = function.getSignature().toString().replace('\t', ' ');
                indexOut.println(entry + "\t" + name + "\t" + signature);

                DecompileResults result = decompiler.decompileFunction(function, 90, monitor);
                if (result != null && result.decompileCompleted() && result.getDecompiledFunction() != null) {
                    String code = result.getDecompiledFunction().getC();
                    String marker = "\n/* ===== " + name + " @ " + entry + " ===== */\n";
                    cOut.print(marker);
                    cOut.println(code);
                    cppOut.print(marker);
                    cppOut.println(code);
                    completed++;
                } else {
                    failed++;
                    String error = result == null ? "no result" : result.getErrorMessage();
                    if (error == null || error.isEmpty()) {
                        error = "decompile did not complete";
                    }
                    failureOut.println(entry + "\t" + name + "\t" + error.replace('\t', ' ').replace('\n', ' '));
                }

                if ((total % 250) == 0) {
                    println("Decompiled " + total + " functions from " + currentProgram.getName());
                }
            }
        } finally {
            decompiler.dispose();
        }

        try (PrintWriter summary = writer(summaryFile)) {
            summary.println("program=" + currentProgram.getName());
            summary.println("language=" + currentProgram.getLanguageID());
            summary.println("compiler=" + currentProgram.getCompilerSpec().getCompilerSpecID());
            summary.println("image_base=" + currentProgram.getImageBase());
            summary.println("functions_total=" + total);
            summary.println("functions_decompiled=" + completed);
            summary.println("functions_failed=" + failed);
        }

        println("Export complete for " + currentProgram.getName() + ": " + completed + "/" + total + " functions decompiled");
    }
}
