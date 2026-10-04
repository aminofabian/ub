package zelisline.ub.platform.media;

/**
 * Stores support chat attachments (images, PDFs, Office and text files) when uploads go to R2.
 * While uploads stay on Cloudinary the browser uploads attachments there directly instead.
 */
public interface AttachmentStore {

    boolean isActive();

    CloudinaryUploadResult uploadAttachment(byte[] fileBytes, String originalFilename, String folderPath);
}
