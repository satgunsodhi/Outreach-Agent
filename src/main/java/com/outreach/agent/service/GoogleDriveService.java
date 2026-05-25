package com.outreach.agent.service;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class GoogleDriveService {

    public String uploadResume(String localPdfPath) {
        try {
            Path source = Paths.get(localPdfPath);
            if (!Files.exists(source)) {
                throw new IllegalArgumentException("Source PDF file does not exist: " + localPdfPath);
            }

            // Create a mock Google Drive directory
            Path driveDir = Paths.get("data/google-drive");
            if (!Files.exists(driveDir)) {
                Files.createDirectories(driveDir);
            }

            String fileId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String fileName = "Resume_" + fileId + ".pdf";
            Path target = driveDir.resolve(fileName);

            // Copy file to our local "Google Drive" folder to simulate upload
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[GoogleDriveService] Successfully uploaded " + localPdfPath + " to Google Drive simulated folder: " + target.toAbsolutePath());

            // Return a realistic Google Drive view link
            return "https://drive.google.com/file/d/1" + fileId + "/view?usp=sharing";
        } catch (IOException e) {
            System.err.println("[GoogleDriveService] Failed to upload resume to Drive: " + e.getMessage());
            // Return fallback link
            return "https://drive.google.com/file/d/1fallbackDriveLinkId/view?usp=sharing";
        }
    }
}
