package com.outreach.agent.runner;

import java.io.File;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CiBatchRunner.class);

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

        log.info("==================================================");
        log.info("   RUNNING OUTREACH AGENT IN CI BATCH MODE");
        log.info("==================================================");

        if (!googleOAuthService.isAvailable()) {
            log.error("Google OAuth Service is not available. Check GOOGLE_REFRESH_TOKEN and GOOGLE_SERVICE_ACCOUNT_JSON.");
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
            log.info("Loaded {} targets. {} new targets added.", targets.size(), added);
        } else {
            log.info("Targets file not found at: {}", targetsFilePath);
        }

        // 2. Process all PENDING targets
        log.info("Starting target processing...");
        batchOutreachService.processPendingTargets();

        // 3. Wait if there are still targets PROCESSING
        while (!repository.findByStatus("PROCESSING").isEmpty()) {
            log.info("Waiting for processing to complete...");
            Thread.sleep(5000);
        }

        log.info("==================================================");
        log.info("   CI BATCH MODE COMPLETE. EXITING.");
        log.info("==================================================");
        
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
