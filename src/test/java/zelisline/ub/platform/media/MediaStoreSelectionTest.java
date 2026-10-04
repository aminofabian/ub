package zelisline.ub.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

class MediaStoreSelectionTest {

    @Configuration
    @EnableConfigurationProperties({CloudinaryProperties.class, R2Properties.class})
    static class MediaProps {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(MediaProps.class, MediaStoreConfiguration.class,
                    CloudinaryImageService.class, R2MediaStore.class)
            .withPropertyValues(
                    "app.media.r2.account-id=acct",
                    "app.media.r2.bucket=picshare-media",
                    "app.media.r2.access-key-id=k",
                    "app.media.r2.secret-access-key=s",
                    "app.media.r2.public-base-url=https://media.example.com");

    @Test
    void cloudinaryIsTheStoreByDefault() {
        runner.run(ctx -> assertThat(ctx.getBean(MediaStore.class)).isInstanceOf(CloudinaryImageService.class));
    }

    @Test
    void r2WinsWhileCloudinaryStaysUpForSignedUploads() {
        runner.withPropertyValues("app.media.r2.enabled=true").run(ctx -> {
            assertThat(ctx.getBean(MediaStore.class)).isInstanceOf(R2MediaStore.class);
            assertThat(ctx).hasSingleBean(CloudinaryImageService.class);
        });
    }

    @Test
    void r2AloneDoesNotAlsoCreateTheNoOpStore() {
        runner.withPropertyValues("app.media.r2.enabled=true", "app.media.cloudinary.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MediaStore.class);
                    assertThat(ctx.getBean(MediaStore.class)).isInstanceOf(R2MediaStore.class);
                });
    }

    @Test
    void r2EnabledWithoutCredentialsFailsFast() {
        new ApplicationContextRunner()
                .withUserConfiguration(MediaProps.class, R2MediaStore.class)
                .withPropertyValues("app.media.r2.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("R2_BUCKET"));
    }
}
