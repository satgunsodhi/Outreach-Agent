package com.outreach.agent.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.repository.OutreachTargetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);

    private final OutreachTargetRepository outreachTargetRepository;
    private final ObjectMapper objectMapper;

    @Value("classpath:data/targets.json")
    private Resource targetsResource;

    public DatabaseSeeder(OutreachTargetRepository outreachTargetRepository, ObjectMapper objectMapper) {
        this.outreachTargetRepository = outreachTargetRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!targetsResource.exists()) {
            log.info("targets.json not found, skipping database seeding.");
            return;
        }

        try (InputStream is = targetsResource.getInputStream()) {
            List<OutreachTarget> targets = objectMapper.readValue(is, new TypeReference<List<OutreachTarget>>() {
            });

            for (OutreachTarget target : targets) {
                // Check if target with same email and company already exists
                boolean exists = outreachTargetRepository.existsByCompanyNameAndRecipientEmail(
                        target.getCompanyName(), target.getRecipientEmail());

                if (!exists) {
                    target.setStatus("PENDING");
                    outreachTargetRepository.save(target);
                    log.info("Seeded new OutreachTarget: {} ({})", target.getCompanyName(), target.getRecipientEmail());
                }
            }
            log.info("Database seeding completed.");
        } catch (Exception e) {
            log.error("Error seeding database from targets.json: {}", e.getMessage());
        }
    }
}
