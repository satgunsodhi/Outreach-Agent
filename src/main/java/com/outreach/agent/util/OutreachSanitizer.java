package com.outreach.agent.util;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class OutreachSanitizer {

    // ── Regex patterns for placeholder detection ──
    // Matches anything inside [...], <...>, or {...} that looks like a placeholder
    // e.g. [Hiring Manager's Name], <Recruiter Name>, {Company Name}, [Name], etc.
    private static final Pattern BRACKET_PLACEHOLDER = Pattern.compile(
            "\\[(?:Hiring Manager(?:'s)?(?:\\s+Name)?|Recruiter(?:'s)?(?:\\s+Name)?|" +
            "Your\\s+Name|Name|First\\s+Name|Last\\s+Name|Recipient(?:'s)?(?:\\s+Name)?|" +
            "Contact\\s+Name|HR\\s+Manager|Team\\s+Lead|Founder(?:'s)?(?:\\s+Name)?|" +
            "Company(?:\\s+Name)?|Company|Role|Position|Job\\s+Title)\\]",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ANGLE_PLACEHOLDER = Pattern.compile(
            "<(?:Hiring Manager(?:'s)?(?:\\s+Name)?|Recruiter(?:'s)?(?:\\s+Name)?|" +
            "PRIVATE_PERSON|Your\\s+Name|Name|First\\s+Name|Recipient(?:'s)?(?:\\s+Name)?|" +
            "COMPANY_NAME|Company(?:\\s+Name)?|HR\\s+Manager|Founder(?:'s)?(?:\\s+Name)?)>",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CURLY_PLACEHOLDER = Pattern.compile(
            "\\{(?:name|companyName|company|hiringManager|recruiter|recipientName|" +
            "yourName|firstName|lastName)\\}",
            Pattern.CASE_INSENSITIVE);

    // Matches common greeting lines with placeholders so we can clean them up
    // e.g. "Hi [Hiring Manager's Name]," → "Hi," or "Dear [Recruiter]," → "Hi,"
    private static final Pattern GREETING_WITH_PLACEHOLDER = Pattern.compile(
            "^(Hi|Hello|Dear|Hey)\\s+" +
            "(?:\\[.*?\\]|<.*?>|\\{.*?\\})" +
            "(\\s*[,:]?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    public String fillPlaceholders(String text, String candidateName, String companyName) {
        if (text == null) {
            return null;
        }

        // Step 1: Direct literal replacements (fast path for known exact tokens)
        text = text
                .replace("[Your Name]", candidateName)
                .replace("<PRIVATE_PERSON>", candidateName)
                .replace("YOUR_NAME", candidateName)
                .replace("{name}", candidateName)
                .replace("[Company]", companyName)
                .replace("[Company Name]", companyName)
                .replace("{companyName}", companyName)
                .replace("<COMPANY_NAME>", companyName);

        // Step 2: Fix greeting lines with any remaining placeholders
        // "Hi [Hiring Manager's Name]," → "Hi,"
        // "Dear [Recruiter]," → "Hi,"
        text = GREETING_WITH_PLACEHOLDER.matcher(text).replaceAll(mr -> {
            String greeting = mr.group(1);
            String punctuation = mr.group(2);
            // Normalize "Dear" to "Hi" for cold outreach
            if (greeting.equalsIgnoreCase("Dear")) {
                greeting = "Hi";
            }
            return greeting + (punctuation.isEmpty() ? "," : punctuation);
        });

        // Step 3: Regex-replace any remaining name/person placeholders with candidate name
        text = replacePersonPlaceholders(text, candidateName);

        // Step 4: Regex-replace any remaining company placeholders with company name
        text = replaceCompanyPlaceholders(text, companyName);

        // Step 5: Clean up any stray em-dashes the LLM loves to insert
        text = text.replace("\u2014", "-").replace("\u2013", "-").replace("\u2011", "-");

        // Step 6: Collapse any double newlines introduced by removals
        text = text.replaceAll("\n{3,}", "\n\n");

        return text.trim();
    }

    private String replacePersonPlaceholders(String text, String candidateName) {
        text = BRACKET_PLACEHOLDER.matcher(text).replaceAll(mr -> {
            String content = mr.group().toLowerCase();
            if (content.contains("company") || content.contains("role") || content.contains("position") || content.contains("job")) {
                return mr.group(); // Skip
            }
            return candidateName;
        });
        text = ANGLE_PLACEHOLDER.matcher(text).replaceAll(mr -> {
            String content = mr.group().toLowerCase();
            if (content.contains("company")) {
                return mr.group(); // Skip
            }
            return candidateName;
        });
        text = CURLY_PLACEHOLDER.matcher(text).replaceAll(mr -> {
            String content = mr.group().toLowerCase();
            if (content.contains("company") || content.contains("role") || content.contains("position") || content.contains("jobtitle")) {
                return mr.group(); // Skip
            }
            return candidateName;
        });
        return text;
    }

    private String replaceCompanyPlaceholders(String text, String companyName) {
        // Fix #7 + B4: only replace genuine company placeholders here.
        // [Role], [Position], {role}, {jobTitle} are role/title placeholders — replacing them with companyName produces nonsense.
        text = Pattern.compile(
                "\\[(?:Company(?:\\s+Name)?)\\]",
                Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(companyName);
        text = Pattern.compile(
                "<(?:COMPANY_NAME|Company(?:\\s+Name)?)>",
                Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(companyName);
        text = Pattern.compile(
                "\\{(?:companyName|company)\\}",
                Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(companyName);
        return text;
    }

    /**
     * E4: Uses the same compiled Patterns as fillPlaceholders() — every token variant
     * the filler can handle is now also caught by this guard.
     */
    public boolean containsPlaceholderTokens(String text) {
        if (text == null) {
            return false;
        }
        // Fast path: literal tokens that the regex patterns may not cover
        String upper = text.toUpperCase();
        if (upper.contains("YOUR_NAME") || upper.contains("YOUR COMPANY")) {
            return true;
        }
        // Pattern-based check — mirrors fillPlaceholders() logic
        return BRACKET_PLACEHOLDER.matcher(text).find()
                || ANGLE_PLACEHOLDER.matcher(text).find()
                || CURLY_PLACEHOLDER.matcher(text).find();
    }

    public String sanitizePdfPath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String trimmed = stripSurroundingQuotes(rawPath.trim());

        // Match the resume stem (with or without .pdf). Always emit with .pdf guaranteed.
        // Regex: matches "resume-" followed by word chars/dashes, optionally ending in ".pdf" or ".PDF"
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("resume-[\\w\\-]+(\\.pdf)?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(trimmed);
        if (matcher.find()) {
            String matched = matcher.group(0);
            // Strip any extension already present, then re-add .pdf canonically
            String stem = matched.replaceAll("(?i)\\.pdf$", "");
            return "data/generated-pdfs/" + stem + ".pdf";
        }

        // Fallback: return the raw path normalized to forward slashes
        return trimmed.replace("\\", "/");
    }

    /**
     * Strips matching surrounding quote or backtick pairs from a string.
     * Handles {@code `...`}, {@code "..."}, and {@code '...'}.
     */
    private String stripSurroundingQuotes(String s) {
        boolean stripped;
        do {
            stripped = false;
            for (char q : new char[]{'`', '"', '\''}) {
                if (s.length() >= 2 && s.charAt(0) == q && s.charAt(s.length() - 1) == q) {
                    s = s.substring(1, s.length() - 1).trim();
                    stripped = true;
                    break;
                }
            }
        } while (stripped);
        return s;
    }
}
