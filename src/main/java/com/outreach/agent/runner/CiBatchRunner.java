package com.outreach.agent.runner;

import java.io.File;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.repository.OutreachTargetRepository;
import com.outreach.agent.service.BatchOutreachService;
import com.outreach.agent.service.GoogleOAuthService;

@Component
public class CiBatchRunner implements ApplicationRunner {

    private final BatchOutreachService batchOutreachService;
    private final OutreachTargetRepository repository;
    private final ApplicationContext context;
    private final GoogleOAuthService googleOAuthService;

    @Value("${app.ci-batch-mode:false}")
    private boolean ciBatchMode;

    @Value("${app.targets-file:targets.json}")
    private String targetsFilePath;

    public CiBatchRunner(BatchOutreachService batchOutreachService, OutreachTargetRepository repository,
            ApplicationContext context, GoogleOAuthService googleOAuthService) {
        this.batchOutreachService = batchOutreachService;
        this.repository = repository;
        this.context = context;
        this.googleOAuthService = googleOAuthService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!ciBatchMode) {
            return;
        }

        System.out.println("==================================================");
        System.out.println("   RUNNING OUTREACH AGENT IN CI BATCH MODE");
        System.out.println("==================================================");

        if (!googleOAuthService.isAvailable()) {
            System.err.println("[CI BATCH] ERROR: Google OAuth Service is not available. Check GOOGLE_REFRESH_TOKEN and GOOGLE_SERVICE_ACCOUNT_JSON.");
            System.exit(SpringApplication.exit(context, () -> 1));
            return;
        }

        // 1. Load targets
        File targetsFile = new File(targetsFilePath);
        if (targetsFile.exists()) {
            ObjectMapper mapper = new ObjectMapper();
            List<OutreachTarget> targets = mapper.readValue(targetsFile, new TypeReference<List<OutreachTarget>>() {});
            int added = 0;
            for (OutreachTarget t : targets) {
                if (!repository.existsByCompanyNameAndRecipientEmail(t.getCompanyName(), t.getRecipientEmail())) {
                    t.setStatus("PENDING");
                    repository.save(t);
                    added++;
                }
            }
            System.out.println("[CI BATCH] Loaded " + targets.size() + " targets. " + added + " new targets added.");
        } else {
            System.out.println("[CI BATCH] Targets file not found at: " + targetsFilePath);
        }

        // 2. Process all PENDING targets
        System.out.println("[CI BATCH] Starting target processing...");
        batchOutreachService.processPendingTargets();

        // 3. Wait if there are still targets PROCESSING
        while (!repository.findByStatus("PROCESSING").isEmpty()) {
            System.out.println("[CI BATCH] Waiting for processing to complete...");
            Thread.sleep(5000);
        }

        System.out.println("==================================================");
        System.out.println("   CI BATCH MODE COMPLETE. EXITING.");
        System.out.println("==================================================");
        
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
