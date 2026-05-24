package com.outreach.agent.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.outreach.agent.model.OutreachTarget;
import com.outreach.agent.repository.OutreachTargetRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

@Component
public class DatabaseSeeder implements CommandLineRunner {

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
            System.out.println("targets.json not found, skipping database seeding.");
            return;
        }

        try (InputStream is = targetsResource.getInputStream()) {
            List<OutreachTarget> targets = objectMapper.readValue(is, new TypeReference<List<OutreachTarget>>() {});
            
            for (OutreachTarget target : targets) {
                // Check if target with same email and company already exists
                boolean exists = outreachTargetRepository.findAll().stream()
                        .anyMatch(t -> t.getRecipientEmail().equalsIgnoreCase(target.getRecipientEmail()) &&
                                       t.getCompanyName().equalsIgnoreCase(target.getCompanyName()));
                                       
                if (!exists) {
                    target.setStatus("PENDING");
                    outreachTargetRepository.save(target);
                    System.out.println("Seeded new OutreachTarget: " + target.getCompanyName() + " (" + target.getRecipientEmail() + ")");
                }
            }
            System.out.println("Database seeding completed.");
        } catch (Exception e) {
            System.err.println("Error seeding database from targets.json: " + e.getMessage());
        }
    }
}
