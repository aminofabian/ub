package zelisline.ub.desktop.device;

import java.io.IOException;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/printer")
@RequiredArgsConstructor
public class DesktopPrinterController {

    private final DesktopPrinterService printerService;

    @GetMapping
    public PrinterConfig get() {
        return printerService.getConfig();
    }

    @PutMapping
    public PrinterConfig put(@RequestBody PrinterConfig config) throws IOException {
        return printerService.saveConfig(config);
    }
}
