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
            return objectMapper.readValue(json, PrinterConfig.class);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file, e.toString());
            return PrinterConfig.defaults();
        }
    }

    public PrinterConfig saveConfig(PrinterConfig config) throws IOException {
        Path file = printerFile();
        Files.createDirectories(file.getParent());
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return config;
    }

    private Path printerFile() {
        return Path.of(appData).resolve("conf").resolve("printer.json");
    }
}
