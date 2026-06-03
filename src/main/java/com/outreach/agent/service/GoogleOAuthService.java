package com.outreach.agent.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.gmail.GmailScopes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Centralised Google OAuth 2.0 service.
 * <p>
 * Manages a single OAuth2 credential that covers both Google Drive and Gmail.
 * The credential is stored in the configurable {@code tokens/} directory so it
 * persists across restarts (no browser re-auth required after first run).
 * </p>
 * Usage: inject this service into {@link GoogleDriveService} and
 * {@link GmailService} — do NOT build credentials independently in those classes.
 */
@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    /**
     * OAuth 2.0 scopes requested from the user on first authorization.
     * Adding a new scope here will force re-authorization on next startup
     * if the stored token does not include it.
     */
    private static final List<String> SCOPES = List.of(
            DriveScopes.DRIVE,
            GmailScopes.GMAIL_COMPOSE
    );

    @Value("${google.drive.service-account-json:}")
    private String oauthClientSecretJson;

    @Value("${google.drive.tokens-directory:tokens}")
    private String tokensDirectoryPath;

    @Value("${google.oauth.refresh-token:}")
    private String headlessRefreshToken;

    private NetHttpTransport httpTransport;
    private Credential credential;

    @PostConstruct
    public void init() {
        if (oauthClientSecretJson == null || oauthClientSecretJson.isBlank()) {
            log.warn("OAuth2 client secret JSON not configured " +
                    "(GOOGLE_CLIENT_SECRETS_JSON in .env). Drive uploads and Gmail drafts will be disabled.");
            return;
        }

        try {
            httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            ByteArrayInputStream in = new ByteArrayInputStream(
                    oauthClientSecretJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                    .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(tokensDirectoryPath)))
                    .setAccessType("offline")
                    .build();

            if (headlessRefreshToken != null && !headlessRefreshToken.isBlank()) {
                com.google.api.client.auth.oauth2.TokenResponse response = new com.google.api.client.auth.oauth2.TokenResponse();
                response.setRefreshToken(headlessRefreshToken);
                credential = flow.createAndStoreCredential(response, "user");
                log.info("Headless CI Mode: Loaded OAuth credential from refresh token.");
            } else {
                // On first run: opens browser for OAuth consent. On subsequent runs: loads stored token.
                LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
                credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
                log.info("Authorized successfully. Scopes: Drive + Gmail Compose.");
            }
        } catch (Exception e) {
            log.error("Failed to initialize OAuth2 credential: {}", e.getMessage());
            credential = null;
            httpTransport = null;
        }
    }

    /**
     * Returns the authorized OAuth2 {@link Credential}, or {@code null} if not initialized.
     */
    public Credential getCredential() {
        return credential;
    }

    /**
     * Returns the shared {@link NetHttpTransport}, or {@code null} if not initialized.
     */
    public NetHttpTransport getHttpTransport() {
        return httpTransport;
    }

    /**
     * Convenience check — returns true if OAuth is configured and the credential was obtained.
     */
    public boolean isAvailable() {
        return credential != null && httpTransport != null;
    }
}
