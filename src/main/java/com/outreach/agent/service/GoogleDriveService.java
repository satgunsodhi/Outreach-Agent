package com.outreach.agent.service;

import com.google.api.client.http.FileContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

@Service
public class GoogleDriveService {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveService.class);

    private static final String APPLICATION_NAME = "Outreach Agent";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String DRIVE_FOLDER_NAME = "Outreach Resumes";

    private final GoogleOAuthService oauthService;

    @Value("${google.drive.folder-id:}")
    private String configuredFolderId;

    private Drive driveService;
    private String resumeFolderId;

    public GoogleDriveService(GoogleOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @PostConstruct
    public void init() {
        if (!oauthService.isAvailable()) {
            log.warn("OAuth not available. Drive uploads will fall back to local copy.");
            driveService = null;
            return;
        }

        try {
            driveService = new Drive.Builder(
                    oauthService.getHttpTransport(),
                    JSON_FACTORY,
                    oauthService.getCredential())
                    .setApplicationName(APPLICATION_NAME)
                    .build();

            if (configuredFolderId != null && !configuredFolderId.isBlank()) {
                resumeFolderId = configuredFolderId.trim();
                log.info("Drive service initialized. Using configured folder ID: {}", resumeFolderId);
            } else {
                resumeFolderId = getOrCreateFolder(DRIVE_FOLDER_NAME);
                log.info("Drive service initialized. Resume folder ID: {}", resumeFolderId);
            }
        } catch (Exception e) {
            log.error("Failed to initialize Google Drive: {}", e.getMessage());
            driveService = null;
        }
    }

    /**
     * Uploads a resume PDF to Google Drive and returns a public shareable link.
     * Falls back to a local file copy if Drive is not configured.
     */
    public String uploadResume(String localPdfPath) {
        if (driveService == null) {
            log.warn("Drive service not initialized. Using fallback.");
            return fallbackLocalCopy(localPdfPath);
        }

        try {
            Path source = Paths.get(localPdfPath);
            if (!Files.exists(source)) {
                throw new IllegalArgumentException("Source PDF file does not exist: " + localPdfPath);
            }

            File fileMetadata = new File();
            fileMetadata.setName(source.getFileName().toString());
            fileMetadata.setMimeType("application/pdf");

            if (resumeFolderId != null) {
                fileMetadata.setParents(Collections.singletonList(resumeFolderId));
            }

            FileContent mediaContent = new FileContent("application/pdf", source.toFile());
            File uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, webViewLink")
                    .setSupportsAllDrives(true)
                    .execute();

            // Make the file readable by anyone with the link
            try {
                com.google.api.services.drive.model.Permission permission =
                        new com.google.api.services.drive.model.Permission()
                                .setType("anyone")
                                .setRole("reader");
                driveService.permissions().create(uploadedFile.getId(), permission)
                        .setSupportsAllDrives(true)
                        .execute();
            } catch (Exception pe) {
                log.warn("Non-critical: Failed to make file public: {}", pe.getMessage());
            }

            String driveLink = "https://drive.google.com/file/d/" + uploadedFile.getId() + "/view?usp=sharing";
            log.info("Uploaded {} -> {}", localPdfPath, driveLink);
            return driveLink;

        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage(), e);
            return fallbackLocalCopy(localPdfPath);
        }
    }

    private String getOrCreateFolder(String folderName) throws IOException {
        var result = driveService.files().list()
                .setQ("name = '" + folderName + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute();

        if (!result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        }

        File folderMetadata = new File();
        folderMetadata.setName(folderName);
        folderMetadata.setMimeType("application/vnd.google-apps.folder");

        File folder = driveService.files().create(folderMetadata)
                .setFields("id")
                .setSupportsAllDrives(true)
                .execute();

        log.info("Created Drive folder '{}' with ID: {}", folderName, folder.getId());
        return folder.getId();
    }

    private String fallbackLocalCopy(String localPdfPath) {
        try {
            Path source = Paths.get(localPdfPath);
            if (!Files.exists(source)) {
                throw new IllegalArgumentException("Source PDF file does not exist: " + localPdfPath);
            }

            Path driveDir = Paths.get("data/google-drive-fallback");
            if (!Files.exists(driveDir)) {
                Files.createDirectories(driveDir);
            }

            String fileId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String fileName = "Resume_" + fileId + ".pdf";
            Path target = driveDir.resolve(fileName);
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            log.info("FALLBACK: Copied locally to {}", target.toAbsolutePath());
            return "https://drive.google.com/file/d/FALLBACK_" + fileId + "/view?usp=sharing";
        } catch (IOException e) {
            log.error("Fallback failed: {}", e.getMessage());
            return "https://drive.google.com/file/d/ERROR_NO_DRIVE_LINK/view?usp=sharing";
        }
    }
}
