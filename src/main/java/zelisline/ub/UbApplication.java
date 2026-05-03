package zelisline.ub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import zelisline.ub.platform.media.CloudinaryProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CloudinaryProperties.class)
public class UbApplication {

    public static void main(String[] args) {
        SpringApplication.run(UbApplication.class, args);
    }
}
