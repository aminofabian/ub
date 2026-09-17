package zelisline.ub.desktop.device;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Profile("desktop")
@RequiredArgsConstructor
@Slf4j
public class DesktopPrinterService {

    private final ObjectMapper objectMapper;

    @Value("${APP_DATA:${user.home}/.palmart}")
    private String appData;

    public PrinterConfig getConfig() {
        Path file = printerFile();
        if (!Files.exists(file)) {
            return PrinterConfig.defaults();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return normalize(objectMapper.readValue(json, PrinterConfig.class));
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file, e.toString());
            return PrinterConfig.defaults();
        }
    }

    public PrinterConfig saveConfig(PrinterConfig config) throws IOException {
        Path file = printerFile();
        Files.createDirectories(file.getParent());
        PrinterConfig normalized = normalize(config);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return normalized;
    }

    private static PrinterConfig normalize(PrinterConfig config) {
        if (config == null) {
            return PrinterConfig.defaults();
        }
        String mode = config.mode() != null && !config.mode().isBlank() ? config.mode().trim() : "file";
        String host = config.host() != null ? config.host().trim() : "";
        int port = config.port() > 0 ? config.port() : 9100;
        String path = config.path() != null ? config.path().trim() : "";
        String cupsName = config.cupsName() != null ? config.cupsName().trim() : "";
        return new PrinterConfig(mode, host, port, path, cupsName);
    }

    private Path printerFile() {
        return Path.of(appData).resolve("conf").resolve("printer.json");
    }
}
