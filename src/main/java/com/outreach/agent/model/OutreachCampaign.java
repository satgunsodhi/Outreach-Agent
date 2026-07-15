package com.outreach.agent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "outreach_campaigns")
public class OutreachCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private CampaignStatus status;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
