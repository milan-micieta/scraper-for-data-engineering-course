package core.server.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class PythonService {

    @Value("${python.exe:python}")
    private String pythonExe;

    @Value("${kiwi.python.script}")
    private String pythonScript;

    @Value("${kiwi.python.timeout-seconds:150}")
    private long timeoutSeconds;

    /**
     * Spustí kiwi_scraper.py v režime --json (jednosmerná cesta, iba priame lety)
     * a vráti jeho stdout (čistý JSON). stderr skriptu (logy) ide do konzoly aplikácie.
     */
    public String runKiwiScraper(String origin, String dest, String depart) throws Exception {
        List<String> cmd = List.of(pythonExe, pythonScript, origin, dest, depart, "--json", "--no-csv");
        return run(cmd, timeoutSeconds);
    }

    public String run(List<String> cmd, long timeoutSeconds) throws Exception {

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.environment().put("PYTHONUTF8", "1");
        Process process = pb.start();
        process.getOutputStream().close();

        // stdout čítame asynchrónne, aby timeout fungoval aj pri zaseknutom procese
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new RuntimeException("Python scraper prekročil limit " + timeoutSeconds + " s");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("Python scraper skončil s kódom " + process.exitValue());
        }
        return stdout.get(10, TimeUnit.SECONDS);
    }

    public void runStreaming(List<String> cmd, long timeoutSeconds, Consumer<String> lineConsumer)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.environment().put("PYTHONUTF8", "1");
        Process process = pb.start();
        process.getOutputStream().close();

        AtomicReference<RuntimeException> consumerFailure = new AtomicReference<>();
        CompletableFuture<Void> stdout = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (consumerFailure.get() != null) {
                        continue;
                    }
                    try {
                        lineConsumer.accept(line);
                    } catch (RuntimeException e) {
                        consumerFailure.compareAndSet(null, e);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new RuntimeException("Python scraper prekročil limit " + timeoutSeconds + " s");
        }
        stdout.get(10, TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            throw new RuntimeException("Python scraper skončil s kódom " + process.exitValue());
        }
        if (consumerFailure.get() != null) {
            throw consumerFailure.get();
        }
    }
}
