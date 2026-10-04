package zelisline.ub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import zelisline.ub.integrations.backup.config.BackupProperties;
import zelisline.ub.platform.media.CloudinaryProperties;
import zelisline.ub.platform.media.R2Properties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({CloudinaryProperties.class, R2Properties.class, BackupProperties.class})
public class UbApplication {

    public static void main(String[] args) {
        SpringApplication.run(UbApplication.class, args);
    }
}
